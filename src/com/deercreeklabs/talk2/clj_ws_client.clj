(ns com.deercreeklabs.talk2.clj-ws-client
  (:require
   [clojure.core.async :as ca]
   [clojure.string :as str]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [com.deercreeklabs.talk2.utils :as u]
   [lambdaisland.uri :as uri]
   [primitive-math]
   [taoensso.timbre :as log])
  (:import
   (clojure.lang ExceptionInfo)
   (java.io IOException)
   (java.net InetSocketAddress)
   (java.nio ByteBuffer)
   (java.nio.channels ClosedChannelException
                      ByteChannel
                      SocketChannel)
   (javax.net.ssl SSLContext)
   (tlschannel ClientTlsChannel
               NeedsReadException
               NeedsWriteException
               WouldBlockException)))

(set! *warn-on-reflection* true)
(primitive-math/use-primitive-operators)

(defn make-upgrade-req-ba [host port path ws-key protocols-seq]
  (let [lines (cond-> [(str "GET " path " HTTP/1.1")
                       (str "Host: " host ":" port)
                       "User-Agent: talk2.client"
                       "Upgrade: websocket"
                       "Connection: Upgrade"
                       (str "Sec-WebSocket-Key: " ws-key)
                       "Sec-WebSocket-Version: 13"]
                (seq protocols-seq) (conj (str "Sec-WebSocket-Protocol: "
                                               (str/join ", " protocols-seq))))]
    (-> (str/join "\r\n" lines)
        (str "\r\n\r\n")
        (ba/utf8->byte-array))))

(defn write-ba-to-nio-chan [^ByteChannel nio-ch ba *state]
  (loop [^ByteBuffer buf (ByteBuffer/wrap ba)]
    (let [n (try
              (.write nio-ch buf)
              (catch WouldBlockException e
                0)
              (catch NeedsReadException e
                0)
              (catch NeedsWriteException e
                0)
              (catch ClosedChannelException e
                -1)
              (catch IOException e
                -1))]
      (cond
        (or (neg? n)
            (= :closed @*state))
        false

        (pos? (.remaining buf))
        (recur buf)

        :else
        true))))

