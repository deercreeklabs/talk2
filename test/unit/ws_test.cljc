(ns unit.ws-test
  (:require
   [clojure.core.async :as ca]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [com.deercreeklabs.talk2.utils :as u]
   [taoensso.timbre :as log])
  #?(:clj
     (:import
      (clojure.lang ExceptionInfo))))

(def rsp-str
  (let [lines [" HTTP/1.1 101 Web Socket Protocol Handshake"
               "Connection: Upgrade"
               "Date: Mon, 31 May 2021 22:13:49 GMT"
               "Sec-WebSocket-Accept: lCoDebYroaMlyu4skRjb4iq+w84="
               "Server: Kaazing Gateway"
               "Upgrade: websocket"]]
    (-> (str/join "\r\n" lines)
        (str "\r\n\r\n"))))

(def extra-non-ascii-ba (ba/byte-array [189 190 191]))

(def rsp-ba (ba/concat-byte-arrays
             [(ba/utf8->byte-array rsp-str) extra-non-ascii-ba]))

(deftest test-ba->rsp-info
  (let [ret (u/byte-array->http-info rsp-ba)
        expected {:complete? true
                  :headers {:connection "Upgrade"
                            :date "Mon, 31 May 2021 22:13:49 GMT"
                            :sec-websocket-accept "lCoDebYroaMlyu4skRjb4iq+w84="
                            :server "Kaazing Gateway"
                            :upgrade "websocket"}
                  :first-line "HTTP/1.1 101 Web Socket Protocol Handshake"}]
    (is (= expected (dissoc ret :unprocessed-ba)))
    (is (ba/equivalent-byte-arrays? extra-non-ascii-ba (:unprocessed-ba ret)))))

(deftest test-ba->rsp-info-partial
  (let [ret (u/byte-array->http-info (ba/slice-byte-array rsp-ba 0 110))]
    (is (= false (:complete? ret)))))

