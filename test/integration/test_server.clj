(ns integration.test-server
  (:require
   [clojure.string :as str]
   [com.deercreeklabs.talk2.server :as server]
   [com.deercreeklabs.talk2.utils :as u]
   [integration.test-protocols :as tp]
   [taoensso.timbre :as log]))

(defn get-tls-configs []
  (let [certificate-str (some-> (System/getenv "TALK2_SERVER_CERTIFICATE_FILE")
                                (slurp))
        private-key-str (some-> (System/getenv "TALK2_SERVER_PRIVATE_KEY_FILE")
                                (slurp))]
    (when-not certificate-str
      (throw (ex-info (str "No certificate file specified in env var "
                           "`TALK2_SERVER_CERTIFICATE_FILE`")
                      {})))
    (when-not private-key-str
      (throw (ex-info (str "No private key file specified in env var "
                           "`TALK2_SERVER_PRIVATE_KEY_FILE`")
                      {})))
    (u/sym-map certificate-str private-key-str)))

(defn <handle-oasn [{:keys [arg backend-conn-id server]}]
  (let [{:keys [numbers offset]} arg
        offset-numbers (map #(+ offset %) numbers)]
    (server/<send-msg! {:arg offset-numbers
                        :conn-id backend-conn-id
                        :msg-type-name :sum-numbers
                        :server server})))

(defn handle-request-status-update [{:keys [conn-id server]}]
  (server/<send-msg! {:arg "On time"
                      :conn-id conn-id
                      :msg-type-name :status-update
                      :server server}))

(defn handle-count-bytes [{:keys [arg]}]
  (count arg))

(defn -main [port-str tls?-str]
  (let [tls? (#{"true" "1"} (str/lower-case tls?-str))
        *backend-conn-id (atom nil)
        config (cond-> {:port (u/str->int port-str)}
                 tls? (merge (get-tls-configs)))
        server (server/server config)
        client-handlers {:count-bytes
                         handle-count-bytes

                         :offset-and-sum-numbers
                         #(<handle-oasn
                           (-> %
                               (assoc :server server)
                               (assoc :backend-conn-id
                                      @*backend-conn-id)))

                         :request-status-update
                         #(handle-request-status-update
                           (assoc % :server server))

                         :throw-if-even
                         #(if (even? (:arg %))
                            (throw (ex-info "Even!" {}))
                            false)}
        client-ep-info {:handlers client-handlers
                        :on-connect (fn [conn]
                                      (log/info
                                       (str "Client connection opened:\n"
                                            (u/pprint-str
                                             (select-keys conn
                                                          [:conn-id :path :protocol
                                                           :remote-address])))))
                        :on-disconnect (fn [conn]
                                         (log/info
                                          (str "Client connection closed:\n"
                                               (u/pprint-str
                                                (select-keys conn [:conn-id])))))
                        :path "/client"
                        :protocol tp/client-gateway-protocol
                        :server server}
        be-ep-info {:on-connect (fn [{:keys [conn-id] :as conn}]
                                  (reset! *backend-conn-id conn-id)
                                  (log/info
                                   (str "Backend connection opened:\n"
                                        (u/pprint-str
                                         (select-keys conn
                                                      [:conn-id :path :protocol
                                                       :remote-address])))))
                    :on-disconnect (fn [conn]
                                     (log/info
                                      (str "Backend connection closed:\n"
                                           (u/pprint-str
                                            (select-keys conn [:conn-id])))))
                    :path "/backend"
                    :protocol tp/backend-gateway-protocol
                    :server server}]
    (server/add-endpoint! client-ep-info)
    (server/add-endpoint! be-ep-info)))

(comment
 (defonce *ts (atom nil))
 (defn reset []
   (when @*ts
     (server/stop! @*ts))
   (reset! *ts (-main "8080" "false"))
   :ready)
 (reset))