(defn upgrade-info [rsp-info expected-ws-accept]
  (let [{:keys [complete? headers first-line]} rsp-info
        status (some->> first-line
                        (re-matches #"\s*HTTP/\d\.\d\s+(\d+)\s+.*")
                        (second)
                        (u/str->int))
        connection (some-> (:connection headers)
                           (str/lower-case))
        upgrade (some-> (:upgrade headers)
                        (str/lower-case))
        protocol (:sec-websocket-protocol headers)
        valid? (and
                (= 101 status)
                (= "upgrade" connection)
                (= "websocket" upgrade)
                (= expected-ws-accept (:sec-websocket-accept headers)))]
    (u/sym-map complete? valid? protocol)))

(defn <read*
  ([nio-ch rcv-buf handle-ba on-conn-close close! *state]
   (<read* nio-ch rcv-buf handle-ba on-conn-close close! *state nil))
  ([^ByteChannel nio-ch ^ByteBuffer rcv-buf handle-ba on-conn-close close!
    *state timeout-ms]
   (au/go
     (let [expire-ms (when timeout-ms
                       (+ (long (u/current-time-ms)) (long timeout-ms)))]
       (loop [rcv-ba (ba/byte-array 0)]
         (let [n (try
                   (.read nio-ch rcv-buf)
                   (catch WouldBlockException e
                     0)
                   (catch NeedsReadException e
                     0)
                   (catch NeedsWriteException e
                     0)
                   (catch ClosedChannelException e
                     -1))]
           (cond
             (and expire-ms (>= (long (u/current-time-ms)) (long expire-ms)))
             (throw (ex-info (str "Timed out waiting for message from server "
                                  " after " timeout-ms " ms.")
                             {:cause :timeout
                              :timeout timeout-ms}))

             (and (not= :closed @*state)
                  (zero? (int n)))
             (do
               (ca/<! (ca/timeout 10))
               (recur rcv-ba))

             (neg? n)
             (on-conn-close)

             :else
             (do
               (.flip rcv-buf)
               (let [len (.remaining rcv-buf)
                     ba (ba/byte-array len)
                     _ (.get rcv-buf ba 0 len)
                     _ (.clear rcv-buf)
                     new-rcv-ba (ba/concat-byte-arrays [rcv-ba ba])
                     {:keys [complete? valid? ret]} (handle-ba new-rcv-ba)]
                 (cond
                   (not complete?)
                   (when (not= :closed @*state)
                     (recur new-rcv-ba))

                   valid?
                   ret

                   :else
                   (let [hex-data (ba/byte-array->hex-str new-rcv-ba)]
                     (log/error (str "Received data is invalid. Got (hex) `"
                                     hex-data "`."))
                     (close! 1002))))))))))))

(defn <connect-ws
  [host port path timeout-ms protocols-seq ^SocketChannel raw-nio-ch nio-ch
   ^ByteBuffer rcv-buf on-conn-close close! *state]
  (au/go
    (let [expire-ms (+ (long (u/current-time-ms)) (long timeout-ms))
          throw-timeout #(throw (ex-info
                                 (str "Timed out connecting to host `" host
                                      "` after " timeout-ms " ms.")
                                 (u/sym-map timeout-ms host port path)))
          ws-key (-> (u/secure-random-byte-array 16)
                     (ba/byte-array->b64))
          upgrade-req-ba (make-upgrade-req-ba host port path ws-key
                                              protocols-seq)
          expected-ws-accept (u/ws-key->ws-accept-key ws-key)]
      ;; Wait for socket connection
      (loop []
        (cond
          (>= (long (u/current-time-ms)) (long expire-ms))
          (throw-timeout)

          (not (.finishConnect raw-nio-ch))
          (do
            (ca/<! (ca/timeout 100))
            (recur))))
      (if-not (write-ba-to-nio-chan nio-ch upgrade-req-ba *state)
        (on-conn-close)
        (let [handle-ba (fn [ba]
                          (let [rsp-info (u/byte-array->http-info ba)
                                info (upgrade-info rsp-info expected-ws-accept)
                                {:keys [valid? complete? protocol]} info
                                ret {:connected? true
                                     :protocol protocol
                                     :unprocessed-ba (:unprocessed-ba
                                                      rsp-info)}]
                            (u/sym-map complete? valid? ret)))
              remaining-timeout-ms (- (long expire-ms)
                                      (long (u/current-time-ms)))]
          (try
            (au/<? (<read* nio-ch rcv-buf handle-ba on-conn-close close! *state
                           remaining-timeout-ms))
            (catch ExceptionInfo e
              (if (= :timeout (:cause (ex-data e)))
                (throw-timeout)
                (throw e)))))))))

(defn <read-bytes
  [nio-ch ^ByteBuffer rcv-buf on-conn-close close! *state]
  (au/go
    (let [handle-ba (fn [ba]
                      {:complete? true
                       :ret ba
                       :valid? true})]
      (try
        (au/<? (<read* nio-ch rcv-buf handle-ba on-conn-close close! *state
                       1000))
        (catch ExceptionInfo e
          (if (= :timeout (:cause (ex-data e)))
            nil
            (throw e)))))))

(defn <rcv-loop
  [nio-ch rcv-buf initial-ba on-conn-close on-close-frame on-error on-message
   on-ping on-pong close! *state]
  (ca/go
    (try
      (loop [unprocessed-ba initial-ba
             continuation-ba nil
             continuation-opcode nil]
        (when (not= :closed @*state)
          (let [frame-info (u/byte-array->frame-info unprocessed-ba)]
            (if (and (:complete-header? frame-info)
                     (:complete-payload? frame-info))
              (let [ret (u/process-frame! frame-info continuation-ba
                                          continuation-opcode on-close-frame
                                          on-message on-ping on-pong)]
                (recur (:unprocessed-ba frame-info)
                       (:continuation-ba ret)
                       (:continuation-opcode ret)))
              (let [new-ba (au/<? (<read-bytes nio-ch rcv-buf on-conn-close
                                               close! *state))
                    new-unprocessed-ba (if new-ba
                                         (ba/concat-byte-arrays [unprocessed-ba
                                                                 new-ba])
                                         unprocessed-ba)]
                (recur new-unprocessed-ba
                       continuation-ba
                       continuation-opcode))))))
      (catch Exception e
        (close! 1001)
        (on-error {:error e})))))

(defn send-data! [msg-type data nio-ch max-payload-len *state]
  (reduce (fn [acc ba]
            (if (write-ba-to-nio-chan nio-ch ba *state)
              acc
              (reduced false)))
          true
          (u/data->frame-byte-arrays msg-type data true max-payload-len)))

