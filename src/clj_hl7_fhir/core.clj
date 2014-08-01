(ns clj-hl7-fhir.core
  (:import (java.util Date)
           (clojure.lang ExceptionInfo))
  (:require [clojure.string :as str]
            [cheshire.core :as json])
  (:use [camel-snake-kebab]
        [clj-hl7-fhir.util]))

(defn- ->fhir-resource-name [x]
  (name (->CamelCase x)))

(defn- fhir-request [type base-url resource-url & {:keys [params body follow-location?]}]
  (let [query            (map->query-string params)
        url              (build-url base-url resource-url query)
        follow-location? (if (nil? follow-location?) true follow-location?)]
    (try
      (let [response (case type
                       :get    (http-get-json url)
                       :post   (http-post-json url body)
                       :put    (http-put-json url body)
                       :delete (http-delete-json url body))
            location (get-in response [:headers "Location"])]
        (if location
          (if follow-location?
            (-> (http-get-json location)
                :body
                (json/parse-string true))
            location)))
      (catch ExceptionInfo ex
        (let [{:keys [status body headers]} (:object (ex-data ex))
              fhir-resource-response?       (.contains (get headers "Content-Type") "application/json+fhir")]
          (throw (ex-info (str "FHIR request failed: HTTP " status)
                          {:status status
                           :fhir-resource? fhir-resource-response?
                           :response
                            (if fhir-resource-response?
                              (json/parse-string body true)
                              body)})))))))

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

(defn- ->search-param-descriptor [parameter value operator {:keys [modifier]}]
  {:name (->search-param-name parameter modifier)
   :operator operator
   :value value})

