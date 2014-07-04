(ns clj-hl7-fhir.core
  (:import (java.util Date)
           (clojure.lang ExceptionInfo))
  (:require [clojure.string :as str])
  (:use [camel-snake-kebab]
        [clj-hl7-fhir.util]))

(def ^:private base-params {:_format "json"})

(defn- ->fhir-resource-name [x]
  (name (->CamelCase x)))

(defn- fhir-get-request [base-url resource-url & [params]]
  (let [query (map->query-string (merge base-params params))]
    (http-get-json (build-url base-url resource-url query))))

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

(defn- search-params->query-map [params]
  (->> params
       (apply concat)
       (map
         (fn [{:keys [name operator value]}]
           [name
            (str
              (if-not (= "=" operator) operator)
              (format-search-value value))]))
       (reduce
         (fn [m [name value]]
           (if (contains? m name)
             (update-in m [name] #(conj (if (vector? %) % [%]) value))
             (assoc m name value)))
         {})))

(defn- get-bundle-next-page-url [bundle]
  (->> (:link bundle)
       (filter #(= "next" (:rel %)))
       (first)
       :href))

(defn collect-resources
  "returns a sequence containing all of the resources contained in the given bundle

  reference:
  bundles: http://hl7.org/implement/standards/fhir/extras.html#bundle"
  [bundle]
  (->> bundle
       :entry
       (map :content)))

(defn fetch-next-page
  "for resources that are returned over more then one page, this will fetch the
   next page of resources as indicated by the link information contained in the
   passed bundle. the return value is another bundle that can be passed again
   to this function to get subsequent pages. if this function is passed the
   bundle for the last page of resources, nil is returned

   reference:
   bundles: http://hl7.org/implement/standards/fhir/extras.html#bundle
   paging: http://hl7.org/implement/standards/fhir/http.html#paging"
  [bundle]
  (if-let [next-url (get-bundle-next-page-url bundle)]
    (http-get-json next-url)))

(defn fetch-all
  "for resources that are returned over more then one page, this will automatically
   fetch all pages of resources and return a final sequence containing all of them
   in order

   reference:
   bundles: http://hl7.org/implement/standards/fhir/extras.html#bundle
   paging: http://hl7.org/implement/standards/fhir/http.html#paging"
  [bundle]
  (loop [current-page bundle
         fetched      []]
    (let [latest-fetched (concat fetched (collect-resources current-page))
          next-page      (fetch-next-page current-page)]
      (if next-page
        (recur next-page latest-fetched)
        latest-fetched))))

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
  "gets a single resource from a FHIR server that is contained in a bundle.

  reference:
  bundles: http://hl7.org/implement/standards/fhir/extras.html#bundle"
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
   the results of this function can be passed to fetch-next-page or fetch-all to collect resources
   returned in paged search results easier

   reference:
   search: http://hl7.org/implement/standards/fhir/http.html#search"
  [base-url type where & params]
  (let [resource-name  (->fhir-resource-name type)
        url-components ["/" resource-name]]
    (fhir-get-request
      base-url
      (apply join-paths url-components)
      (merge
        (search-params->query-map where)
        (apply hash-map params)))))
