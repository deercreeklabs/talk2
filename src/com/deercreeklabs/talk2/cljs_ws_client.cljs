(ns com.deercreeklabs.talk2.cljs-ws-client
  (:require
   [applied-science.js-interop :as j]
   [com.deercreeklabs.talk2.utils :as u]
   [taoensso.timbre :as log]))

(defn make-ws
  [uri protocols-seq on-disconnect on-error on-message on-connect]
  (let [js-ws (if (empty? protocols-seq)
                (js/WebSocket. uri)
                (js/WebSocket. uri protocols-seq))
        get-state (fn []
                    (case (j/get js-ws :readyState)
                      0 :connecting
                      1 :open
                      2 :closing
                      3 :closed))
        ws {:get-state get-state
            :send! (fn [msg-type data]
                     (when (= :open (get-state))
                       (j/call js-ws :send (if (= :binary msg-type)
                                             (j/get data :buffer)
                                             data))))
            :close! (fn [code]
                      (j/call js-ws :close code))}]
    (j/assoc! js-ws :binaryType "arraybuffer")
    (j/assoc! js-ws :onclose (fn [e]
                               (on-disconnect {:code (j/get e :code)})))
    (j/assoc! js-ws :onerror (fn [e]
                               (on-error {:error e})))
    (j/assoc! js-ws :onmessage (fn [msg]
                                 (let [data (j/get msg :data)
                                       arg {:ws ws
                                            :data (if (string? data)
                                                    data
                                                    (js/Int8Array. data))}]
                                   (on-message arg))))
    (j/assoc! js-ws :onopen (fn [e]
                              (on-connect {:ws ws
                                           :protocol (j/get js-ws :protocol)})))
    ws))