(defn <wait-for-conn-closure [code timeout-ms close! on-error *state]
  (ca/go
    (let [expire-ms (+ (long (u/current-time-ms)) (long timeout-ms))]
      (try
        (loop []
          (ca/<! (ca/timeout 50))
          (cond
            (= :closed @*state) ; connection was closed
            nil

            (>= (long (u/current-time-ms)) (long expire-ms))
            (close! code)

            :else
            (recur)))
        (catch Exception e
          (close! 1001)
          (on-error {:error e}))))))

(defn check-scheme [scheme]
  (when-not (#{"ws" "wss"} scheme)
    (throw (ex-info (str "Bad URL scheme `" scheme "`. Must be `ws` or `wss`.")
                    (u/sym-map scheme)))))

(defn make-ws
  [url close-timeout-ms connect-timeout-ms protocols-seq max-payload-len
   on-disconnect on-error on-message on-connect on-pong]
  (let [^ByteBuffer rcv-buf (ByteBuffer/allocate 10000) ; bytes
        *state (atom :connecting)
        *on-disconnect-called? (atom nil) ; Changes to false when connected
        *client-close-code (atom 1000)
        {:keys [scheme host port path]} (uri/uri url)
        _ (check-scheme scheme)
        tls? (= "wss" scheme)
        port* (cond
                port (u/str->int port)
                tls? 443
                :else 80)
        path* (if (empty? path)
                "/"
                path)
        host-addr (InetSocketAddress. ^String host (int port*))
        ^SocketChannel raw-nio-ch (SocketChannel/open)
        _ (.configureBlocking raw-nio-ch false)
        _ (.connect raw-nio-ch host-addr)
        ^ByteChannel nio-ch (if-not tls?
                              raw-nio-ch
                              (.build (ClientTlsChannel/newBuilder
                                       ^ByteChannel raw-nio-ch
                                       ^SSLContext (SSLContext/getDefault))))
        close! (fn [code]
                 (reset! *state :closed)
                 (try
                   (.close raw-nio-ch)
                   (catch ClosedChannelException e))
                 (when tls?
                   (try
                     (.close nio-ch)
                     (catch ClosedChannelException e)))
                 (when (compare-and-set! *on-disconnect-called? false true)
                   ;; compare-and-set! fails if ws is not connected yet,
                   ;; because *on-disconnect-called? is set to nil until
                   ;; the connection succeeds, then it is set to false.
                   (on-disconnect code))
                 nil)
        send! (fn [msg-type data]
                (let [state @*state]
                  (when (or (= :open state)
                            (and (= :closing state)
                                 (= :close msg-type)))
                    (send-data! msg-type data nio-ch max-payload-len *state)
                    nil)))
        send-close! (fn [code]
                      (send! :close
                             (u/code->byte-array code)))
        <wait-for-conn-closure* #(<wait-for-conn-closure % close-timeout-ms
                                                         close! on-error
                                                         *state)
        do-sending-close! (fn [code]
                            (reset! *state :closing)
                            (reset! *client-close-code code)
                            (send-close! code)
                            (<wait-for-conn-closure* code)
                            nil)
        ws {:get-state (fn []
                         @*state)
            :send! send!
            :close! (fn [code]
                      (case @*state
                        :connecting (close! code)
                        :open (do-sending-close! code)
                        :closing nil
                        :closed nil)
                      nil)}
        on-conn-close (fn []
                        (case @*state
                          :connecting (close! 1005)
                          :open (close! 1005)
                          :closing (close! @*client-close-code)
                          :closed nil))
        on-close-frame (fn []
                         (case @*state
                           :open (do-sending-close! 1000)
                           :closing (<wait-for-conn-closure*
                                     @*client-close-code)
                           :closed nil))
        on-ping #(send! :pong %)]
    (ca/go
      (try
        (let [connect-ret (au/<? (<connect-ws host port* path*
                                              connect-timeout-ms protocols-seq
                                              raw-nio-ch nio-ch rcv-buf
                                              on-conn-close close! *state))
              {:keys [connected? unprocessed-ba protocol]} connect-ret]
          (when connected?
            (reset! *state :open)
            (reset! *on-disconnect-called? false)
            (<rcv-loop nio-ch rcv-buf unprocessed-ba on-conn-close
                       on-close-frame on-error on-message
                       on-ping on-pong close! *state)
            (when on-connect
              (on-connect (u/sym-map ws protocol)))))
        (catch Exception e
          (do-sending-close! 1001)
          (on-error {:error e}))))
    ws))
