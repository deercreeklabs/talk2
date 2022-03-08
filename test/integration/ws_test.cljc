(ns integration.ws-test
  (:require
   [clojure.core.async :as ca]
   [clojure.test :refer [deftest is]]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [com.deercreeklabs.talk2.utils :as u]
   [com.deercreeklabs.talk2.ws-client :as ws-client]
   [integration.bytes :as bytes]
   [taoensso.timbre :as log]))

;;;; IMPORTANT!!! You must start two servers for these tests to work.
;;;; $ bin/run-ws-echo-server
;;;; $ bin/run-ws-tls-echo-server

(def normal-url "ws://localhost:9080")
(def tls-url "wss://localhost:9443/ws")

(defn <send-ws-msg-and-return-rsp [arg-map]
  (au/go
    (let [{:keys [expected-protocol
                  msg
                  protocols-seq
                  timeout-ms
                  url]
           :or {timeout-ms 5000}} arg-map
          rcv-ch (ca/chan)
          connected-ch (ca/chan)
          opts {:on-error (fn [{:keys [error]}]
                            (ca/put! rcv-ch error))
                :on-message (fn [{:keys [ws data]}]
                              (ca/put! rcv-ch data))
                :on-connect (fn [{:keys [ws protocol] :as arg}]
                              (ca/put! connected-ch arg))
                :protocols-seq protocols-seq}
          ws (ws-client/websocket url opts)]
      (when-not ws
        (throw (ex-info "Failed to construct WebSocket"
                        {:type :execution-error
                         :subtype :construction-failure})))
      (let [[{:keys [ws protocol]} ch] (au/alts? [connected-ch
                                                  (ca/timeout timeout-ms)])]
        (if (not= connected-ch ch)
          (throw (ex-info "WebSocket failed to connect in time."
                          (u/sym-map timeout-ms)))
          (try
            (when (and expected-protocol (not= expected-protocol protocol))
              (throw (ex-info (str "Did not get expected protocol. Expected `"
                                   expected-protocol "`. Got: `"
                                   (or protocol nil)
                                   "`.")
                              (u/sym-map expected-protocol protocol))))
            (ws-client/send! ws msg)
            (let [[ret ch] (au/alts? [rcv-ch (ca/timeout timeout-ms)])]
              (if (= rcv-ch ch)
                ret
                (throw (ex-info "Timed out waiting for client response"
                                {:type :execution-error
                                 :subtype :timeout
                                 :timeout timeout-ms}))))
            (catch #?(:clj Exception :cljs js/Error) e
              (log/error (u/ex-msg-and-stacktrace e)))
            (finally
              (ws-client/close! ws))))))))

(deftest test-round-trip-w-small-bin-msg
  (au/test-async
   20000
   (au/go
     (let [msg (ba/byte-array [72 101 108 108 111 32 119 111 114 108 100 33])
           norm-rsp (au/<? (<send-ws-msg-and-return-rsp {:url normal-url
                                                         :msg msg}))
           #_#_tls-rsp (au/<? (<send-ws-msg-and-return-rsp {:url tls-url
                                                            :msg msg}))]
       (is (not= nil norm-rsp))
       #_(is (not= nil tls-rsp))
       (when norm-rsp
         (is (ba/equivalent-byte-arrays? msg norm-rsp)))
       #_(when tls-rsp
           (is (ba/equivalent-byte-arrays? msg tls-rsp)))))))

(deftest test-round-trip-w-small-text-msg
  (au/test-async
   20000
   (ca/go
     (let [msg "This is a nice text message."
           norm-rsp (au/<? (<send-ws-msg-and-return-rsp {:url normal-url
                                                         :msg msg}))
           #_#_tls-rsp (au/<? (<send-ws-msg-and-return-rsp {:url tls-url
                                                            :msg msg}))]
       (is (= msg norm-rsp))
       #_(is (= msg tls-rsp))))))

(deftest test-round-trip-w-large-bin-msg
  (au/test-async
   20000
   (au/go
     (let [msg bytes/bytes-1M
           norm-rsp (au/<? (<send-ws-msg-and-return-rsp {:url normal-url
                                                         :msg msg}))
           #_#_tls-rsp (au/<? (<send-ws-msg-and-return-rsp {:url tls-url
                                                            :msg msg}))]
       (is (not= nil norm-rsp))
       #_(is (not= nil tls-rsp))
       (when norm-rsp
         (is (ba/equivalent-byte-arrays? msg norm-rsp)))
       #_(when tls-rsp
           (is (ba/equivalent-byte-arrays? msg tls-rsp)))))))

(deftest test-protocol-selection
  (au/test-async
   20000
   (au/go
     (let [msg "jkldlkdjkladjklads dskjda"
           rsp (au/<? (<send-ws-msg-and-return-rsp
                       {:expected-protocol "talk2-2"
                        :msg msg
                        :protocols-seq ["talk2-1" "talk2-2" "adfa"]
                        :url normal-url}))]
       (is (not= nil rsp))
       (when rsp
         (is (= msg rsp)))))))
