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

(defn make-ep [{:keys [handlers on-connect on-disconnect protocol *shutdown?]}]
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
               *shutdown?)))

(defn make-path->ep [{:keys [path->endpoint-info *shutdown?]}]
  (reduce-kv
   (fn [acc path ep-info]
     (assoc acc path (make-ep (assoc ep-info :*shutdown? *shutdown?))))
   {}
   path->endpoint-info))

(defn make-on-connect [{:keys [path->ep *conn-id->sender *server]}]
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
          (swap! *conn-id->sender assoc conn-id sender)
          (ws-server/set-on-message! conn (fn [data]
                                            (common/process-packet-data
                                             (assoc sender :data data
                                                    :server @*server))))))
      (when-let [f (:on-connect ep)]
        (f conn)))))

(defn server
  [{:keys [certificate-str private-key-str path->endpoint-info port]
    :as config}]
  (let [*shutdown? (atom false)
        *conn-id->sender (atom {})
        path->ep (make-path->ep (u/sym-map path->endpoint-info *shutdown?))
        *server (atom nil)
        on-connect (make-on-connect
                    (u/sym-map path->ep *conn-id->sender *server))
        on-disconnect (fn [{:keys [conn-id] :as conn}]
                        (let [sender (@*conn-id->sender conn-id)]
                          (swap! *conn-id->sender dissoc conn-id)
                          (when-let [f (:on-disconnect sender)]
                            (f conn))))
        prioritized-protocols-seq ["talk2"]
        ws-server (ws-server/ws-server (u/sym-map certificate-str
                                                  on-disconnect
                                                  on-connect
                                                  port
                                                  prioritized-protocols-seq
                                                  private-key-str))
        server (u/sym-map ws-server *conn-id->sender *shutdown?)]
    (reset! *server server)
    server))

(defn shutdown! [server]
  (reset! (:*shutdown? server) true)
  (ws-server/stop-server! (:ws-server server)))

(defn <send-msg! [{:keys [arg conn-id msg-type-name server timeout-ms]}]
  (let [{:keys [*conn-id->sender]} server
        sender (get @*conn-id->sender conn-id)]
    (if sender
      (common/<send-msg! sender msg-type-name arg timeout-ms)
      (throw (ex-info (str "No connection found for conn-id `" conn-id "`.")
                      (u/sym-map conn-id))))))
