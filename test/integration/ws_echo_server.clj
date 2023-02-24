(ns integration.ws-echo-server
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [deercreeklabs.baracus :as ba]
   [com.deercreeklabs.talk2.ws-server :as ws-server]
   [com.deercreeklabs.talk2.utils :as u]
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

(defn -main [port* tls?*]
  (let [port (u/str->int port*)
        tls? (#{"true" "1"} (str/lower-case tls?*))
        on-connect (fn [conn]
                     (log/info (str "Conn opened: "
                                    (select-keys conn [:conn-id])))
                     (ws-server/set-on-message!
                      conn (fn [{:keys [data]}]
                             (log/info (str "Got " (count data)
                                            " bytes:\n"  data))
                             (ws-server/send! conn data)))
                     (ws-server/set-on-pong!
                      conn (fn [{:keys [data]}]
                             (log/info (str "Got pong frame."
                                            (when-not (empty? data)
                                              (str " Payload: "
                                                   (ba/byte-array->hex-str
                                                    data))))))))
        on-disconnect (fn [conn]
                        (log/info (str "Conn closed: "
                                       (select-keys conn [:conn-id]))))
        prioritized-protocols-seq ["talk2-2" "talk2-1"]
        config (cond-> (u/sym-map on-disconnect
                                  on-connect
                                  port
                                  prioritized-protocols-seq)
                 tls? (merge (get-tls-configs)))]
    (ws-server/ws-server config)))
