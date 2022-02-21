(ns deercreeklabs.talk2.cljs-ws-client
  (:require
   [deercreeklabs.talk2.utils :as u]
   [taoensso.timbre :as log]))

(defn make-ws
  [uri protocols-seq on-disconnect on-error on-message on-connect]
  (let [js-ws (if (empty? protocols-seq)
                (js/WebSocket. uri)
                (js/WebSocket. uri protocols-seq))
        ws {:send! (fn [msg-type data]
                     (when (= 1 (.readyState js-ws)) ;; OPEN state
                       (.send js-ws (if (= :binary msg-type)
                                      (.-buffer data)
                                      data))))
            :close! (fn [code]
                      (.close js-ws code))}]
    (set! (.-binaryType js-ws) "arraybuffer")
    (set! (.-onclose js-ws) (fn [e]
                              (on-disconnect (.-code e))))
    (set! (.-onerror js-ws) (fn [e]
                              (on-error e)))
    (set! (.-onmessage js-ws) (fn [msg]
                                (let [data (.-data msg)]
                                  (on-message ws (if (string? data)
                                                   data
                                                   (js/Int8Array. data))))))
    (set! (.-onopen js-ws) (fn [e]
                             (on-connect
                              {:ws ws
                               :protocol (.-protocol js-ws)})))
    ws))
