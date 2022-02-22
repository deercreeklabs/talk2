(ns integration.test-server
  (:require
   [clojure.core.async :as ca]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [deercreeklabs.async-utils :as au]
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
      (throw (ex-info "Failed to load certificate file." {})))
    (when-not private-key-str
      (throw (ex-info "Failed to load private key file" {})))
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

(defn -main [port-str tls?-str]
  (let [tls? (#{"true" "1"} (str/lower-case tls?-str))
        *server (atom nil)
        *backend-conn-id (atom nil)
        client-ep-info {:handlers {:offset-and-sum-numbers
                                   #(<handle-oasn
                                     (-> %
                                         (assoc :server @*server)
                                         (assoc :backend-conn-id
                                                @*backend-conn-id)))

                                   :request-status-update
                                   #(handle-request-status-update
                                     (assoc % :server @*server))}
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

                        :protocol tp/client-gateway-protocol}
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
                    :protocol tp/backend-gateway-protocol}

        config (cond-> {:path->endpoint-info {"/client" client-ep-info
                                              "/backend" be-ep-info}
                        :port (u/str->int port-str)}
                 tls? (merge (get-tls-configs)))
        server (server/server config)]
    (reset! *server server)))