(deftest test-normal-framing
  (let [xf-map (fn [m]
                 (-> (dissoc m :payload-start :payload-end)
                     (update :masking-key ba/byte-array->hex-str)
                     (update :payload-ba ba/byte-array->hex-str)))
        xf-map-no-payload (fn [m]
                            (-> (dissoc m :payload-start :payload-end
                                        :payload-ba)
                                (update :masking-key ba/byte-array->hex-str)))

        m-1 {:fin? true
             :opcode 1                  ; Text
             :payload-ba (ba/utf8->byte-array "Hello")}
        ba-1 (u/frame-info->byte-array m-1)
        _ (is (= "810548656c6c6f" (ba/byte-array->hex-str ba-1)))
        rt-1 (u/byte-array->frame-info ba-1)
        expected-map (-> (xf-map m-1)
                         (assoc :complete-header? true)
                         (assoc :complete-payload? true))
        _ (is (= expected-map (xf-map rt-1)))

        m-2 {:fin? true
             :masking-key (ba/byte-array [55, -6, 33, 61])
             :opcode 1                  ; Text
             :payload-ba (ba/utf8->byte-array "Hello")}
        ba-2 (u/frame-info->byte-array m-2)
        _ (is (= "818537fa213d7f9f4d5158" (ba/byte-array->hex-str ba-2)))
        rt-2 (u/byte-array->frame-info ba-2)
        expected-map (-> (xf-map-no-payload m-2)
                         (assoc :complete-header? true)
                         (assoc :complete-payload? true))
        _ (is (= expected-map (xf-map-no-payload rt-2)))

        m-3 {:fin? false
             :opcode 1                  ; Text
             :payload-ba (ba/utf8->byte-array "Hel")}
        ba-3 (u/frame-info->byte-array m-3)
        _ (is (= "010348656c" (ba/byte-array->hex-str ba-3)))
        rt-3 (u/byte-array->frame-info ba-3)
        expected-map (-> (xf-map m-3)
                         (assoc :complete-header? true)
                         (assoc :complete-payload? true))
        _ (is (= expected-map (xf-map rt-3)))

        m-4 {:fin? true
             :opcode 0                  ; Continuation
             :payload-ba (ba/utf8->byte-array "lo")}
        ba-4 (u/frame-info->byte-array m-4)
        _ (is (= "80026c6f" (ba/byte-array->hex-str ba-4)))
        rt-4 (u/byte-array->frame-info ba-4)
        expected-map (-> (xf-map m-4)
                         (assoc :complete-header? true)
                         (assoc :complete-payload? true))
        _ (is (= expected-map (xf-map rt-4)))

        m-4 {:fin? true
             :opcode 9                  ; Ping
             :payload-ba (ba/utf8->byte-array "Hello")}
        ba-4 (u/frame-info->byte-array m-4)
        _ (is (= "890548656c6c6f" (ba/byte-array->hex-str ba-4)))
        rt-4 (u/byte-array->frame-info ba-4)
        expected-map (-> (xf-map m-4)
                         (assoc :complete-header? true)
                         (assoc :complete-payload? true))
        _ (is (= expected-map (xf-map rt-4)))

        m-5 {:fin? true
             :masking-key (ba/byte-array [55, -6, 33, 61])
             :opcode 10                 ; Pong
             :payload-ba (ba/utf8->byte-array "Hello")}
        ba-5 (u/frame-info->byte-array m-5)
        _ (is (= "8a8537fa213d7f9f4d5158" (ba/byte-array->hex-str ba-5)))
        rt-5 (u/byte-array->frame-info ba-5)
        expected-map (-> (xf-map-no-payload m-5)
                         (assoc :complete-header? true)
                         (assoc :complete-payload? true))
        _ (is (= expected-map (xf-map-no-payload rt-5)))

        m-6 {:fin? true
             :opcode 2                  ; Binary
             :payload-len 256}
        ba-6 (u/frame-header-map->byte-array m-6)
        _ (is (= "827e0100" (ba/byte-array->hex-str ba-6)))
        rt-6 (u/byte-array->frame-header-map ba-6)
        expected-map (-> (xf-map m-6)
                         (assoc :complete-header? true))
        _ (is (= expected-map (xf-map rt-6)))

        m-6 {:fin? true
             :opcode 2                  ; Binary
             :payload-len 65536}
        ba-6 (u/frame-header-map->byte-array m-6)
        _ (is (= "827f0000000000010000" (ba/byte-array->hex-str ba-6)))
        rt-6 (u/byte-array->frame-header-map ba-6)
        expected-map (-> (xf-map m-6)
                         (assoc :complete-header? true))
        _ (is (= expected-map (xf-map rt-6)))]))

(deftest test-framing-w-multiple-frames
  (let [xf-map (fn [m]
                 (-> (dissoc m :payload-start :payload-end)
                     (update :masking-key ba/byte-array->hex-str)
                     (update :payload-ba ba/byte-array->hex-str)
                     (update :unprocessed-ba ba/byte-array->hex-str)))
        extra-str "fjaslfk!!@(234!*(jasflk"
        extra-ba (ba/utf8->byte-array extra-str)
        extra-hex-str (ba/byte-array->hex-str extra-ba)

        m-1 {:fin? true
             :opcode 1 ; Text
             :payload-ba (ba/utf8->byte-array "Hello")}
        ba-1 (u/frame-info->byte-array m-1)
        m-2 {:fin? true
             :opcode 1 ; Text
             :payload-ba (ba/utf8->byte-array " World")}
        ba-2 (u/frame-info->byte-array m-2)
        ba (ba/concat-byte-arrays [ba-1 ba-2])
        rt-1 (u/byte-array->frame-info ba)
        expected-map-1 (-> m-1
                           (assoc :complete-header? true)
                           (assoc :complete-payload? true)
                           (assoc :unprocessed-ba ba-2)
                           (xf-map))
        _ (is (= expected-map-1 (xf-map rt-1)))
        rt-2 (u/byte-array->frame-info (:unprocessed-ba rt-1))
        expected-map-2 (-> m-2
                           (assoc :complete-header? true)
                           (assoc :complete-payload? true)
                           (xf-map))
        _ (is (= expected-map-2 (xf-map rt-2)))]))

