(ns integration.talk2-test
  (:require
   [clojure.core.async :as ca]
   [clojure.test :refer [deftest is]]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [com.deercreeklabs.talk2.client :as client]
   [com.deercreeklabs.talk2.utils :as u]
   #?(:clj [kaocha.repl])
   [integration.bytes :as bytes]
   [integration.test-protocols :as tp]
   [taoensso.timbre :as log]))

;;;; IMPORTANT!!! You must start the talk2 test server for these tests to work.
;;;; $ bin/run-talk2-test-server

(defn get-url []
  "ws://localhost:8080")

;; TODO: Test malformed protocols

(defn handle-sum-numbers [{:keys [arg]}]
  (apply + arg))

(comment (kaocha.repl/run #'test-messaging))
(deftest test-messaging
  (au/test-async
   30000
   (ca/go
     (let [status-update-ch (ca/chan)
           backend-connected-ch (ca/chan)
           handle-status-update (fn [{:keys [arg]}]
                                  (ca/put! status-update-ch arg))
           client-config {:get-url (constantly "ws://localhost:8080/client")
                          :handlers {:status-update handle-status-update}
                          :protocol tp/client-gateway-protocol}
           client (client/client client-config)
           be-config {:get-url (constantly "ws://localhost:8080/backend")
                      :handlers {:sum-numbers handle-sum-numbers}
                      :protocol tp/backend-gateway-protocol
                      :on-connect (fn [info]
                                    (ca/put! backend-connected-ch true))}
           be-client (client/client be-config)]
       (try
         (let [[_ ch] (ca/alts! [backend-connected-ch (ca/timeout 5000)])
               _ (is (= backend-connected-ch ch))
               _ (is (= 10000000 (au/<? (client/<send-msg!
                                         client :count-bytes
                                         bytes/bytes-10M))))
               numbers [2 3 8 2 3]
               offset 3
               arg (u/sym-map numbers offset)
               oasn-rsp (au/<? (client/<send-msg!
                                client :offset-and-sum-numbers arg))
               expected (apply + (map #(+ offset %) numbers))
               _ (is (= expected oasn-rsp))
               _ (is (= true (au/<? (client/<send-msg!
                                     client :request-status-update nil))))
               [v ch] (ca/alts! [status-update-ch (ca/timeout 5000)])
               _ (is (= status-update-ch ch))
               _ (is (= "On time" v))
               ret (au/<? (client/<send-msg! client :throw-if-even 1))
               _ (is (= false ret))]
           (try
             (au/<? (client/<send-msg! client :throw-if-even 2))
             (is (= :should-throw :but-didnt))
             (catch #?(:clj Exception :cljs js/Error) e
               (is (= :should-throw :should-throw)))))
         (catch #?(:clj Exception :cljs js/Error) e
           (log/error (u/ex-msg-and-stacktrace e))
           (is (= :threw :but-should-not-have)))
         (finally
           (client/stop! client)
           (client/stop! be-client)))))))

(comment
 (au/<??
  (au/go
   (let [status-update-ch (ca/chan)
         backend-connected-ch (ca/chan)
         handle-status-update (fn [{:keys [arg]}]
                                (ca/put! status-update-ch arg))
         client-config {:get-url (constantly "ws://localhost:8080/client")
                        :handlers {:status-update handle-status-update}
                        :protocol tp/client-gateway-protocol}
         client (client/client client-config)
         be-config {:get-url (constantly "ws://localhost:8080/backend")
                    :handlers {:sum-numbers handle-sum-numbers}
                    :protocol tp/backend-gateway-protocol
                    :on-connect (fn [info]
                                  (ca/put! backend-connected-ch true))}
         be-client (client/client be-config)]
     (try
      (let [[_ ch] (ca/alts! [backend-connected-ch (ca/timeout 5000)])
            _ (log/info (= backend-connected-ch ch))
            _ (log/info "before")
            chans (mapv (fn [x]
                          (client/<send-msg!
                           client :count-bytes bytes/bytes-10M))
                        (range 2))
            _ (log/info (count chans))
            _ (log/info "after")
            _ (doseq [c chans]
                (log/info "got one")
                (au/<? c))])
      (catch #?(:clj Exception :cljs js/Error) e
        (log/error (u/ex-msg-and-stacktrace e))
        (log/info :threw :but-should-not-have))
      (finally
       (client/stop! client)
       (client/stop! be-client))))))
 )
