(ns clj-hl7-fhir.core
  (:import (java.util Date)
           (clojure.lang ExceptionInfo))
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json])
  (:use [camel-snake-kebab]
        [clj-hl7-fhir.util]))

(defn- ->fhir-resource-name [x]
  (name (->CamelCase x)))

(defn- fhir-get-request [base-url resource-url & [params]]
  (let [query (cond
                (seq? params) (->> params (concat [:_format "json"]) (kv-vector->query))
                :else         (merge {:_format "json"} params))]
    (-> (build-url base-url resource-url query)
        (http/get)
        :body
        (json/parse-string true))))

(defn- ->search-param-name [parameter & [modifier]]
  (keyword
    (str
      (if (vector? parameter)
        (->> parameter
             (map name)
             (str/join ".")
             )
        (name parameter))
      (if modifier
        (str ":" (name modifier))))))

(defn ->search-param-descriptor [parameter value operator {:keys [modifier]}]
  {:name (->search-param-name parameter modifier)
   :operator operator
   :value value})

(defmacro single-search-op [name operator]
  `(defn ~name [parameter# value# & options#]
     [(->search-param-descriptor parameter# value# ~operator (apply hash-map options#))]))

(defmacro double-search-op [name operator1 operator2]
  `(defn ~name [parameter# value1# value2# & options#]
     [(->search-param-descriptor parameter# value1# ~operator1 (apply hash-map options#))
      (->search-param-descriptor parameter# value2# ~operator2 (apply hash-map options#))]))

(single-search-op eq "=")
(single-search-op lt "<")
(single-search-op lte "<=")
(single-search-op gt ">")
(single-search-op gte ">=")
(double-search-op between ">" "<")

(defn namespaced
  ([value]
   (namespaced nil value))
  ([namespace value]
   {:namespace namespace
    :value value}))

(defn- escape-parameter [value]
  (-> value
      (.replace "\\" "\\\\")
      (.replace "$" "\\$")
      (.replace "," "\\,")
      (.replace "|" "\\|")))

(defn- format-search-value [value]
  (cond
    (sequential? value)
    (->> value
         (map format-search-value)
         (str/join ","))

    (map? value)
    (str (:namespace value) "|" (format-search-value (:value value)))

    (instance? Date value)
    (->iso-date value)

    :else
    (-> value str escape-parameter)))

(defn- search-params->query-kvs [params]
  (->> params
       (apply concat)
       (map
         (fn [{:keys [name operator value]}]
           [name
            (str
              (if-not (= "=" operator) operator)
              (format-search-value value))]))
       (apply concat)))

(defn get-resource
  "gets a single resource from a FHIR server. can optionally get a specific version of a resource.

   reference:
   read: http://hl7.org/implement/standards/fhir/http.html#read
   vread: http://hl7.org/implement/standards/fhir/http.html#vread"
  [base-url type id & {:keys [version]}]
  (let [resource-name  (->fhir-resource-name type)
        url-components (if version
                         ["/" resource-name id "_history" version]
                         ["/" resource-name id])]
    (try
      (fhir-get-request
        base-url
        (apply join-paths url-components))
      (catch ExceptionInfo ex
        (if (not= 404 (get-in (ex-data ex) [:object :status]))
          (throw ex))))))

(defn get-resource-bundle
  "gets a single resource from a FHIR server that is contained in a bundle."
  [base-url type id]
  (let [resource-name (->fhir-resource-name type)
        url-components ["/" resource-name]]
    (fhir-get-request
      base-url
      (apply join-paths url-components)
      {:_id id})))

(defn search
  "searches for resources on a FHIR server. multiple parameters are ANDed together. use of the search
   operator helper functions is encouraged to ensure proper escaping/encoding of search parameters.

   reference:
   search: http://hl7.org/implement/standards/fhir/http.html#search"
  [base-url type & params]
  (let [resource-name  (->fhir-resource-name type)
        url-components ["/" resource-name]]
    (fhir-get-request
      base-url
      (apply join-paths url-components)
      (search-params->query-kvs params))))

