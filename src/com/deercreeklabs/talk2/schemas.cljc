(ns com.deercreeklabs.talk2.schemas
  (:require
   [deercreeklabs.lancaster :as l]
   [taoensso.timbre :as log]))

(l/def-enum-schema packet-type-schema
  :msg
  :msg-type-info-req
  :msg-type-info-rsp
  :rpc-req
  :rpc-rsp)

(l/def-record-schema msg-type-info-schema
  [:arg-json-schema l/string-schema]
  [:msg-type-name l/keyword-schema]
  [:ret-json-schema l/string-schema])

(l/def-record-schema packet-schema
  [:bytes l/bytes-schema]
  [:msg-type-id l/long-schema]
  [:msg-type-info msg-type-info-schema]
  [:packet-type packet-type-schema]
  [:rpc-id l/long-schema])
