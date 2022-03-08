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
     #?(:clj (clj-ws-client/make-ws url close-timeout-ms connect-timeout-ms
                                    protocols-seq max-payload-len
                                    on-disconnect on-error on-message
                                    on-connect on-pong)
        :cljs (cljs-ws-client/make-ws url protocols-seq on-disconnect on-error
                                      on-message on-connect)))))

(defn close!
  ([ws]
   (close! ws 1000))
  ([ws code]
   ((:close! ws) code)))

(defn send!
  [ws data]
  (let [{:keys [get-state send!]} ws
        max-attempts 600
        wait-ms 100]
    (ca/go
      (try
        (loop [attempts-remaining max-attempts]
          (when (zero? attempts-remaining)
            (throw (ex-info (str ("send! timed out waiting for websocket "
                                  "to open. (" (* max-attempts wait-ms) "ms)"))
                            (u/sym-map ws data wait-ms max-attempts))))
          (case (get-state)
            :connecting
            (do
              (ca/<! (ca/timeout wait-ms))
              (recur (dec attempts-remaining)))

            :open
            (send! (u/get-msg-type data) data)

            :closing
            (throw (ex-info "send! failed because websocket is closing." {}))

            :closed
            (throw (ex-info "send! failed because websocket is closed." {}))))
        (catch #?(:clj Exception :cljs js/Error) e
          (log/error (str "Error in send!:\n"
                          (u/ex-msg-and-stacktrace e))))))))

(defn send-ping!
  ([ws]
   (send-ping! ws nil))
  ([ws payload-ba]
   ((:send! ws) :ping payload-ba (constantly nil))))
