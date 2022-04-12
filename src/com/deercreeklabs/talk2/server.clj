(ns com.deercreeklabs.talk2.server
  (:require
   [clojure.string :as str]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.lancaster :as l]
   [com.deercreeklabs.talk2.common :as common]
   [com.deercreeklabs.talk2.schemas :as schemas]
   [com.deercreeklabs.talk2.ws-server :as ws-server]
   [com.deercreeklabs.talk2.utils :as u]
   [lambdaisland.uri :as uri]
   [taoensso.timbre :as log]))

(defn send-packet! [conn packet]
  (let [ba (ba/concat-byte-arrays [(ba/byte-array [common/packet-magic-number])
                                   (l/serialize schemas/packet-schema packet)])]
    (ws-server/send! conn ba)))

(defn make-ep [{:keys [handlers on-connect on-disconnect protocol]}]
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
               *rpc-id->info)))

(defn make-on-connect [{:keys [*path->ep *conn-id->info *server]}]
  (fn [{:keys [close! conn-id path] :as conn}]
    (let [*conn-info (atom common/empty-conn-info)
          unadorned-path (-> path uri/uri :path)
          ep (@*path->ep unadorned-path)]
      (if-not ep
        (do
          ;; TODO: Return a 404
          (log/error (str "No matching endpoint found for path `" unadorned-path
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
                                            (common/process-packet-data!
                                             (assoc sender :data data
                                                    :server @*server))))))
      (when-let [f (:on-connect ep)]
        (f conn)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Public API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn server
  [{:keys [certificate-str private-key-str port]
    :as config}]
  (let [*stop? (atom false)
        *conn-id->info (atom {})
        *path->ep (atom {})
        *server (atom nil)
        on-connect (make-on-connect
                    (u/sym-map *path->ep *conn-id->info *server))
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
        server (u/sym-map *conn-id->info *path->ep *stop? ws-server)]
    (reset! *server server)
    server))

(defn stop! [server]
  (reset! (:*stop? server) true)
  (ws-server/stop! (:ws-server server)))

(defn add-endpoint!
  [{:keys [handlers on-connect on-disconnect path protocol server]
    :as ep-info}]
  (when-not (string? path)
    (throw (ex-info (str "`:path` must be a string. Got `"
                         (or path "nil") "`.")
                    (u/sym-map path))))
  (when-not (str/starts-with? path "/")
    (throw (ex-info (str "`:path` must start with `/` (a slash). Got `"
                         (or path "nil") "`.")
                    (u/sym-map path))))
  (let [{:keys [*path->ep]} server
        unadorned-path (-> path uri/uri :path)]
    (when (@*path->ep unadorned-path)
      (throw (ex-info (str "The path `" unadorned-path "` is already "
                           "associated with an endpoint.")
                      (u/sym-map unadorned-path path))))
    (swap! *path->ep assoc unadorned-path (make-ep ep-info))))

(defn remove-endpoint!
  [{:keys [path server]}]
  (let [{:keys [*path->ep]} server
        unadorned-path (-> path uri/uri :path)]
    (when-not (@*path->ep path)
      (throw (ex-info (str "There is no endpoint at path `"
                           (or unadorned-path "nil") "`.")
                      (u/sym-map unadorned-path path))))
    (swap! *path->ep dissoc unadorned-path)))

(defn <send-msg! [{:keys [arg conn-id msg-type-name server timeout-ms]}]
  (when-not server
    (throw (ex-info "Missing `:server` value in `<send-msg!` arg map")))
  (when-not msg-type-name
    (throw (ex-info "Missing `:msg-type-name` value in `<send-msg!` arg map")))
  (let [{:keys [*conn-id->info]} server
        {:keys [sender]} (get @*conn-id->info conn-id)]
    (if sender
      (common/<send-msg! sender msg-type-name arg timeout-ms)
      (throw (ex-info (str "No connection found for conn-id `"
                           (or conn-id "nil") "`.")
                      (u/sym-map conn-id))))))

(defn close-connection! [{:keys [conn-id server]}]
  (when-not server
    (throw (ex-info "Missing `:server` value in `close-connection!` arg map")))
  (let [{:keys [*conn-id->info]} server
        {:keys [closer]} (get @*conn-id->info conn-id)]
    (if closer
      (closer)
      (throw (ex-info (str "No connection found for conn-id `"
                           (or conn-id "nil") "`.")
                      (u/sym-map conn-id))))))