(defmacro ^:private single-search-op [name operator]
  `(defn ~name [parameter# value# & options#]
     [(->search-param-descriptor parameter# value# ~operator (apply hash-map options#))]))

(defmacro ^:private double-search-op [name operator1 operator2]
  `(defn ~name [parameter# value1# value2# & options#]
     [(->search-param-descriptor parameter# value1# ~operator1 (apply hash-map options#))
      (->search-param-descriptor parameter# value2# ~operator2 (apply hash-map options#))]))

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
    (->timestamp value)

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

(defn resource?
  "returns true if the given argument is an EDN representation of a FHIR resource"
  [x]
  (and (map? x)
       (string? (:resourceType x))
       (not= "Bundle" (:resourceType x))))

(defn bundle?
  "returns true if the given argument is an EDN representation of a FHIR bundle"
  [x]
  (and (map? x)
       (= "Bundle" (:resourceType x))))

(defn validate-resource! [resource]
  (if (and resource
           (not (resource? resource)))
    (throw (Exception. "Not a valid FHIR resource"))))

(defn validate-bundle! [bundle]
  (if (and bundle
           (not (bundle? bundle)))
    (throw (Exception. "Not a valid FHIR bundle"))))

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

(defn collect-resources
  "returns a sequence containing all of the resources contained in the given bundle

  reference:
  bundles: http://hl7.org/implement/standards/fhir/extras.html#bundle"
  [bundle]
  (validate-bundle! bundle)
  (->> bundle
       :entry
       (map :content)))

(defn- get-bundle-next-page-url [bundle]
  (validate-bundle! bundle)
  (->> (:link bundle)
       (filter #(= "next" (:rel %)))
       (first)
       :href))

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

(defn- concat-bundle-entries [bundle other-bundle]
  (if (nil? bundle)
    other-bundle
    (update-in
      bundle [:entry]
      (fn [existing-entries]
        (->> (:entry other-bundle)
             (concat existing-entries)
             (vec))))))

(defn- strip-bundle-page-links [bundle]
  (if bundle
    (assoc bundle
      :link
      (->> (:link bundle)
           (remove
             (fn [{:keys [rel]}]
               (or (= rel "first")
                   (= rel "last")
                   (= rel "next")
                   (= rel "previous"))))
           (vec)))))

(defn fetch-all
  "for resources that are returned over more then one page, this will automatically
   fetch all pages of resources and them into a single bundle that contains all of
   the resources.

   reference:
   bundles: http://hl7.org/implement/standards/fhir/extras.html#bundle
   paging: http://hl7.org/implement/standards/fhir/http.html#paging"
  [bundle]
  (loop [current-page   bundle
         working-bundle nil]
    (let [merged    (concat-bundle-entries working-bundle current-page)
          next-page (fetch-next-page current-page)]
      (if next-page
        (recur next-page merged)
        (strip-bundle-page-links merged)))))

(defn get-resource
  "gets a single resource from a FHIR server. the raw resource itself is returned (that is,
   it is not contained in a bundle). if the resource could not be found, nil is returned.
   for any other type of response (errors), an exception is thrown.

   a relative url can be used to identify the resource to be retrieved, or a resource type,
   id and optional version number can be used.

   reference:
   read: http://hl7.org/implement/standards/fhir/http.html#read
   vread: http://hl7.org/implement/standards/fhir/http.html#vread
   relative url: http://hl7.org/implement/standards/fhir/references.html#atom-rel"
  ([base-url relative-resource-url]
   (try
     (fhir-request :get
       base-url
       relative-resource-url)
     (catch ExceptionInfo ex
       (let [http-status (:status (ex-data ex))]
         ; TODO: do we want to handle 410 differently? either way, the resource is not available
         ;       though, a 410 could indicate to the caller that it might be available under a
         ;       previous version ...
         (if-not (or (= http-status 404)
                     (= http-status 410))
           (throw ex))))))
  ([base-url type id & {:keys [version]}]
   (let [resource-name (->fhir-resource-name type)
         url-components (if version
                          ["/" resource-name id "_history" version]
                          ["/" resource-name id])]
     (get-resource base-url (apply join-paths url-components)))))

(defn get-relative-resource
  "gets a single resource from a FHIR server. the server to be queried will be taken from the
   'fhir-base' link in the provided bundle. an exception is thrown if an error response is
   received."
  [bundle relative-url]
  (if bundle
    (let [base-url (->> (:link bundle)
                        (filter #(= "fhir-base" (:rel %)))
                        (first)
                        :href)]
      (get-resource base-url relative-url))))

(defn get-resource-bundle
  "gets a single resource from a FHIR server. the returned resource will be contained in a
   bundle. if the resource could not be found, a bundle containing zero resources is returned.
   an exception is thrown if an error response is received.

  reference:
  bundles: http://hl7.org/implement/standards/fhir/extras.html#bundle"
  [base-url type id]
  (let [resource-name (->fhir-resource-name type)
        url-components ["/" resource-name]]
    (fhir-request :get
      base-url
      (apply join-paths url-components)
      :params {:_id id})))

(defn search
  "searches for resources on a FHIR server. multiple parameters are ANDed together. use of the search
   operator helper functions is encouraged to ensure proper escaping/encoding of search parameters.
   the results of this function can be passed to fetch-next-page or fetch-all to collect resources
   returned in paged search results easier. an exception is thrown if an error response is received.

   reference:
   search: http://hl7.org/implement/standards/fhir/http.html#search"
  [base-url type where & params]
  (let [resource-name  (->fhir-resource-name type)
        url-components ["/" resource-name]]
    (fhir-request :get
      base-url
      (apply join-paths url-components)
      :params (merge
                (search-params->query-map where)
                (apply hash-map (if (and (seq? params)
                                         (= 1 (count params)))
                                  (first params)
                                  params))))))

(defn search-and-fetch
  "same as search, but automatically fetches all pages of resources returning a single bundle
   that contains all search results. an exception is thrown if an error response is received."
  [base-url type where & params]
  (fetch-all
    (search base-url type where params)))

(defn create
  "creates a new resource. returns the created resource if successful and the server response
   contained a 'Location' header, otherwise returns nil. throws an exception otherwise.

   reference:
   create: http://hl7.org/implement/standards/fhir/http.html#create"
  [base-url type resource & {:keys [return-resource?]}]
  (if-not (resource? resource)
    (throw (Exception. "Not a valid FHIR resource")))
  (let [resource-name    (->fhir-resource-name type)
        uri-components   ["/" resource-name]
        return-resource? (if (nil? return-resource?) true return-resource?)]
    (fhir-request :post
      base-url
      (apply join-paths uri-components)
      :body resource
      :follow-location? return-resource?)))

(defn update
  "updates an existing resource. returns the updated resource if successful and the server
   response contained a 'Location' header, otherwise returns nil. throws an exception if
   an error response was received.

   reference:
   update: http://hl7.org/implement/standards/fhir/http.html#update"
  [base-url type id resource & {:keys [version return-resource?]}]
  (if-not (resource? resource)
    (throw (Exception. "Not a valid FHIR resource")))
  (let [resource-name    (->fhir-resource-name type)
        uri-components   (if version
                           ["/" resource-name id "_history" version]
                           ["/" resource-name id])
        return-resource? (if (nil? return-resource?) true return-resource?)]
    (fhir-request :put
      base-url
      (apply join-paths uri-components)
      :body resource
      :follow-location? return-resource?)))

(defn delete
  "deletes an existing resource. returns nil on success, throws an exception if an error
   response was received.

   reference:
   delete: http://hl7.org/implement/standards/fhir/http.html#delete"
  [base-url type id]
  (let [resource-name  (->fhir-resource-name type)
        uri-components ["/" resource-name id]]
    (fhir-request :delete
      base-url
      (apply join-paths uri-components))))

;(def server-url "http://fhir.healthintersections.com.au/open")
;(def server-url "http://spark.furore.com/fhir")
;(def server-url "http://fhirtest.uhn.ca/base")
