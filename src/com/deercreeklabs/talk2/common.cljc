(ns com.deercreeklabs.talk2.common
  (:require
   [clojure.core.async :as ca]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.lancaster :as l]
   [com.deercreeklabs.talk2.schemas :as schemas]
   [com.deercreeklabs.talk2.utils :as u]
   [taoensso.timbre :as log]))

(def packet-magic-number 42)
(def empty-conn-info {:my-msg-type-id->info-sent? {}
                      :peer-msg-type-id->info {}})

(defn check-protocol [protocol]
  (when-not (associative? protocol)
    (throw (ex-info (str "Invalid `:protocol`. "
                         "Must be associative. Got `" (or protocol "nil") "`.")
                    (u/sym-map protocol))))
  (doseq [[msg-type-name msg-type-info] protocol]
    (when-not (keyword? msg-type-name)
      (throw (ex-info (str "Invalid msg type name in protocol. Must be a "
                           "keyword. Got `" (or msg-type-name "nil") "`.")
                      (u/sym-map msg-type-name msg-type-info))))
    (when-not (associative? msg-type-info)
      (throw (ex-info
              (str "Invalid msg type info for msg type`" msg-type-name "` in "
                   "protocol. Must be associative. Got `"
                   (or msg-type-info "nil") "`.")
              (u/sym-map msg-type-name msg-type-info))))
    (let [{:keys [arg-schema ret-schema]} msg-type-info]
      (when-not (l/schema? arg-schema)
        (throw (ex-info
                (str "Bad arg schema for msg type `" msg-type-name "` in "
                     "protocol. Must be a valid Lancaster schema. Got`"
                     (or arg-schema "nil") "`.")
                (u/sym-map msg-type-name msg-type-info arg-schema))))
      (when (and ret-schema
                 (not (l/schema? ret-schema)))
        (throw (ex-info
                (str "Bad ret schema for msg type `" msg-type-name "` in "
                     "protocol. Must be a valid Lancaster schema. Got`"
                     (or ret-schema "nil") "`.")
                (u/sym-map msg-type-name msg-type-info ret-schema)))))))

(defn check-handlers [{:keys [protocol handlers]}]
  (doseq [[msg-type-name handler] handlers]
    (when-not (keyword? msg-type-name)
      (throw (ex-info (str "Invalid msg type name in handlers map. Must be a "
                           "keyword. Got `" (or msg-type-name "nil") "`.")
                      (u/sym-map msg-type-name))))
    (when-not (ifn? handler)
      (throw (ex-info (str "Invalid handler for msg type `" msg-type-name "`. "
                           "Handler must be a function. Got `"
                           (or handler "nil") "`.")
                      (u/sym-map msg-type-name handler))))
    (when-not (protocol msg-type-name)
      (throw (ex-info (str "Invalid msg type name in handlers map. Must be a "
                           "msg type name that is listed in the protocol. "
                           "Got `" (or msg-type-name "nil")
                           "`. Valid protocol msg type "
                           "names are: " (keys protocol))
                      {:msg-type-name msg-type-name
                       :valid-msg-type-names (keys protocol)})))))

(defmulti <process-packet! (fn [{:keys [packet]}]
                             (:packet-type packet)))

(defn xf-msg-type-info [{:keys [arg-json-schema ret-json-schema] :as info}]
  (cond-> info
    arg-json-schema (assoc :arg-schema (l/json->schema arg-json-schema))
    ret-json-schema (assoc :ret-schema (l/json->schema ret-json-schema))
    true (dissoc :arg-json-schema)
    true (dissoc :ret-json-schema)))

(defn <get-msg-type-info
  [{:keys [packet send-packet! *conn-info] :as arg}]
  (au/go
    (loop [info-requested? false
           attempts-remaining (* 10 60 60 5)] ; 5 mins
      (let [{:keys [msg-type-id msg-type-info]} packet
            _ (when msg-type-info
                (swap! *conn-info assoc-in [:peer-msg-type-id->info msg-type-id]
                       (xf-msg-type-info msg-type-info)))
            stored-mti (get-in @*conn-info
                               [:peer-msg-type-id->info msg-type-id])]
        (cond
          (zero? attempts-remaining)
          (throw (ex-info (str "Could not get msg type info for msg-type-id `"
                               msg-type-id "`.")
                          (u/sym-map msg-type-id)))

          (and (not stored-mti) (not info-requested?))
          (do
            (send-packet! {:msg-type-id msg-type-id
                           :packet-type :msg-type-info-req})
            (recur true (dec attempts-remaining)))

          (not stored-mti)
          (do
            (ca/<! (ca/timeout 100))
            (recur info-requested? (dec attempts-remaining)))

          stored-mti
          stored-mti)))))

