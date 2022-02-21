(ns deercreeklabs.talk2.client
  (:require
   [clojure.core.async :as ca]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.lancaster :as l]
   [deercreeklabs.talk2.common :as common]
   [deercreeklabs.talk2.schemas :as schemas]
   [deercreeklabs.talk2.utils :as u]
   [deercreeklabs.talk2.ws-client :as ws-client]
   [taoensso.timbre :as log]))

(defn check-get-url [get-url]
  (when-not (fn? get-url)
    (throw (ex-info (str "Invalid `:get-url` fn in client config. Got `"
                         get-url "`.")
                    (u/sym-map get-url)))))

(defn <connect!
  [{:keys [close-ch get-url max-wait-interval-ms on-disconnect on-connect
           send-ch *shutdown?]
    :or {max-wait-interval-ms 30000
         on-connect (constantly nil)
         on-disconnect (constantly nil)}
    :as arg}]
  (au/go
    (loop [wait-ms 2000]
      (let [open-ch (ca/chan)
            url (get-url)
            opts {:on-disconnect (fn [code]
                                   (ca/put! close-ch true)
                                   (on-disconnect (u/sym-map url code)))
                  :on-message (fn [ws data]
                                (common/process-packet-data
                                 (-> arg
                                     (assoc :data data)
                                     (assoc :send-packet!
                                            (fn [packet]
                                              (ca/put! send-ch packet))))))
                  :on-connect (fn [{:keys [protocol]}]
                                (ca/put! open-ch true)
                                (on-connect (u/sym-map protocol url)))
                  :protocols-seq ["talk2"]}
            ws (ws-client/websocket url opts)
            [_ ch] (ca/alts! [open-ch (ca/timeout wait-ms)])]
        (cond
          @*shutdown? false
          (= open-ch ch) ws
          :else (do
                  (ca/<! (ca/timeout (rand-int wait-ms)))
                  (recur (min (* 2 wait-ms)
                              max-wait-interval-ms))))))))

(defn start-connection-loop
  [{:keys [*conn-info *shutdown?] :as arg}]
  (ca/go
    (loop []
      (try
        (let [close-ch (ca/chan)
              ws (au/<? (<connect! (assoc arg :close-ch close-ch)))]
          (reset! *conn-info (assoc common/empty-conn-info :ws ws))
          (loop []
            (when-not @*shutdown?
              (let [[_ ch] (ca/alts! [close-ch (ca/timeout 1000)])]
                (if (= close-ch ch)
                  (reset! *conn-info nil)
                  (recur))))))
        (catch #?(:clj Exception :cljs js/Error) e
          (log/error (str "Error in connect loop:\n"
                          (u/ex-msg-and-stacktrace e)))))
      (ca/timeout (rand-int 2000))
      (when-not @*shutdown?
        (recur)))))

(defn start-send-loop [{:keys [send-ch *conn-info *shutdown?]}]
  (ca/go
    (loop []
      (try
        (let [[packet ch] (ca/alts! [send-ch (ca/timeout 1000)])]
          (when (= send-ch ch)
            (loop []
              (let [{:keys [ws]} @*conn-info
                    data (ba/concat-byte-arrays
                          [(ba/byte-array [common/packet-magic-number])
                           (l/serialize schemas/packet-schema packet)])]
                (cond
                  @*shutdown? nil
                  ws (ws-client/send! ws data)
                  :else (do
                          (ca/<! (ca/timeout 100))
                          (recur)))))))
        (catch #?(:clj Exception :cljs js/Error) e
          (log/error (str "Error in send loop:\n"
                          (u/ex-msg-and-stacktrace e)))))
      (when-not @*shutdown?
        (recur)))))

(defn start-gc-loop [{:keys [*rpc-id->info *shutdown?]}]
  (ca/go
    (loop []
      (try
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
          (swap! *rpc-id->info #(apply dissoc % expired-rpc-ids)))
        (catch #?(:clj Exception :cljs js/Error) e
          (log/error (str "Error in gc loop:\n"
                          (u/ex-msg-and-stacktrace e)))))
      (when-not @*shutdown?
        (recur)))))

(defn client [config]
  (let [{:keys [get-url handlers protocol]} config
        _ (check-get-url get-url)
        _ (common/check-handlers (u/sym-map protocol handlers))
        _ (common/check-protocol protocol)
        send-ch (ca/chan 1000)
        *conn-info (atom nil)
        *next-rpc-id (atom 0)
        *rpc-id->info (atom {})
        *shutdown? (atom false)
        {:keys [msg-type-name->msg-type-id
                msg-type-id->msg-type-name]} (common/make-msg-type-maps
                                              protocol)
        send-packet! #(ca/put! send-ch %)
        client (u/sym-map msg-type-name->msg-type-id
                          msg-type-id->msg-type-name
                          protocol
                          send-ch
                          send-packet!
                          *conn-info
                          *next-rpc-id
                          *rpc-id->info
                          *shutdown?)]
    (start-connection-loop (merge config client))
    (start-send-loop client)
    (start-gc-loop client)
    client))

(defn shutdown! [client]
  (when-let [ws (some-> client :*conn-info deref :ws)]
    (ws-client/close! ws))
  (reset! (:*shutdown? client) true))

(defn <send-msg!
  ([client msg-type-name arg]
   (common/<send-msg! client msg-type-name arg nil))
  ([client msg-type-name arg timeout-ms]
   (common/<send-msg! client msg-type-name arg timeout-ms)))