(deftest test-framing-w-extra-bytes
  (let [xf-map (fn [m]
                 (-> (dissoc m :payload-start :payload-end)
                     (update :masking-key ba/byte-array->hex-str)
                     (update :payload-ba ba/byte-array->hex-str)
                     (update :unprocessed-ba ba/byte-array->hex-str)))
        extra-str "fjaslfk!!@(234!*(jasflk"
        extra-ba (ba/utf8->byte-array extra-str)
        extra-hex-str (ba/byte-array->hex-str extra-ba)

        m-1 {:fin? true
             :opcode 1 ; Text
             :payload-ba (ba/utf8->byte-array "Hello")}
        ba-1 (u/frame-info->byte-array m-1)
        _ (is (= "810548656c6c6f" (ba/byte-array->hex-str ba-1)))
        ba-1-extra (ba/concat-byte-arrays [ba-1 extra-ba])
        rt-1 (u/byte-array->frame-info ba-1-extra)
        expected-map (-> m-1
                         (assoc :complete-header? true)
                         (assoc :complete-payload? true)
                         (assoc :unprocessed-ba extra-ba)
                         (xf-map))
        _ (is (= expected-map (xf-map rt-1)))

        m-2 {:fin? true
             :opcode 2 ; Binary
             :payload-len 256}
        ba-2 (u/frame-header-map->byte-array m-2)
        _ (is (= "827e0100" (ba/byte-array->hex-str ba-2)))
        ba-2-extra (ba/concat-byte-arrays [ba-2 extra-ba])
        rt-2 (u/byte-array->frame-info ba-2-extra)
        expected-map (-> (dissoc m-2 :payload-len)
                         (assoc :complete-header? true)
                         (assoc :complete-payload? false)
                         (assoc :unprocessed-ba extra-ba)
                         (xf-map))
        _ (is (= expected-map (xf-map rt-2)))

        m-3 {:fin? true
             :opcode 2 ; Binary
             :payload-len 66000}
        ba-3 (u/frame-header-map->byte-array m-3)
        _ (is (= "827f00000000000101d0" (ba/byte-array->hex-str ba-3)))
        ba-3-extra (ba/concat-byte-arrays [ba-3 extra-ba])
        rt-3 (u/byte-array->frame-info ba-3-extra)
        expected-map (-> (dissoc m-3 :payload-len)
                         (assoc :complete-header? true)
                         (assoc :complete-payload? false)
                         (assoc :unprocessed-ba extra-ba)
                         (xf-map))
        _ (is (= expected-map (xf-map rt-3)))]))

(deftest test-framing-w-incomplete-data
  (let [xf-map (fn [{:keys [masking-key unprocessed-ba] :as m}]
                 (cond-> (dissoc m :payload-start :payload-end :payload-ba)
                   masking-key (update :masking-key
                                       ba/byte-array->hex-str)
                   (not masking-key) (dissoc :masking-key)
                   unprocessed-ba (update :unprocessed-ba
                                          ba/byte-array->hex-str)))

        ba-1-short (ba/hex-str->byte-array "81")
        rt-1 (u/byte-array->frame-info ba-1-short)
        expected-map {:complete-header? false
                      :complete-payload? false
                      :unprocessed-ba "81"}
        _ (is (= expected-map (xf-map rt-1)))

        ba-2-short (ba/hex-str->byte-array "810548656c6c")
        rt-2 (u/byte-array->frame-info ba-2-short)
        expected-map {:complete-header? true
                      :complete-payload? false
                      :fin? true
                      :opcode 1
                      :unprocessed-ba "48656c6c"}
        _ (is (= expected-map (xf-map rt-2)))

        ba-3-short (ba/hex-str->byte-array "827e0100")
        rt-3 (u/byte-array->frame-info ba-3-short)
        expected-map {:complete-header? true
                      :complete-payload? false
                      :fin? true
                      :opcode 2
                      :unprocessed-ba ""}
        _ (is (= expected-map (xf-map rt-3)))

        ba-4-short (ba/hex-str->byte-array "827e01")
        rt-4 (u/byte-array->frame-info ba-4-short)
        expected-map {:complete-header? false
                      :complete-payload? false
                      :unprocessed-ba "827e01"}
        _ (is (= expected-map (xf-map rt-4)))

        ba-4-short (ba/hex-str->byte-array "827f00000000000101d0ff")
        rt-4 (u/byte-array->frame-info ba-4-short)
        expected-map {:complete-header? true
                      :complete-payload? false
                      :fin? true
                      :opcode 2
                      :unprocessed-ba "ff"}
        _ (is (= expected-map (xf-map rt-4)))]))

