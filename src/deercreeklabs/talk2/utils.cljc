(ns deercreeklabs.talk2.utils
  (:require
   [clojure.core.async :as ca]
   #?(:cljs [clojure.pprint :as pprint])
   [clojure.string :as str]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.lancaster :as l]
   #?(:clj [puget.printer :as puget])
   [taoensso.timbre :as log])
  #?(:cljs
     (:require-macros
      [deercreeklabs.talk2.utils :refer [sym-map go-log go-log-helper*]]))
  #?(:clj
     (:import
      (java.security SecureRandom)
      (java.util UUID))

     :cljs
     (:import
      (goog.math Long))))


;; 2^31-1 - Largest safe positive JS 32-bit signed integer. JS bitwise
;; operations are performed on 32-bit signed integers.
(def max-ws-payload-len 2147483647)

(def ws-key-constant "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
(def four-zeros-ba (ba/byte-array [0 0 0 0]))

(defmacro sym-map
  "Builds a map from symbols.
   Symbol names are turned into keywords and become the map's keys.
   Symbol values become the map's values.
  (let [a 1
        b 2]
    (sym-map a b))  =>  {:a 1 :b 2}"
  [& syms]
  (zipmap (map keyword syms) syms))

(defn pprint [x]
  #?(:clj (.write *out* ^String (puget/pprint-str x))
     :cljs (pprint/pprint x)))

(defn pprint-str [x]
  #?(:clj (puget/pprint-str x)
     :cljs (with-out-str (pprint/pprint x))))

(defn int-pow [base exp]
  (int (Math/pow base exp)))

(defn str->int [s]
  (when (seq s)
    #?(:clj (Integer/parseInt s)
       :cljs (js/parseInt s))))

(defn ex-msg [e]
  #?(:clj (.toString ^Exception e)
     :cljs (.-message e)))

(defn ex-stacktrace [e]
  #?(:clj (clojure.string/join "\n" (map str (.getStackTrace ^Exception e)))
     :cljs (.-stack e)))

(defn ex-msg-and-stacktrace [e]
  (let [data (ex-data e)
        lines (cond-> [(str "\nException:\n" (ex-msg e))]
                data (conj (str "\nex-data:\n" (ex-data e)))
                true (conj (str "\nStacktrace:\n" (ex-stacktrace e))))]
    (str/join "\n" lines)))

(defn current-time-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs(.fromNumber ^Long Long (.getTime (js/Date.)))))

(defmacro go-log-helper* [ex-type body]
  `(try
     ~@body
     (catch ~ex-type e#
       (log/error (ex-msg-and-stacktrace e#)))))

(defmacro go-log [& body]
  `(au/if-cljs
    (clojure.core.async/go
      (go-log-helper* :default ~body))
    (clojure.core.async/go
      (go-log-helper* Exception ~body))))

(defn round-int [n]
  (int #?(:clj (Math/round (float n))
          :cljs (js/Math.round n))))

(defn floor-int [n]
  (int #?(:clj (Math/round (Math/floor (float n)))
          :cljs (js/Math.floor n))))

;;;;;;;;;;;;;;;;;;;; Platform detection ;;;;;;;;;;;;;;;;;;;;

(defn jvm? []
  #?(:clj true
     :cljs false))

(defn browser? []
  #?(:clj false
     :cljs (exists? js/navigator)))

