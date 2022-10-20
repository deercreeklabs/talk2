(ns com.deercreeklabs.talk2.ws-server
  (:require
   [clojure.core.async :as ca]
   [clojure.string :as str]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [com.deercreeklabs.talk2.utils :as u]
   [primitive-math]
   [taoensso.timbre :as log])
  (:import
   (java.io ByteArrayInputStream
            IOException)
   (java.net InetSocketAddress
             ServerSocket
             SocketAddress
             StandardSocketOptions)
   (java.nio ByteBuffer)
   (java.nio.channels AsynchronousByteChannel
                      AsynchronousChannel
                      AsynchronousChannelGroup
                      AsynchronousCloseException
                      AsynchronousServerSocketChannel
                      AsynchronousSocketChannel
                      ClosedChannelException
                      CompletionHandler
                      ServerSocketChannel
                      ShutdownChannelGroupException
                      SocketChannel)
   (java.nio.channels.spi AsynchronousChannelProvider)
   (java.security KeyFactory
                  KeyStore
                  Security)
   (java.security.cert Certificate
                       CertificateFactory
                       X509Certificate)
   (java.security.interfaces RSAPrivateKey)
   (java.security.spec PKCS8EncodedKeySpec)
   (java.util.concurrent CancellationException
                         Executors)
   (javax.net.ssl KeyManagerFactory
                  SSLContext)
   (tlschannel ServerTlsChannel
               ServerTlsChannel$Builder
               TlsChannel)
   (tlschannel.async AsynchronousTlsChannel
                     AsynchronousTlsChannelGroup)))

(primitive-math/use-primitive-operators)

(defn str->input-stream [s]
  (ByteArrayInputStream. (.getBytes ^String s)))

(defn make-private-key [pkey-str]
  (let [pattern (re-pattern (str "-----BEGIN PRIVATE KEY-----\n"
                                 "([^-]+)"
                                 "\n-----END PRIVATE KEY-----"))
        contents (-> (re-find pattern pkey-str)
                     (second)
                     (str/split-lines)
                     (str/join))
        ba (ba/b64->byte-array contents)
        spec (PKCS8EncodedKeySpec. ba)
        ^KeyFactory factory (KeyFactory/getInstance "RSA")]
    (.generatePrivate factory spec)))

(defn make-cert [cert-str]
  (let [^CertificateFactory factory (CertificateFactory/getInstance "X.509")]
    (.generateCertificate factory (str->input-stream cert-str))))

(defn make-ssl-ctx [cert-str pkey-str]
  (let [^SSLContext ctx (SSLContext/getInstance "TLSv1.3")
        ^KeyStore keystore (KeyStore/getInstance "JKS")
        ^KeyManagerFactory kmf (KeyManagerFactory/getInstance "SunX509")
        ^X509Certificate cert (make-cert cert-str)
        ^RSAPrivateKey k (make-private-key pkey-str)
        chain (into-array Certificate [cert])]
    (.load keystore nil)
    (.setCertificateEntry keystore "cert-alias" cert)
    (.setKeyEntry keystore "key-alias" k (char-array 0) chain)
    (.init kmf keystore (char-array 0))
    (.init ctx (.getKeyManagers kmf) nil nil)
    ctx))

(defn completion-handler [on-completed on-failed]
  (reify CompletionHandler
    (completed [this result attachment]
      (on-completed this result))
    (failed [this e attachment]
      (on-failed this e))))

(defn choose-protocol [client-protcols-set prioritized-protocols-seq]
  (let [protocol (reduce (fn [acc protocol]
                           (if (client-protcols-set protocol)
                             (reduced protocol)
                             acc))
                         :no-match
                         prioritized-protocols-seq)]
    (if (not= :no-match protocol)
      protocol
      (throw (ex-info "No matching protocol found."
                      (u/sym-map client-protcols-set
                                 prioritized-protocols-seq))))))