(deftest test-code->byte-array->code
  (let [codes [1000 1001 3000 3999 4000 4999]]
    (doseq [code codes]
      (let [ba (u/code->byte-array code)
            rt-code (u/byte-array->code ba)]
        (is (= code rt-code))))))

(deftest test-ws-key->ws-accept-key
  (let [ws-key "dGhlIHNhbXBsZSBub25jZQ=="
        expected "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="]
    (is (= expected (u/ws-key->ws-accept-key ws-key)))))

(deftest test-process-data!-short-text-msg
  (au/test-async
   1000
   (au/go
     (let [msg-ch (ca/chan)
           data "Hi"
           bas1 (u/data->frame-byte-arrays :text data true 50)
           _ (is (= 1 (count bas1)))
           arg1 {:ba (first bas1)
                 :on-message #(ca/put! msg-ch (:data %))
                 :server? true}
           _ (u/process-data! arg1)
           _ (is (= data (au/<? msg-ch)))]))))

(deftest test-process-data!-fragmentation-and-continuation-frames
  (au/test-async
   1000
   (au/go
     (let [msg-ch (ca/chan)
           max-payload-len 12
           ;; 50 char string, will fragment
           s "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
           bas (u/data->frame-byte-arrays :text s true max-payload-len)
           _ (is (= 5 (count bas)))
           arg {:ba (ba/concat-byte-arrays bas)
                :on-message #(ca/put! msg-ch (:data %))
                :server? true}
           ret (u/process-data! arg)]
       (is (= s (au/<? msg-ch)))))))

(deftest test-process-data!-two-msgs
  (au/test-async
   1000
   (au/go
     (let [msg-ch (ca/chan)
           max-payload-len 12
           s1 "XXXXXXXXXXXXXXXXXXXX" ; 20 char string, will fragment
           s2 "Hi"
           bas1 (u/data->frame-byte-arrays :text s1 true max-payload-len)
           bas2 (u/data->frame-byte-arrays :text s2 true max-payload-len)
           _ (is (= 2 (count bas1)))
           _ (is (= 1 (count bas2)))
           arg {:ba (ba/concat-byte-arrays (concat bas1 bas2))
                :on-message #(ca/put! msg-ch (:data %))
                :server? true}
           ret (u/process-data! arg)]
       (is (= s1 (au/<? msg-ch)))
       (is (= s2 (au/<? msg-ch)))))))

(deftest test-process-data!-less-than-full-frame
  (let [max-payload-len 12
        s "12345678"
        bas (u/data->frame-byte-arrays :text s true max-payload-len)
        _ (is (= 1 (count bas)))
        ba (first bas)
        _ (is (= 14 (count ba)))
        chunk (ba/slice-byte-array ba 0 10)
        arg {:ba chunk
             :server? true}
        {:keys [continuation-ba
                continuation-opcode
                unprocessed-ba]} (u/process-data! arg)]
    (is (= nil continuation-ba))
    (is (= nil continuation-opcode))
    (is (= 10 (count unprocessed-ba)))
    (is (ba/equivalent-byte-arrays? chunk unprocessed-ba))))
