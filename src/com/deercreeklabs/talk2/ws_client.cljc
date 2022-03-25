(ns com.deercreeklabs.talk2.ws-client
  (:require
   [clojure.core.async :as ca]
   [deercreeklabs.baracus :as ba]
   #?(:clj [com.deercreeklabs.talk2.clj-ws-client :as clj-ws-client]
      :cljs [com.deercreeklabs.talk2.cljs-ws-client :as cljs-ws-client])
   [com.deercreeklabs.talk2.utils :as u]
   [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Public API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn websocket
  ([url]
   (websocket url {}))
  ([url opts]
   (let [{:keys [close-timeout-ms ; clj only
                 connect-timeout-ms ; clj only
                 make-raw-websocket
                 max-payload-len ; clj only
                 on-disconnect
                 on-error
                 on-message
                 on-connect
                 on-pong ; clj only
                 protocols-seq]
          :or {close-timeout-ms 10000
               connect-timeout-ms 30000
               max-payload-len 65000
               on-disconnect (fn [{:keys [code]}])
               on-error (fn [{:keys [error]}]
                          (log/error (u/ex-msg-and-stacktrace error)))
               on-message (fn [{:keys [ws msg]}])
               on-connect (fn [{:keys [ws protocol]}])
               on-pong (fn [])
               protocols-seq []}} opts]
     (let [arg (u/sym-map close-timeout-ms connect-timeout-ms max-payload-len
                          on-connect on-disconnect on-error on-message on-pong
                          protocols-seq url)]
       (if make-raw-websocket
         (make-raw-websocket arg)
         #?(:clj (clj-ws-client/make-raw-websocket arg)
            :cljs (cljs-ws-client/make-raw-websocket arg)))))))

(defn close!
  ([ws]
   (close! ws 1000))
  ([ws code]
   ((:close! ws) code)))

(defn send!
  [ws data]
  (let [{:keys [get-state]} ws
        max-attempts 600
        initial-wait-ms 10
        max-wait-ms 1000]
    (ca/go
      (try
        (let [start-ms (u/current-time-ms)]
          (loop [wait-ms initial-wait-ms
                 attempts-remaining max-attempts]
            (when (zero? attempts-remaining)
              (let [elapsed-secs (/ (- (u/current-time-ms) start-ms) 1000)]
                (throw (ex-info (str "send! timed out waiting for websocket "
                                     "to connect after " elapsed-secs ".")
                                (u/sym-map ws data elapsed-secs)))))
            (case (get-state)
              :connecting
              (let [new-wait-ms (min (* 2 wait-ms) max-wait-ms)]
                (ca/<! (ca/timeout wait-ms))
                (recur new-wait-ms (dec attempts-remaining)))

              :open
              ((:send! ws) (u/get-msg-type data) data)

              :closing
              (throw (ex-info "send! failed because websocket is closing." {}))

              :closed
              (throw (ex-info "send! failed because websocket is closed." {})))))
        (catch #?(:clj Exception :cljs js/Error) e
          (log/error (str "Error in send!:\n"
                          (u/ex-msg-and-stacktrace e))))))))

(defn send-ping!
  ([ws]
   (send-ping! ws nil))
  ([ws payload-ba]
   ((:send! ws) :ping payload-ba (constantly nil))))