(defn request-info [{:keys [headers first-line]} remote-address conn-id]
  (let [[method path http-version] (str/split first-line #"\s")
        get? (= "GET" method)
        ws-upgrade? (re-matches #"(?i).*websocket.*"
                                (or (:upgrade headers) ""))
        connection-upgrade? (re-matches #"(?i).*upgrade.*"
                                        (or (:connection headers) ""))
        version-13? (= "13" (:sec-websocket-version headers))
        proper-upgrade-req? (and get?
                                 ws-upgrade?
                                 connection-upgrade?
                                 version-13?)
        ws-key (:sec-websocket-key headers)
        make-set (fn [protocols]
                   (let [empties #{"" "null" nil}]
                     (reduce (fn [acc protocol]
                               (if (empties protocol)
                                 acc
                                 (conj acc protocol)))
                             #{} protocols)))
        client-protocols-set (some-> (:sec-websocket-protocol headers)
                                     (str/replace #"\s" "")
                                     (str/split #",")
                                     (make-set))]
    (when-not proper-upgrade-req?
      (log/error
       (str "Got improper upgrade request:\n"
            (u/sym-map method headers first-line get? ws-upgrade?
                       connection-upgrade? version-13?
                       proper-upgrade-req? ws-key client-protocols-set
                       remote-address conn-id))))
    (u/sym-map proper-upgrade-req? ws-key http-version path
               client-protocols-set)))

(defn make-upgrade-rsp [req-info protocol]
  (let [{:keys [http-version ws-key]} req-info
        ws-accept-key (u/ws-key->ws-accept-key ws-key)
        lines (cond-> [(str http-version " 101 Switching Protocols")
                       "Upgrade: websocket"
                       "Connection: Upgrade"
                       (str "Sec-WebSocket-Accept: " ws-accept-key)]
                (seq protocol) (conj (str "Sec-Websocket-Protocol: "
                                          protocol)))]
    (-> (str/join "\r\n" lines)
        (str "\r\n\r\n")
        (ba/utf8->byte-array))))

(defn <keepalive-loop [conn keepalive-interval-secs *open? *server-running?]
  (ca/go
    (try
      (let [{:keys [send!]} conn]
        (while (and @*open? @*server-running?)
          (ca/<! (ca/timeout (* 1000 (int keepalive-interval-secs))))
          (send! :ping nil (constantly nil))))
      (catch Exception e
        (log/error (u/ex-msg-and-stacktrace e))
        ((:close! conn))))))

(defn <connection-loop
  [rcv-ch conn unprocessed-ba *open? *server-running?]
  (ca/go
    (try
      (let [{:keys [send! close! *on-message *on-ping *on-pong]} conn
            on-close-frame (fn []
                             (let [cb (fn [ret]
                                        (close!))]
                               (send! :close nil cb)))
            on-message (or @*on-message (constantly nil))
            on-ping (or @*on-ping (fn [{:keys [data]}]
                                    (send! :pong data (constantly nil))))
            on-pong (or @*on-pong (constantly nil))
            server? true]
        (loop [ba unprocessed-ba
               continuation-ba nil
               continuation-opcode nil]
          (when (and @*open? @*server-running?)
            (let [ret (u/process-data! (u/sym-map ba
                                                  close!
                                                  continuation-ba
                                                  continuation-opcode
                                                  on-close-frame
                                                  on-message
                                                  on-ping
                                                  on-pong
                                                  server?))
                  new-ba (au/<? rcv-ch)]
              (when new-ba
                (recur (ba/concat-byte-arrays [(:unprocessed-ba ret) new-ba])
                       (:continuation-ba ret)
                       (:continuation-opcode ret)))))))
      (catch Exception e
        (log/error (u/ex-msg-and-stacktrace e))
        ((:close! conn))))))

(defn send-ba!
  [^AsynchronousByteChannel async-nio-ch ba cb]
  (let [buf (ByteBuffer/wrap ba)
        h (completion-handler
           (fn on-completed [handler result]
             (if (pos? (.remaining buf))
               (if-not (.isOpen async-nio-ch)
                 (cb false)
                 (try
                   (.write async-nio-ch buf nil handler)
                   (catch ShutdownChannelGroupException e
                     (cb false))
                   (catch Exception e
                     (cb e))))
               (cb true)))
           (fn on-failed [handler e]
             (cb e)))]
    (if-not (.isOpen async-nio-ch)
      (cb false)
      (try
        (.write async-nio-ch buf nil h)
        (catch ShutdownChannelGroupException e
          (cb false))
        (catch Exception e
          (cb e))))))

(defn <send-ba! [async-nio-ch ba]
  (let [ch (ca/chan)
        cb (fn [result]
             (ca/put! ch result))]
    (send-ba! async-nio-ch ba cb)
    ch))

(defn <send-data! [{:keys [async-nio-ch data max-payload-len msg-type]}]
  (au/go
    (let [data-size (count data)
          bas (u/data->frame-byte-arrays msg-type data false max-payload-len)
          last-i (dec (count bas))]
      (loop [i 0]
        (let [ba (nth bas i)
              ret (au/<? (<send-ba! async-nio-ch ba))]
          (cond
            (not ret) ; channel was closed
            false

            (not= last-i i)
            (recur (inc (int i)))

            :else
            true))))))

(defn make-conn
  [{:keys [close-conn! conn-id path protocol remote-address send-ch]}]
  {:conn-id conn-id
   :close! close-conn!
   :path path
   :protocol protocol
   :remote-address remote-address
   :send! (fn [msg-type data cb]
            (ca/put! send-ch (u/sym-map msg-type data cb)))
   :*on-message (atom (constantly nil))
   :*on-ping (atom (constantly nil))
   :*on-pong (atom (constantly nil))})

(defn <connect
  [async-nio-ch rcv-ch send-ch remote-address conn-id *open? *server-running?
   close-conn! on-connect max-payload-len disable-keepalive?
   keepalive-interval-secs prioritized-protocols-seq]
  (ca/go
    (try
      (loop [unprocessed-ba nil]
        (when (and @*open? @*server-running?)
          (let [ba (au/<? rcv-ch)
                new-ba (ba/concat-byte-arrays [unprocessed-ba ba])
                http-info (u/byte-array->http-info new-ba)]
            (if-not (:complete? http-info)
              (recur new-ba)
              (let [req-info (request-info http-info remote-address conn-id)
                    {:keys [client-protocols-set
                            path
                            proper-upgrade-req?]} req-info]
                (if-not proper-upgrade-req?
                  (close-conn!)
                  (let [protocol (when (seq client-protocols-set)
                                   (choose-protocol client-protocols-set
                                                    prioritized-protocols-seq))
                        rsp-ba (make-upgrade-rsp req-info protocol)
                        ret (au/<? (<send-ba! async-nio-ch rsp-ba))
                        _ (when-not ret
                            (close-conn!))
                        conn (make-conn (u/sym-map close-conn! conn-id
                                                   max-payload-len
                                                   path protocol
                                                   remote-address send-ch))]
                    (on-connect conn)
                    (<connection-loop rcv-ch conn (:unprocessed-ba http-info)
                                      *open? *server-running?)
                    (when-not disable-keepalive?
                      (<keepalive-loop conn keepalive-interval-secs
                                       *open? *server-running?)))))))))
      (catch AsynchronousCloseException e
        (close-conn!))
      (catch Exception e
        (close-conn!)
        (log/error (u/ex-msg-and-stacktrace e))))))

(defn <rcv-loop
  [^AsynchronousByteChannel async-nio-ch rcv-ch close-conn! *open?
   *server-running?]
  (ca/go
    (try
      (let [^ByteBuffer rcv-buf (ByteBuffer/allocate 10000)
            h (completion-handler
               (fn on-completed [handler n]
                 (try
                   (if (neg? n)
                     (close-conn!)
                     (do
                       (when (pos? n)
                         (let [_ (.flip rcv-buf)
                               len (.remaining rcv-buf)
                               ba (ba/byte-array len)]
                           (.get rcv-buf ba 0 len)
                           (.clear rcv-buf)
                           (ca/put! rcv-ch ba
                                    (fn [open?]
                                      (when (and open?
                                                 @*open?
                                                 @*server-running?
                                                 (.isOpen async-nio-ch))
                                        (.read async-nio-ch rcv-buf
                                               nil handler))))))))))
               (fn on-failed [handler e]
                 (close-conn!)
                 ;; Ignore exceptions from closing channel
                 (when-not (or (instance? AsynchronousCloseException e)
                               (instance? CancellationException e)
                               (instance? IOException e))
                   (log/error (u/ex-msg-and-stacktrace e)))))]
        (.read async-nio-ch rcv-buf nil h))
      (catch Exception e
        (close-conn!)
        (log/error (str "Error in receive loop:\n"
                        (u/ex-msg-and-stacktrace e)))))))

(defn <send-loop
  [{:keys [*open? *server-running? async-nio-ch close-conn!
           max-payload-len send-ch]}]
  (ca/go-loop []
    (try
      (let [[send-info ch] (au/alts? [send-ch (ca/timeout 1000)])]
        (when (and (= send-ch ch) send-info)
          (let [{:keys [cb data msg-type]} send-info
                ret (au/<? (<send-data! (u/sym-map async-nio-ch cb data
                                                   max-payload-len msg-type)))]
            (when cb
              (cb ret)))))
      (catch AsynchronousCloseException e
        (close-conn!))
      (catch ClosedChannelException e
        (close-conn!))
      (catch Exception e
        (close-conn!)
        (log/error (str "Error in send loop:\n"
                        (u/ex-msg-and-stacktrace e)))))
    (when (and @*open? @*server-running?)
      (recur))))

(defn do-connect!
  [^AsynchronousChannel async-nio-ch remote-address on-connect on-disconnect
   max-payload-len disable-keepalive? keepalive-interval-secs
   prioritized-protocols-seq *next-conn-id *server-running?]
  (let [rcv-ch (ca/chan 1000)
        send-ch (ca/chan 1000)
        *on-disconnect-called? (atom false)
        *open? (atom true)
        conn-id (swap! *next-conn-id #(inc %))
        close-conn! (fn []
                      (reset! *open? false)
                      (ca/close! rcv-ch)
                      (ca/close! send-ch)
                      (when (.isOpen async-nio-ch)
                        (.close async-nio-ch))
                      (when (compare-and-set! *on-disconnect-called? false true)
                        (on-disconnect (u/sym-map conn-id))))]
    (when async-nio-ch
      (<rcv-loop async-nio-ch rcv-ch close-conn! *open? *server-running?)
      (<send-loop (u/sym-map *open? *server-running? async-nio-ch close-conn!
                             max-payload-len send-ch))
      (<connect async-nio-ch rcv-ch send-ch remote-address conn-id *open?
                *server-running? close-conn! on-connect max-payload-len
                disable-keepalive? keepalive-interval-secs
                prioritized-protocols-seq))))

(defn <accept-loop-non-tls
  [port group *next-conn-id *server-running? stop-server! on-connect
   on-disconnect max-payload-len disable-keepalive? keepalive-interval-secs
   prioritized-protocols-seq]
  (ca/go
    (try
      (let [^AsynchronousServerSocketChannel
            server (AsynchronousServerSocketChannel/open (:obj group))
            h (completion-handler
               (fn on-completed [this ^AsynchronousSocketChannel async-nio-ch]
                 (let [remote-addr (.getRemoteAddress async-nio-ch)
                       remote-addr-str (.toString ^SocketAddress remote-addr)]
                   (do-connect! async-nio-ch remote-addr-str on-connect
                                on-disconnect max-payload-len disable-keepalive?
                                keepalive-interval-secs
                                prioritized-protocols-seq *next-conn-id
                                *server-running?)
                   (if @*server-running?
                     (.accept server nil this)
                     (.close server))))
               (fn on-failed [this e]
                 ;; Ignore exceptions from closing channel
                 (when-not (or (instance? CancellationException e)
                               (instance? AsynchronousCloseException e))
                   (log/error (u/ex-msg-and-stacktrace e)))))]
        (.setOption server StandardSocketOptions/SO_REUSEADDR true)
        (.bind server (InetSocketAddress. port))
        (.accept server nil h))
      (catch IOException e ; happens if group is closed
        nil)
      (catch Exception e
        (log/error (u/ex-msg-and-stacktrace e))
        (stop-server!)))))

(defn <accept-loop-tls
  [port group *next-conn-id *server-running? stop-server! on-connect
   on-disconnect max-payload-len disable-keepalive? keepalive-interval-secs
   prioritized-protocols-seq]
  (ca/go
    (try
      (let [^ServerSocketChannel server-nio-ch (ServerSocketChannel/open)]
        (.configureBlocking server-nio-ch false)
        (.setOption server-nio-ch StandardSocketOptions/SO_REUSEADDR true)
        (.bind server-nio-ch (InetSocketAddress. port))
        (loop []
          (let [^SocketChannel raw-nio-ch (.accept server-nio-ch)]
            (if-not raw-nio-ch
              (ca/<! (ca/timeout 100))
              (let [^SSLContext ssl-ctx (:ssl-ctx group)
                    _ (.configureBlocking raw-nio-ch false)
                    ^ServerTlsChannel$Builder builder (ServerTlsChannel/newBuilder
                                                       ^SocketChannel raw-nio-ch
                                                       ^SSLContext ssl-ctx)
                    ^TlsChannel tls-nio-ch (.build builder)
                    ^AsynchronousTlsChannelGroup group-obj (:obj group)
                    async-nio-ch (AsynchronousTlsChannel. group-obj tls-nio-ch
                                                          raw-nio-ch)
                    remote-address (.getRemoteAddress raw-nio-ch)
                    remote-addr-str (.toString ^SocketAddress remote-address)]
                (do-connect! async-nio-ch remote-addr-str on-connect
                             on-disconnect max-payload-len disable-keepalive?
                             keepalive-interval-secs prioritized-protocols-seq
                             *next-conn-id *server-running?))))
          (if @*server-running?
            (recur)
            (do
              (.close ^ServerSocket (.socket server-nio-ch))
              (.close server-nio-ch)))))
      (catch Exception e
        (log/error (u/ex-msg-and-stacktrace e))
        (stop-server!)))))

(defn async-nio-ch-group []
  (let [^AsynchronousChannelProvider
        provider (AsynchronousChannelProvider/provider)
        ^Runtime runtime (Runtime/getRuntime)
        num-threads (.availableProcessors runtime)
        factory (Executors/defaultThreadFactory)]
    (.openAsynchronousChannelGroup provider num-threads factory)))

(defn make-group [ssl-ctx]
  (let [group (if ssl-ctx
                (AsynchronousTlsChannelGroup.)
                (async-nio-ch-group))]
    {:obj group
     :shutdown-now! #(if ssl-ctx
                       (.shutdownNow ^AsynchronousTlsChannelGroup group)
                       (.shutdownNow ^AsynchronousChannelGroup group))
     :ssl-ctx ssl-ctx}))

(defn check-config [config]
  (let [{:keys [certificate-str
                disable-keepalive?
                dns-cache-secs
                keepalive-interval-secs
                private-key-str
                on-connect
                on-disconnect
                port]} config]
    (when certificate-str
      (when-not (string? certificate-str)
        (throw (ex-info
                (str "`:certificate-str` parameter must be a string. "
                     "Got: " certificate-str)
                (u/sym-map certificate-str)))())
      (when-not (string? private-key-str)
        (throw (ex-info
                (str "`:certificate-str` was given, but not `:private-key-str`."
                     " Both are required to use TLS certificate.")
                (u/sym-map certificate-str private-key-str)))))
    (when private-key-str
      (when-not (string? private-key-str)
        (throw (ex-info
                (str "`:private-key-str` parameter must be a string. "
                     "Got: " private-key-str)
                (u/sym-map private-key-str)))())
      (when-not (string? certificate-str)
        (throw (ex-info
                (str "`:certificate-str` was given, but not `:private-key-str`."
                     " Both are required to use an SSL certificate.")
                (u/sym-map certificate-str private-key-str)))))
    (when (and dns-cache-secs (not (integer? dns-cache-secs)))
      (throw (ex-info
              (str "`:dns-cache-secs` parameter must be an integer. Got: `"
                   dns-cache-secs "`.")
              (u/sym-map dns-cache-secs))))
    (when (and keepalive-interval-secs (not (integer? keepalive-interval-secs)))
      (throw (ex-info
              (str "`:keepalive-interval-secs` parameter must be an integer. "
                   "Got: `" keepalive-interval-secs "`.")
              (u/sym-map keepalive-interval-secs))))
    (when (and (contains? config :disable-keepalive?)
               (not (boolean? disable-keepalive?)))
      (throw (ex-info
              (str "`:disable-keepalive?` parameter must be a boolean. Got: `"
                   disable-keepalive? "`.")
              (u/sym-map disable-keepalive?))))
    (when-not on-connect
      (throw (ex-info
              "You must provide a :on-connect fn in the config."
              config)))
    (when-not (ifn? on-connect)
      (throw (ex-info
              (str "`:on-connect` value must be a function. Got: `"
                   on-connect "`.")
              (u/sym-map on-connect))))
    (when (and on-disconnect (not (ifn? on-disconnect)))
      (throw (ex-info
              (str "`:on-disconnect` value must be a function. Got: `"
                   on-disconnect "`.")
              (u/sym-map on-disconnect))))
    (when (and port (not (integer? port)))
      (throw (ex-info
              (str "`:port` parameter must be an integer. "
                   "Got: `" port "`.")
              (u/sym-map port))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Public API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ws-server [config]
  (check-config config)
  (let [{:keys [certificate-str
                disable-keepalive?
                dns-cache-secs
                keepalive-interval-secs
                max-payload-len
                on-connect
                on-disconnect
                port
                prioritized-protocols-seq
                private-key-str]
         :or {dns-cache-secs 60
              keepalive-interval-secs 30
              max-payload-len 65000
              on-connect (constantly nil)
              on-disconnect (constantly nil)
              port 8000}} config
        ssl-ctx (when (and certificate-str private-key-str)
                  (make-ssl-ctx certificate-str private-key-str))
        group (make-group ssl-ctx)
        *next-conn-id (atom 0)
        *server-running? (atom true)
        stop-server! (fn []
                       (if-not (compare-and-set! *server-running? true false)
                         (log/info "Server is not running.")
                         (do
                           (log/info "Stopping server...")
                           ((:shutdown-now! group)))))
        <accept-loop (if ssl-ctx
                       <accept-loop-tls
                       <accept-loop-non-tls)]
    (log/info (str "Starting server on port " port "."))
    (log/info (str "TLS? " (boolean ssl-ctx)))
    (Security/setProperty "networkaddress.cache.ttl" (str dns-cache-secs))
    (<accept-loop port group *next-conn-id *server-running? stop-server!
                  on-connect on-disconnect max-payload-len
                  disable-keepalive? keepalive-interval-secs
                  prioritized-protocols-seq)
    (u/sym-map stop-server!)))

(defn stop! [server]
  ((:stop-server! server)))

(defn send!
  ([conn data]
   (send! conn data nil))
  ([conn data cb]
   (let [msg-type (u/get-msg-type data)]
     ((:send! conn) msg-type data cb))))

(defn send-ping!
  ([conn]
   (send-ping! conn nil))
  ([conn payload-ba]
   ((:send! conn) :ping payload-ba (constantly nil))))

(defn set-on-message! [conn f]
  (reset! (:*on-message conn) f))

(defn set-on-ping! [conn f]
  (reset! (:*on-ping conn) f))

(defn set-on-pong! [conn f]
  (reset! (:*on-pong conn) f))


;; TODO: Send a close frame when server initiates a close on a connection?
