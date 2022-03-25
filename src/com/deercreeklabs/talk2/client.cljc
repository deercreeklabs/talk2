(ns com.deercreeklabs.talk2.client
  (:require
   [clojure.core.async :as ca]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.lancaster :as l]
   [com.deercreeklabs.talk2.common :as common]
   [com.deercreeklabs.talk2.schemas :as schemas]
   [com.deercreeklabs.talk2.utils :as u]
   [com.deercreeklabs.talk2.ws-client :as ws-client]
   [taoensso.timbre :as log]))

(defn check-get-url [get-url]
  (when-not (fn? get-url)
    (throw (ex-info (str "Invalid `:get-url` fn in client config. Got `"
                         get-url "`.")
                    (u/sym-map get-url)))))

(defn start-send-loop!
  [{:keys [*stop? *ws-connected? send-ch stop-sending-ch ws]}]
  (ca/go-loop []
    (try
      (let [[data ch] (au/alts? [send-ch stop-sending-ch])]
        (when (and (= send-ch ch) @*ws-connected? (not @*stop?))
          (ws-client/send! ws data)))
      (catch #?(:clj Exception :cljs js/Error) e
        (log/error (str "Error in send loop:\n"
                        (u/ex-msg-and-stacktrace e)))
        (au/<? (ca/timeout 1000))))
    (when (and @*ws-connected? (not @*stop?))
      (recur))))

(defn connect!
  [{:keys [*conn-info disconnect-notify-ch get-url on-connect on-disconnect]
    :as arg}]
  (let [url (get-url)
        *ws-connected? (atom false)
        stop-sending-ch (ca/chan)
        opts {:on-disconnect (fn [{:keys [code]}]
                               (reset! *conn-info nil)
                               (reset! *ws-connected? false)
                               (ca/put! stop-sending-ch true)
                               (ca/put! disconnect-notify-ch true)
                               (when on-disconnect
                                 (on-disconnect (u/sym-map url code))))
              :on-message (fn [{:keys [data]}]
                            (common/process-packet-data!
                             (assoc arg :data data)))
              :on-connect (fn [{:keys [protocol ws]}]
                            (reset! *ws-connected? true)
                            (start-send-loop!
                             (-> arg
                                 (assoc :*ws-connected? *ws-connected?)
                                 (assoc :stop-sending-ch stop-sending-ch)
                                 (assoc :ws ws)))
                            (when on-connect
                              (on-connect (u/sym-map protocol url))))
              :protocols-seq ["talk2"]}
        ws (ws-client/websocket url opts)]
    (reset! *conn-info (assoc common/empty-conn-info :ws ws))
    ws))

(defn start-connect-loop!
  [{:keys [*stop? disconnect-notify-ch] :as arg}]
  (ca/go-loop []
    (try
      (connect! arg)
      (au/<? disconnect-notify-ch)
      (catch #?(:clj Exception :cljs js/Error) e
        (log/error (str "Error in connect loop:\n"
                        (u/ex-msg-and-stacktrace e)))
        (au/<? (ca/timeout 1000))))
    (when-not @*stop?
      (recur))))

(defn gc-rpcs! [{:keys [*rpc-id->info]}]
  (let [id->info @*rpc-id->info
        now (u/current-time-ms)
        expired-rpc-ids (reduce-kv
                         (fn [acc rpc-id {:keys [expiry-time-ms]}]
                           (if (> now expiry-time-ms)
                             (conj acc rpc-id)
                             acc))
                         []
                         id->info)]
    (doseq [rpc-id expired-rpc-ids]
      (let [{:keys [cb timeout-ms]
             :or {cb (constantly nil)}} (id->info rpc-id)]
        (cb (ex-info
             (str "RPC timed out after " timeout-ms " milliseconds.")
             (u/sym-map rpc-id timeout-ms)))))
    (swap! *rpc-id->info #(apply dissoc % expired-rpc-ids))))

(defn send-packet!* [send-ch *conn-info packet]
  (let [data (ba/concat-byte-arrays
              [(ba/byte-array [common/packet-magic-number])
               (l/serialize schemas/packet-schema packet)])]
    (ca/put! send-ch data)))

(defn client [config]
  (let [{:keys [get-url handlers protocol]} config
        _ (check-get-url get-url)
        _ (common/check-handlers (u/sym-map protocol handlers))
        _ (common/check-protocol protocol)
        send-ch (ca/chan 1000)
        *conn-info (atom nil)
        *next-rpc-id (atom 0)
        *rpc-id->info (atom {})
        *stop? (atom false)
        send-packet! (partial send-packet!* send-ch *conn-info)
        {:keys [msg-type-name->msg-type-id
                msg-type-id->msg-type-name]} (common/make-msg-type-maps
                                              protocol)
        disconnect-notify-ch (ca/chan)
        client (u/sym-map disconnect-notify-ch
                          msg-type-name->msg-type-id
                          msg-type-id->msg-type-name
                          protocol
                          send-ch
                          send-packet!
                          *conn-info
                          *next-rpc-id
                          *rpc-id->info
                          *stop?)]
    (start-connect-loop! (merge config client))
    client))

(defn stop! [client]
  (let [{:keys [*conn-info *stop? disconnect-notify-ch send-ch]} client
        {:keys [ws]} @*conn-info]
    (reset! *stop? true)
    (ca/close! disconnect-notify-ch)
    (ca/close! send-ch)
    (when ws
      (ws-client/close! ws))))

(defn <send-msg!
  ([client msg-type-name arg]
   (common/<send-msg! client msg-type-name arg nil))
  ([client msg-type-name arg timeout-ms]
   (gc-rpcs! client)
   (common/<send-msg! client msg-type-name arg timeout-ms)))