(defmethod <process-packet! :msg
  [{:keys [conn-id handlers msg-type-name reader-schemas server writer-mti]
    :as arg}]
  (au/go
    (let [msg-arg (l/deserialize (:arg-schema reader-schemas)
                                 (:arg-schema writer-mti)
                                 (-> arg :packet :bytes))
          handler (get handlers msg-type-name)
          _ (when-not handler
              (throw (ex-info (str "No handler found for msg type name `"
                                   msg-type-name "`.")
                              (u/sym-map msg-type-name))))
          ret (handler {:arg msg-arg
                        :conn-id conn-id
                        :server server})]
      ;; We retun the handler's ret in case it's an exception
      (if (au/channel? ret)
        (au/<? ret)
        ret))))

(defmethod <process-packet! :msg-type-info-rsp
  [{:keys [packet *conn-info] :as arg}]
  (au/go
    (let [{:keys [msg-type-id msg-type-info]} packet]
      (when msg-type-info
        (swap! *conn-info assoc-in [:peer-msg-type-id->info msg-type-id]
               (xf-msg-type-info msg-type-info))))))

(defmethod <process-packet! :msg-type-info-req
  [{:keys [msg-type-id->msg-type-name protocol send-packet! *conn-info]
    :as arg}]
  (au/go
    (let [my-msg-type-id (-> arg :packet :msg-type-id)
          my-msg-type-name (msg-type-id->msg-type-name my-msg-type-id)
          my-schemas (protocol my-msg-type-name)
          info {:arg-json-schema (some-> my-schemas :arg-schema l/json)
                :msg-type-name my-msg-type-name
                :ret-json-schema (some-> my-schemas :ret-schema l/json)}
          packet {:msg-type-id my-msg-type-id
                  :msg-type-info info
                  :packet-type :msg-type-info-rsp}]
      (swap! *conn-info
             #(assoc-in % [:my-msg-type-id->info-sent? my-msg-type-id] true))
      (send-packet! packet))))

(defmethod <process-packet! :rpc-req
  [{:keys [conn-id handlers msg-type-id msg-type-name reader-schemas
           send-packet! server writer-mti *conn-info]
    :as arg}]
  (au/go
    (let [rpc-arg (l/deserialize (:arg-schema reader-schemas)
                                 (:arg-schema writer-mti)
                                 (-> arg :packet :bytes))
          handler (get handlers msg-type-name)
          _ (when-not handler
              (throw (ex-info (str "No handler found for msg type name `"
                                   msg-type-name "`.")
                              (u/sym-map msg-type-name))))
          raw-ret (handler {:arg rpc-arg
                            :conn-id conn-id
                            :server server})
          ret (if (au/channel? raw-ret)
                (au/<? raw-ret)
                raw-ret)
          info-sent? (get-in @*conn-info
                             [:my-msg-type-id->info-sent? msg-type-id])
          info {:arg-json-schema (some-> reader-schemas :arg-schema l/json)
                :msg-type-name msg-type-name
                :ret-json-schema (some-> reader-schemas :ret-schema l/json)}
          packet {:bytes (l/serialize (:ret-schema reader-schemas) ret)
                  :msg-type-id msg-type-id
                  :msg-type-info (when-not info-sent? info)
                  :packet-type :rpc-rsp
                  :rpc-id (-> arg :packet :rpc-id)}]
      (when-not info-sent?
        (swap! *conn-info
               #(assoc-in % [:my-msg-type-id->info-sent? msg-type-id] true)))
      (send-packet! packet))))

(defmethod <process-packet! :rpc-rsp
  [{:keys [packet reader-schemas writer-mti *rpc-id->info] :as arg}]
  (au/go
    (let [{:keys [bytes rpc-id]} packet
          ret (l/deserialize (:ret-schema reader-schemas)
                             (:ret-schema writer-mti)
                             bytes)
          {:keys [cb]} (@*rpc-id->info rpc-id)]
      (swap! *rpc-id->info dissoc rpc-id)
      (if cb
        (cb ret)
        (log/error (str "No callback found for rpc-id `" rpc-id "`."))))))

(defn process-packet-data!
  [{:keys [data msg-type-name->msg-type-id protocol] :as arg}]
  (ca/go
    (try
      (cond
        (string? data)
        (log/error (str "Got string data on websocket; expected byte array. "
                        "Ignoring " (count data) " bytes."))

        (not (= packet-magic-number (aget ^bytes data 0)))
        (log/error (str "Got incorrect magic number on packet. Ignoring "
                        (count data) " bytes."))

        :else
        (let [packet (l/deserialize-same schemas/packet-schema
                                         (ba/slice-byte-array data 1))
              {:keys [packet-type]} packet]
          (if (#{:msg-type-info-req :msg-type-info-rsp} packet-type)
            (au/<? (<process-packet! (assoc arg :packet packet)))
            (let [writer-mti (au/<? (<get-msg-type-info
                                     (assoc arg :packet packet)))
                  {:keys [msg-type-name]} writer-mti
                  reader-schemas (protocol msg-type-name)
                  msg-type-id (msg-type-name->msg-type-id msg-type-name)]
              (when-not reader-schemas
                (throw (ex-info (str "No msg type name `"
                                     (or msg-type-name "nil")
                                     "` found in reader protocol.")
                                {:msg-type-name msg-type-name
                                 :protocol-keys (keys protocol)})))
              (au/<? (<process-packet!
                      (-> arg
                          (assoc :msg-type-id msg-type-id)
                          (assoc :msg-type-name msg-type-name)
                          (assoc :packet packet)
                          (assoc :reader-schemas reader-schemas)
                          (assoc :writer-mti writer-mti))))))))
      (catch #?(:clj Exception :cljs js/Error) e
        (log/error (str "Error processing packet:\n"
                        (u/ex-msg-and-stacktrace e)))))))

(defn make-msg-type-maps [protocol]
  (let [protocol-seq (seq protocol)]
    (reduce (fn [acc msg-type-id]
              (let [[msg-type-name _] (nth protocol-seq msg-type-id)]
                (-> acc
                    (assoc-in [:msg-type-name->msg-type-id msg-type-name]
                              msg-type-id)
                    (assoc-in [:msg-type-id->msg-type-name msg-type-id]
                              msg-type-name))))
            {:msg-type-name->msg-type-id {}
             :msg-type-id->msg-type-name {}}
            (range (count protocol)))))

(defn <send-msg! [sender msg-type-name arg timeout-ms*]
  (let [{:keys [msg-type-name->msg-type-id protocol send-packet!
                *conn-info *next-rpc-id *rpc-id->info]} sender
        info (protocol msg-type-name)
        _ (when-not info
            (throw (ex-info (str "No msg type named `" (or msg-type-name "nil")
                                 "` found in protocol.")
                            {:msg-type-name msg-type-name
                             :protocol-keys (keys protocol)})))
        {:keys [arg-schema ret-schema]} info
        msg-type-id (msg-type-name->msg-type-id msg-type-name)
        bytes (l/serialize arg-schema arg)
        info-sent? (get-in @*conn-info [:msg-type-id->info msg-type-id])
        msg-type-info (when-not info-sent?
                        {:arg-json-schema (l/json arg-schema)
                         :msg-type-name msg-type-name
                         :ret-json-schema (when ret-schema
                                            (l/json ret-schema))})
        rpc-id (when ret-schema
                 (swap! *next-rpc-id inc))
        packet-type (if ret-schema
                      :rpc-req
                      :msg)
        packet (u/sym-map bytes msg-type-info msg-type-id packet-type rpc-id)
        ret-ch (ca/chan)]
    (if-not ret-schema
      (ca/put! ret-ch true)
      (let [timeout-ms (or timeout-ms* 30000)
            rpc-info {:cb (fn [ret]
                            (if ret
                              (ca/put! ret-ch ret)
                              (ca/close! ret-ch)))
                      :expiry-time-ms (+ (u/current-time-ms) timeout-ms)
                      :timeout-ms timeout-ms}]
        (swap! *rpc-id->info assoc rpc-id rpc-info)))
    (send-packet! packet)
    (when-not info-sent?
      (swap! *conn-info
             #(assoc-in % [:msg-type-id->info msg-type-id :info-sent?] true)))
    ret-ch))