(defn node? []
  #?(:clj false
     :cljs (boolean (= "nodejs" cljs.core/*target*))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn secure-random-byte-array [num-bytes]
  #?(:clj (let [ba (ba/byte-array num-bytes)]
            (.nextBytes (SecureRandom.) ba)
            ba)
     :cljs (cond
             (node?)
             (js/Int8Array. (js/crypto.randomBytes num-bytes))

             (browser?)
             (let [ba (ba/byte-array num-bytes)]
               (js/window.crypto.getRandomValues ba)
               ba)

             :else
             (throw (ex-info "Unsupported environment" {})))))

(defn random-byte-array [num-bytes]
  (-> (map (fn [i]
             (rand-int 255))
           (range num-bytes))
      (ba/byte-array)))

(defn ws-key->ws-accept-key [ws-key]
  (-> (str ws-key ws-key-constant)
      (ba/utf8->byte-array)
      (ba/sha1)
      (ba/byte-array->b64)))

(defn code->byte-array [code]
  (let [hi (-> (unsigned-bit-shift-right code 8)
               (bit-and 0xff)
               (unchecked-byte))
        lo (-> code
               (bit-and 0xff)
               (unchecked-byte))]
    (ba/byte-array [hi lo])))

(defn byte-array->code [ba]
  (let [hi (aget ^bytes ba 0)
        lo (aget ^bytes ba 1)]
    (bit-or (bit-shift-left (bit-and 0xff hi) 8)
            (bit-and 0xff lo))))

(defn add-header [headers line]
  (let [[k v] (str/split line #":" 2)
        kw-k (-> (str/trim k)
                 (str/lower-case)
                 (keyword))]
    (update headers kw-k (fn [existing-v]
                           (let [trimmed-v (str/trim v)]
                             (if (empty? existing-v)
                               trimmed-v
                               (str existing-v ", " trimmed-v)))))))

(defn byte-array->http-info [ba]
  (let [bv (vec ba)
        len (count bv)
        ba-len (count ba)]
    (loop [i 0
           complete? false
           headers {}
           first-line nil
           unprocessed-bytes []]
      (if (>= i len)
        {:complete? complete?
         :headers headers
         :first-line first-line
         :unprocessed-ba (ba/byte-array unprocessed-bytes)}
        (let [b (nth bv i)
              ni (inc i)
              nb (when (< ni len)
                   (nth bv ni))
              eol? (and (= 13 b) (= 10 nb))
              new-unprocessed-bytes (conj unprocessed-bytes b)
              line (when-not complete?
                     (->> (map char unprocessed-bytes)
                          (apply str)
                          (str/trim)))]

          (cond
            (not eol?)
            (recur ni complete? headers first-line new-unprocessed-bytes)

            (not first-line)
            (recur (inc ni) complete? headers line [])

            (seq line)
            (let [new-headers (add-header headers line)]
              (recur (inc ni) complete? new-headers first-line []))

            :else ; Empty line means end of the rsp
            (recur (inc ni) true headers first-line [])))))))

(defn mask-ws-payload!
  "Mutates the ba arg in place to avoid allocation costs, which are significant
   with large payloads."
  [key-ba ba]
  (dotimes [i (count ba)]
    (aset ^bytes ba i
          ^byte (byte (bit-xor (aget ^bytes key-ba (rem i 4))
                               (aget ^bytes ba i))))))

(defn throw-too-large-payload [payload-len]
  (throw
   (ex-info
    (str "Payload too large (" payload-len " bytes). "
         "Max payload size is " max-ws-payload-len " bytes.")
    (sym-map payload-len max-ws-payload-len))))

(defn byte-array->int [ba]
  (let [n (count ba)]
    (reduce (fn [acc i]
              (-> (aget ^bytes ba i)
                  (bit-and 0xff)
                  (bit-shift-left (* 8 (- n 1 i)))
                  (bit-or acc)))
            0
            (range n))))

(defn int->byte-array [x]
  (-> (reduce (fn [acc i]
                (conj acc (-> (unsigned-bit-shift-right x (* 8 (- 3 i)))
                              (bit-and 0xff)
                              (unchecked-byte))))
              []
              (range 4))
      (ba/byte-array)))

(defn frame-header-map->byte-array
  "https://datatracker.ietf.org/doc/html/rfc6455#section-5.2"
  [{:keys [fin? masking-key opcode payload-len]}]
  (when-not (#{0 1 2 8 9 10} opcode)
    (throw (ex-info (str "Invalid opcode: " opcode ".")
                    (sym-map opcode))))
  (let [[l0 l1-ba] (cond
                     (<= payload-len 125)
                     [payload-len nil]

                     (<= payload-len 65535)
                     (let [hb (-> (bit-and 0xff00 payload-len)
                                  (unsigned-bit-shift-right 8)
                                  (unchecked-byte))
                           lb (-> (bit-and 0x00ff payload-len)
                                  (unchecked-byte))]
                       [126 (ba/byte-array [hb lb])])

                     (<= payload-len max-ws-payload-len)
                     [127 (ba/concat-byte-arrays
                           [four-zeros-ba (int->byte-array payload-len)])]

                     :else
                     (throw-too-large-payload payload-len))
        b0 (bit-or (if fin? 0x80 0) opcode)
        b1 (bit-or (if masking-key
                     0x80
                     0)
                   l0)]
    (ba/concat-byte-arrays [(ba/byte-array [b0 b1])
                            l1-ba
                            masking-key])))

(defn frame-info->byte-array [m]
  (let [{:keys [payload-ba masking-key]} m
        header-ba (frame-header-map->byte-array
                   (assoc m :payload-len (count payload-ba)))
        payload-ba* (cond
                      (not payload-ba)
                      nil

                      (not masking-key)
                      payload-ba

                      :else
                      (do
                        (mask-ws-payload! masking-key payload-ba)
                        payload-ba))]
    (ba/concat-byte-arrays [header-ba payload-ba*])))

(defn get-payload-start [masked? short-payload-len]
  (cond-> (case short-payload-len
            126 4
            127 10
            2) ; (for < 125 bytes)
    masked? (+ 4)))

(defn get-long-payload-len [ba short-payload-len]
  (case short-payload-len
    126 (byte-array->int (ba/slice-byte-array ba 2 4))
    ;; We only support that are payloads that are less
    ;; than 2^31-1, so the first 4 bytes of the 8-byte
    ;; word must be zero.
    127 (do
          (when-not (and (zero? (aget ^bytes ba 2))
                         (zero? (aget ^bytes ba 3))
                         (zero? (aget ^bytes ba 4))
                         (zero? (aget ^bytes ba 5)))
            (throw (ex-info (str "Payload length is greater than "
                                 max-ws-payload-len ".")
                            (sym-map ba max-ws-payload-len))))
          (byte-array->int (ba/slice-byte-array ba 6 10)))
    short-payload-len)) ; (for < 125 bytes)

(defn byte-array->frame-header-map [ba]
  "https://datatracker.ietf.org/doc/html/rfc6455#section-5.2"
  [ba]
  (let [ba-len (count ba)]
    (if (< ba-len 2)
      {:complete-header? false
       :unprocessed-ba ba}
      (let [b0 (aget ^bytes ba 0)
            b1 (aget ^bytes ba 1)
            fin? (bit-test b0 7)
            opcode (bit-and b0 0x0f)
            masked? (bit-test b1 7)
            short-payload-len (bit-and b1 0x7f)
            payload-start (get-payload-start masked? short-payload-len)]
        (if (< ba-len payload-start)
          {:complete-header? false
           :complete-payload? false
           :unprocessed-ba ba}
          (let [payload-len (get-long-payload-len ba short-payload-len)
                _ (when (> payload-len max-ws-payload-len)
                    (throw-too-large-payload payload-len))
                payload-end (+ payload-start payload-len)
                masking-key-start (- payload-start 4)
                masking-key (when masked?
                              (ba/slice-byte-array
                               ba masking-key-start payload-start))
                complete-header? true]
            (sym-map complete-header?
                     fin?
                     masking-key
                     opcode
                     payload-end
                     payload-len
                     payload-start)))))))

(defn byte-array->frame-info [ba]
  (let [ba-len (count ba)
        header-map (byte-array->frame-header-map ba)
        {:keys [complete-header? masking-key
                payload-start payload-end]} header-map
        m (dissoc header-map :payload-len :payload-start :payload-end)]
    (cond
      (not complete-header?)
      (-> m
          (assoc :complete-payload? false)
          (assoc :unprocessed-ba ba))

      (< ba-len payload-end)
      (-> m
          (assoc :complete-payload? false)
          (assoc :unprocessed-ba (ba/slice-byte-array ba payload-start)))

      :else
      (let [payload (ba/slice-byte-array ba payload-start payload-end)]
        (when masking-key
          (mask-ws-payload! masking-key payload))
        (cond-> m
          true (assoc :complete-payload? true)
          (pos? (count payload)) (assoc :payload-ba payload)
          (> ba-len payload-end) (assoc :unprocessed-ba (ba/slice-byte-array
                                                         ba payload-end)))))))

(defn process-frame!
  [frame-info continuation-ba continuation-opcode on-close-frame on-message
   on-ping on-pong]
  (let [{:keys [fin? opcode payload-ba]} frame-info
        control-frame? (bit-test opcode 7)
        data-ba (when (= 0 opcode)
                  (ba/concat-byte-arrays [continuation-ba payload-ba]))]
    ;; Do effects
    (case (int opcode)
      0 (when fin?
          (let [data (cond-> data-ba
                       (= 1 continuation-opcode) (ba/byte-array->utf8))]
            (on-message data)))
      1 (when fin?
          (on-message (ba/byte-array->utf8 payload-ba)))
      2 (when fin?
          (on-message payload-ba))
      8 (on-close-frame)
      9 (on-ping payload-ba)
      10 (on-pong payload-ba)
      (throw (ex-info (str "Bad opcode: `" opcode "`.")
                      (sym-map opcode))))
    (cond
      (or fin? control-frame?)
      {:continuation-ba nil
       :continuation-opcode nil}

      (zero? (int opcode))
      {:continuation-ba data-ba
       :continuation-opcode continuation-opcode}

      :else
      {:continuation-ba payload-ba
       :continuation-opcode opcode})))

(defn data->frame-byte-arrays [msg-type data mask? max-payload-len]
  (let [ba (if (= :text msg-type)
             (ba/utf8->byte-array data)
             data)
        payload-bas (or (ba/byte-array->fragments ba max-payload-len)
                        [nil])
        last-i (int (dec (count payload-bas)))]
    (map-indexed
     (fn [i payload-ba]
       (let [fin? (= (int i) last-i)
             masking-key (when mask?
                           (secure-random-byte-array 4))
             opcode (cond
                      (pos? i) 0 ; continuation
                      (= :text msg-type) 1
                      (= :binary msg-type) 2
                      (= :close msg-type) 8
                      (= :ping msg-type) 9
                      (= :pong msg-type) 10)
             frame-info (sym-map fin? masking-key opcode payload-ba)]
         (frame-info->byte-array frame-info)))
     payload-bas)))

(defn get-msg-type [data]
  (cond
    (string? data) :text
    (ba/byte-array? data) :binary
    :else (throw (ex-info (str "Bad data type passed to `send!`. "
                               "Data must be a string or byte array.")
                          (sym-map data)))))
