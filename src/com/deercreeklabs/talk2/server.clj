(ns com.deercreeklabs.talk2.server
  (:require
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.lancaster :as l]
   [com.deercreeklabs.talk2.common :as common]
   [com.deercreeklabs.talk2.schemas :as schemas]
   [com.deercreeklabs.talk2.ws-server :as ws-server]
   [com.deercreeklabs.talk2.utils :as u]
   [taoensso.timbre :as log]))

(defn send-packet! [conn packet]
  (let [ba (ba/concat-byte-arrays [(ba/byte-array [common/packet-magic-number])
                                   (l/serialize schemas/packet-schema packet)])]
    (ws-server/send! conn ba)))

(defn make-ep [{:keys [handlers on-connect on-disconnect protocol *stop?]}]
  (common/check-protocol protocol)
  (let [*next-rpc-id (atom 0)
        *rpc-id->info (atom {})
        {:keys [msg-type-name->msg-type-id
                msg-type-id->msg-type-name]} (common/make-msg-type-maps
                                              protocol)]
    (u/sym-map msg-type-name->msg-type-id
               msg-type-id->msg-type-name
               handlers
               on-connect
               on-disconnect
               protocol
               *next-rpc-id
               *rpc-id->info
               *stop?)))

(defn make-path->ep [{:keys [path->endpoint-info *stop?]}]
  (reduce-kv
   (fn [acc path ep-info]
     (assoc acc path (make-ep (assoc ep-info :*stop? *stop?))))
   {}
   path->endpoint-info))

(defn make-on-connect [{:keys [path->ep *conn-id->info *server]}]
  (fn [{:keys [close! conn-id path] :as conn}]
    (let [*conn-info (atom common/empty-conn-info)
          ep (path->ep path)]
      (if-not ep
        (do
          (log/error (str "No matching endpoint found for path `" path
                          "`. Closing connection."))
          (close!))
        (let [sender (-> ep
                         (assoc :conn-id conn-id)
                         (assoc :*conn-info *conn-info)
                         (assoc :send-packet!
                                (partial send-packet! conn)))]
          (swap! *conn-id->info assoc conn-id {:closer close!
                                               :sender sender})
          (ws-server/set-on-message! conn (fn [{:keys [data]}]
                                            (common/process-packet-data
                                             (assoc sender :data data
                                                    :server @*server))))))
      (when-let [f (:on-connect ep)]
        (f conn)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Public API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn server
  [{:keys [certificate-str private-key-str path->endpoint-info port]
    :as config}]
  (let [*stop? (atom false)
        *conn-id->info (atom {})
        path->ep (make-path->ep (u/sym-map path->endpoint-info *stop?))
        *server (atom nil)
        on-connect (make-on-connect
                    (u/sym-map path->ep *conn-id->info *server))
        on-disconnect (fn [{:keys [conn-id] :as conn}]
                        (let [{:keys [sender]} (get @*conn-id->info conn-id)]
                          (swap! *conn-id->info dissoc conn-id)
                          (when-let [f (:on-disconnect sender)]
                            (f conn))))
        prioritized-protocols-seq ["talk2"]
        ws-server (ws-server/ws-server (u/sym-map certificate-str
                                                  on-disconnect
                                                  on-connect
                                                  port
                                                  prioritized-protocols-seq
                                                  private-key-str))
        server (u/sym-map ws-server *conn-id->info *stop?)]
    (reset! *server server)
    server))

(defn stop! [server]
  (reset! (:*stop? server) true)
  (ws-server/stop! (:ws-server server)))

(defn <send-msg! [{:keys [arg conn-id msg-type-name server timeout-ms]}]
  (let [{:keys [*conn-id->info]} server
        {:keys [sender]} (get @*conn-id->info conn-id)]
    (if sender
      (common/<send-msg! sender msg-type-name arg timeout-ms)
      (throw (ex-info (str "No connection found for conn-id `" conn-id "`.")
                      (u/sym-map conn-id))))))

(defn close-connection! [{:keys [conn-id server]}]
  (let [{:keys [*conn-id->info]} server
        {:keys [closer]} (get @*conn-id->info conn-id)]
    (when closer
      (closer))))
