(ns integration.ws-echo-server
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.talk2.ws-server :as ws-server]
   [deercreeklabs.talk2.utils :as u]
   [taoensso.timbre :as log]))

(defn get-tls-configs []
  (let [certificate-str (some-> "server.crt" io/resource slurp)
        private-key-str (some-> "server.key" io/resource slurp)]
    (when-not certificate-str
      (throw (ex-info "Could not open `server.crt`." {})))
    (when-not private-key-str
      (throw (ex-info "Could not open `server.key`." {})))
    (u/sym-map certificate-str private-key-str)))

(defn -main [port* tls?*]
  (let [port (u/str->int port*)
        tls? (#{"true" "1"} (str/lower-case tls?*))
        on-connect (fn [conn]
                     (log/info "Conn opened")
                     (ws-server/set-on-message!
                      conn (fn [data]
                             (log/info (str "Got " (count data)
                                            " bytes:\n"  data))
                             (ws-server/send! conn data)))
                     (ws-server/set-on-pong!
                      conn (fn [data]
                             (log/info (str "Got pong frame."
                                            (when-not (empty? data)
                                              (str " Payload: "
                                                   (ba/byte-array->hex-str
                                                    data))))))))
        on-disconnect #(log/info (str "Conn closed: " %))
        prioritized-protocols-seq ["talk2-2" "talk2-1"]
        config (cond-> (u/sym-map on-disconnect
                                  on-connect
                                  port
                                  prioritized-protocols-seq)
                 tls? (merge (get-tls-configs)))]
    (ws-server/ws-server config)))
