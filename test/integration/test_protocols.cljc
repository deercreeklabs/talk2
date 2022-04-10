(ns integration.test-protocols
  (:require
   [deercreeklabs.lancaster :as l]))

(l/def-record-schema offset-and-sum-numbers-arg-schema
  [:numbers (l/array-schema l/int-schema)]
  [:offset l/int-schema])

(def client-gateway-protocol
  {:offset-and-sum-numbers {:arg-schema offset-and-sum-numbers-arg-schema
                            :ret-schema l/int-schema}
   :request-status-update {:arg-schema l/null-schema}
   :status-update {:arg-schema l/string-schema}
   :throw-if-even {:arg-schema l/int-schema
                   :ret-schema l/boolean-schema}})

(def backend-gateway-protocol
  {:sum-numbers {:arg-schema (l/array-schema l/int-schema)
                 :ret-schema l/int-schema}})
