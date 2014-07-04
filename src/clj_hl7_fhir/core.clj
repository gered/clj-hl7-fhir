(ns clj-hl7-fhir.core
  (:import (java.util Date))
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

(defn- format-search-value [x]
  (-> (cond
        (instance? Date x) (->iso-date x)
        :else              (str x))
      (.replace "\\" "\\\\")
      (.replace "$" "\\$")
      (.replace "," "\\,")
      (.replace "|" "\\|")))

(defn- make-search-param-name [parameter & [modifier]]
  (keyword
    (str
      (name parameter)
      (if modifier
        (str ":" (name modifier))))))

(defmacro single-search-op [name operator]
  `(defn ~name [parameter# value# & {:keys [modifier#]}]
     [(make-search-param-name parameter# modifier#)
      (str (if-not (= "=" ~operator) ~operator)
           (format-search-value value#))]))

(defmacro double-search-op [name operator1 operator2]
  `(defn ~name [parameter# value1# value2# & {:keys [modifier#]}]
     [(make-search-param-name parameter# modifier#)
      (str (if-not (= "=" ~operator1) ~operator1)
           (format-search-value value1#))
      (make-search-param-name parameter# modifier#)
      (str (if-not (= "=" ~operator2) ~operator2)
           (format-search-value value2#))]))

(single-search-op eq "=")
(single-search-op lt "<")
(single-search-op lte "<=")
(single-search-op gt ">")
(single-search-op gte ">=")
(double-search-op between ">" "<")

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
    (fhir-get-request
      base-url
      (apply join-paths url-components))))

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
      (apply concat params))))

