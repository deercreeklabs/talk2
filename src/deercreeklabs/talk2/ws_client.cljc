(ns deercreeklabs.talk2.ws-client
  (:require
   [deercreeklabs.baracus :as ba]
   #?(:clj [deercreeklabs.talk2.clj-ws-client :as clj-ws-client]
      :cljs [deercreeklabs.talk2.cljs-ws-client :as cljs-ws-client])
   [deercreeklabs.talk2.utils :as u]
   [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Public API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn websocket
  ([url]
   (websocket url {}))
  ([url opts]
   (let [{:keys [close-timeout-ms ; clj only
                 connect-timeout-ms ; clj only
                 max-payload-len ; clj only
                 on-close
                 on-error
                 on-message
                 on-open
                 on-pong ; clj only
                 protocols-seq]
          :or {close-timeout-ms 10000
               connect-timeout-ms 30000
               max-payload-len 65000
               on-close (fn [code])
               on-error (fn [e]
                          (log/error (u/ex-msg-and-stacktrace e)))
               on-message (fn [ws msg])
               on-open (fn [ws protocol])
               on-pong (fn [])
               protocols-seq []}} opts]
     #?(:clj (clj-ws-client/make-ws url close-timeout-ms connect-timeout-ms
                                    protocols-seq max-payload-len
                                    on-close on-error on-message
                                    on-open on-pong)
        :cljs (cljs-ws-client/make-ws url protocols-seq on-close on-error
                                      on-message on-open)))))

(defn close!
  ([ws]
   (close! ws 1000))
  ([ws code]
   ((:close! ws) code)))

(defn send!
  [ws data]
  (let [msg-type (u/get-msg-type data)]
    ((:send! ws) msg-type data)))

(defn send-ping!
  ([ws]
   (send-ping! ws nil))
  ([ws payload-ba]
   ((:send! ws) :ping payload-ba (constantly nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (def opts
    {:on-close #(log/info (str "Closing w/ code " %))
     :on-error  #(log/info (str "Error:\n" (u/ex-msg-and-stacktrace %)))
     :on-message #(log/info (str "Got " (count %) " bytes:\n" %))
     :on-open (fn [protocol]
                (log/info (if protocol
                            (str "Connected w/ protocol `" protocol "`.")
                            "Connected.")))
     :on-pong #(log/info (str "Got pong frame."
                              (when-not (empty? %)
                                (str " Payload: "
                                     (ba/byte-array->hex-str %)))))})
  (def ws (websocket "wss://echo.websocket.org" opts))
  (def ws (websocket "wss://localhost:8443" opts)))
