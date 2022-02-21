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
                 on-disconnect
                 on-error
                 on-message
                 on-connect
                 on-pong ; clj only
                 protocols-seq]
          :or {close-timeout-ms 10000
               connect-timeout-ms 30000
               max-payload-len 65000
               on-disconnect (fn [code])
               on-error (fn [e]
                          (log/error (u/ex-msg-and-stacktrace e)))
               on-message (fn [ws msg])
               on-connect (fn [ws protocol])
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
  (let [msg-type (u/get-msg-type data)]
    ((:send! ws) msg-type data)))

(defn send-ping!
  ([ws]
   (send-ping! ws nil))
  ([ws payload-ba]
   ((:send! ws) :ping payload-ba (constantly nil))))
