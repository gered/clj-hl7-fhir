(ns clj-hl7-fhir.core
  (:refer-clojure :exclude [update])
  (:require
    [camel-snake-kebab.core :as csk]
    [cemerick.url :refer [url]]
    [cheshire.core :as json]
    [clojure.string :as string]
    [clj-hl7-fhir.util :as util])
  (:import
    (java.util Date)
    (clojure.lang ExceptionInfo)))

; HACK: using this dynamic/"with"-wrapping type of API design is arguably a "lazy" design.
;       in the future I intend to explore reworking the API so as to not require this if
;       authentication/http header support is needed, but I didn't want to get too held
;       up on it right now. the problem at the moment is that passing extra HTTP request
;       info to the main FHIR operation functions is that it only works well in the simple
;       cases. usage of functions like fetch-next-page, fetch-all and
;       get-relative-resource becomes a little bit messy (have to pass in all this info
;       where before none of it was necessary... kind of gross in my opinion, would rather
;       come up with something cleaner if at all possible)

(def ^:dynamic *options* nil)

(defmacro with-options
  "wraps code that performs FHIR operations so that each FHIR operation runs with some
   extra options, such as HTTP authentication information, extra HTTP headers, etc.

   HTTP Authentication:
     specify one of :basic-auth, :digest-auth, :oauth. these should be specified
     in the same manner as clj-http expects (see the clj-http docs for more info).
     this authentication info will be added to all FHIR HTTP requests inside this
     block

   HTTP Headers:
     specify a map of headers under :headers. any headers specified in this way
     will be added as-is to any FHIR HTTP requests inside this with block

   Untrusted / Self-signed SSL Certificates
     if you need to send FHIR requests to a server that is not using a trusted
     SSL cert, you can specify ':insecure? true' in the options
   "
  [options & body]
  `(binding [*options* (select-keys ~options [:basic-auth :digest-auth :oauth-token :headers :insecure?])]
     ~@body))

(defn- get-base-http-req-params
  []
  (merge
    (if (:insecure? *options*) {:insecure? true})
    (select-keys *options* [:basic-auth :digest-auth :oauth-token])
    (if (map? (:headers *options*)) {:headers (:headers *options*)})))

(defn- ->fhir-resource-name [x]
  (name (csk/->PascalCase x)))

(defn- fhir-response? [response]
  (and (map? response)
       (.contains (get-in response [:headers "Content-Type"]) "application/json+fhir")))

(defn- fhir-request [type base-url resource-url & {:keys [params body params-as-body? follow-location?]}]
  (let [query            (util/map->query-string params)
        url              (util/build-url base-url resource-url (if-not params-as-body? query))
        body             (if params-as-body? query body)
        follow-location? (if (nil? follow-location?) true follow-location?)
        http-req-params  (get-base-http-req-params)]
    (try
      (let [response      (case type
                            :get       (util/http-get-json url http-req-params)
                            :form-post (util/http-post-form url http-req-params body)
                            :post      (util/http-post-json url http-req-params body)
                            :put       (util/http-put-json url http-req-params body)
                            :delete    (util/http-delete-json url http-req-params body))
            response-body (:body response)
            location      (get-in response [:headers "Location"])]
        (if location
          (if follow-location?
            (-> (util/http-get-json location http-req-params)
                :body
                (json/parse-string true))
            (if (fhir-response? response)
              (json/parse-string response-body true)
              location))
          (if (fhir-response? response)
            (json/parse-string response-body true)
            response-body)))
      (catch ExceptionInfo ex
        (let [{:keys [status body] :as response} (ex-data ex)
              fhir-resource-response?            (fhir-response? response)]
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
             (string/join ".")
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
         (string/join ","))

    (map? value)
    (str (:namespace value) "|" (format-search-value (:value value)))

    (instance? Date value)
    (util/->timestamp value)

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

(defn- strip-query-params [url]
  (let [pos (.indexOf url "?")]
    (if-not (= -1 pos)
      (subs url 0 pos)
      url)))

(defn- strip-base-url [url server-url]
  (if (.startsWith url server-url)
    (let [stripped-url (subs url (count server-url))]
      (if (= \/ (first stripped-url))
        (subs stripped-url 1)
        stripped-url))
    url))

(defn- format-resource-url-type [url-path-parts keywordize?]
  (if keywordize?
    (-> url-path-parts first csk/->kebab-case keyword)
    (-> url-path-parts first ->fhir-resource-name)))

(defn parse-relative-url
  "parses a relative FHIR resource URL, returning a map containing each of the discrete
   components of the URL (resource type, id, version number). if the optional
   keywordize? arg is true, then returned resource type names will be turned into a
   \"kebab case\" keyword (as opposed to a camelcase string which is the default). if
   the URL cannot be parsed, returns nil"
  [relative-url & [keywordize?]]
  (let [parts (-> (strip-query-params relative-url)
                  (string/split #"/"))]
    (cond
      (= 2 (count parts))
      {:type (format-resource-url-type parts keywordize?)
       :id   (second parts)}

      (and (= 4 (count parts))
           (= "_history" (nth parts 2)))
      {:type    (format-resource-url-type parts keywordize?)
       :id      (second parts)
       :version (last parts)})))

(defn parse-absolute-url
  "parses an absolute FHIR resource URL, returning a map containing each of the discrete
   components of the URL (resource type, id, version number). if the optional
   keywordize? arg is true, then returned resource type names will be turned into a
   \"kebab case\" keyword (as opposed to a camelcase string which is the default). if
   the URL cannot be parsed, returns nil."
  [absolute-url & [keywordize?]]
  (let [{:keys [path]} (url absolute-url)
        parts          (string/split path #"/")
        has-version?   (= "_history" (second (reverse parts)))]
    (cond
      (and (> (count parts) 4)
           (= "_history" (util/second-last parts))
           has-version?)
      (let [versioned-url-parts (take-last 4 parts)]
        {:type    (format-resource-url-type versioned-url-parts keywordize?)
         :id      (second versioned-url-parts)
         :version (last parts)})

      (and (> (count parts) 2)
           (not has-version?))
      (let [no-version-url-parts (take-last 2 parts)]
        {:type (format-resource-url-type no-version-url-parts keywordize?)
         :id   (second no-version-url-parts)}))))

(defn absolute-url?
  "returns true if the passed URL is an absolute URL, false if not. if the value
   passed in is not a string (or an empty string) an exception is thrown."
  [^String resource-url]
  (if (and (string? resource-url)
           (not (string/blank? resource-url)))
    (boolean
      (try
        (url resource-url)
        (catch Exception ex)))
    (throw (new Exception "Invalid URL or non-string value."))))

(defn parse-url
  "parses a FHIR resource URL returning a map containing each of the discrete
   components of the URL (resource type, id, version number). if the optional
   keywordize? arg is true, then returned resource type names will be turned into
   a \"kebab case\" keyword (as opposed to a camelcase string which is the default).
   if the URL cannot be parsed, returns nil.

   this function will automatically determine if the URL is relative or absolute
   and will try to parse it appropriately. you should probably just use this
   function all the time when parsing FHIR resource URLs."
  [resource-url & [keywordize?]]
  (if (absolute-url? resource-url)
    (parse-absolute-url resource-url keywordize?)
    (parse-relative-url resource-url keywordize?)))

(defn absolute->relative-url
  "turns an absolute FHIR resource URL into a relative one."
  [absolute-url]
  (if-let [{:keys [type id version]} (parse-absolute-url absolute-url)]
    (if version
      (str type "/" id "/_history/" version)
      (str type "/" id))))

(defn relative->absolute-url
  "combines a base URL to a FHIR server and a relative FHIR resource URL into an
   absolute resource URL."
  [base-url relative-url]
  (if-not (or (string/blank? base-url)
              (string/blank? relative-url))
    (-> (url base-url relative-url)
        (.toString))))

(defn collect-resources
  "returns a sequence containing all of the resources contained in the given bundle.
   deleted resources listed in the bundle will not be included in the returned
   sequence (they have no :content)

  reference:
  bundles: http://hl7.org/implement/standards/fhir/extras.html#bundle"
  [bundle]
  (validate-bundle! bundle)
  (->> bundle
       :entry
       (map :content)
       (remove nil?)))

(defn get-bundle-next-page-url
  "returns the 'next' bundle URL from the given FHIR bundle. useful for paged
   search results. throws an exception if the value passed is not a valid FHIR
   bundle."
  [bundle]
  (validate-bundle! bundle)
  (->> (:link bundle)
       (filter #(= "next" (:rel %)))
       (first)
       :href))

(defn get-base-url-from-bundle
  "returns the base-url from the given FHIR bundle. throws an exception if the
   value passed is not a valid FHIR bundle."
  [bundle]
  (validate-bundle! bundle)
  (->> (:link bundle)
       (filter #(= "fhir-base" (:rel %)))
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
    (let [http-req-params (get-base-http-req-params)]
      (util/http-get-json next-url http-req-params))))

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

(defn find-resource-in
  "finds and returns a resource contained in the given bundle, identified by a
   relative or absolute resource URL. if not found, nil is returned. throws an
   exception if the bundle and/or url supplied is invalid.

   reference:
   bundles: http://hl7.org/implement/standards/fhir/extras.html#bundle"
  [bundle resource-url]
  (when bundle
    (validate-bundle! bundle)
    (let [base-url (get-base-url-from-bundle bundle)
          search-url (if (absolute-url? resource-url)
                       resource-url
                       (relative->absolute-url base-url resource-url))]
      (->> (:entry bundle)
           (filter #(= search-url (:id %)))
           (first)))))

(defn get-contained
  "returns a resource contained in a parent resource, where the contained resource
   is identified by an internal reference id.

   reference:
   contained resources: http://www.hl7.org/implement/standards/fhir/references.html#contained"
  [containing-resource ref-id]
  (if-not (string/blank? ref-id)
    (if-let [parsed-id (if (.startsWith ref-id "#") (subs ref-id 1))]
      (->> (:contained containing-resource)
           (filter #(= parsed-id (:id %)))
           (first)))))

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
     (get-resource base-url (apply util/join-paths url-components)))))

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
  [base-url type id & params]
  (let [resource-name (->fhir-resource-name type)
        url-components ["/" resource-name]]
    (fhir-request :get
      base-url
      (apply util/join-paths url-components)
      :params (merge
                {:_id id}
                (util/build-params-map params)))))

(defn history
  "returns a bundle containing the history of a single FHIR resource. note that this history can
   include deletions as well, and these entries are not in a format parseable as a normal FHIR
   resource. as a result, using a function like collect-resources on the returned bundle is not
   generally recommended. if the resource could not be found, a bundle containing zero entries is
   returned. an exception is thrown if an error response is received.

   because some resources may have a large history, the bundle's contents may be paged. use the
   helper functions fetch-next-page and fetch-all to work through all returned pages.

   reference:
   history: http://hl7.org/implement/standards/fhir/http.html#history"
  [base-url type id & params]
  (let [resource-name (->fhir-resource-name type)
        url-components ["/" resource-name id "_history"]]
    (fhir-request :get
      base-url
      (apply util/join-paths url-components)
      :params (util/build-params-map params))))

(defn search
  "searches for resources on a FHIR server. multiple parameters are ANDed together. use of the search
   operator helper functions is encouraged to ensure proper escaping/encoding of search parameters.
   the results of this function can be passed to fetch-next-page or fetch-all to collect resources
   returned in paged search results easier. an exception is thrown if an error response is received.

   to overcome HTTP GET query size limitations that could be an issue for search operations with
   a large number of parameters, all search requests are submitted as
   application/x-www-form-urlencoded HTTP POST requests.

   reference:
   search: http://hl7.org/implement/standards/fhir/http.html#search"
  [base-url type where & params]
  (let [resource-name  (->fhir-resource-name type)
        url-components ["/" resource-name "/_search"]]
    (fhir-request :form-post
      base-url
      (apply util/join-paths url-components)
      :params-as-body? true
      :params (merge
                (search-params->query-map where)
                (util/build-params-map params)))))

(defn search-and-fetch
  "same as search, but automatically fetches all pages of resources returning a single bundle
   that contains all search results. an exception is thrown if an error response is received."
  [base-url type where & params]
  (fetch-all
    (search base-url type where params)))

(defn create
  "creates a new resource. if the creation succeeded, then the new resource is returned,
   unless the return-resource? argument is false, in which case the url to the new
   resource is returned (which will contain the resource id). if a 'Location' header
   was not set in the response, nil is returned on success. throws an exception if an
   error was received.

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
      (apply util/join-paths uri-components)
      :body resource
      :follow-location? return-resource?)))

(defn update
  "updates an existing resource. if the update succeeded, then the updated resource is
   returned, unless the return-resource? argument is false, in which case the url to
   the updated resource is returned (which will contain the resource id and version
   number). if a 'Location' header was not set in the response, nil is returned on
   success. throws an exception if an error response was received.

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
      (apply util/join-paths uri-components)
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
      (apply util/join-paths uri-components))))

(defn deleted?
  "checks if a resource has been deleted or not. this is based on FHIR servers returning
   an HTTP 410 response when trying to retrieve a resource that has been deleted."
  [base-url type id]
  (let [resource-name  (->fhir-resource-name type)
        url-components ["/" resource-name id]
        relative-url   (apply util/join-paths url-components)]
    (try
      (fhir-request :get
                    base-url
                    relative-url)
      ; not deleted
      false
      (catch ExceptionInfo ex
        (let [http-status (:status (ex-data ex))]
          (cond
            (= http-status 410) true
            (= http-status 404) false
            :else               (throw ex)))))))

(defn transaction
  "creates/updates/deletes resources specified in a bundle. if the entire transaction
   succeeded, then a bundle is returned containing changed resources. some servers
   may also return an additional OperationOutcome resource with additional information
   about the transaction. throws an exception if an error response was received.

   reference:
   http://hl7.org/implement/standards/fhir/http.html#transaction"
  [base-url bundle]
  (if-not (bundle? bundle)
    (throw (Exception. "Not a valid FHIR bundle")))
  (fhir-request :post
    base-url
    "/"
    :body bundle))

(defmulti get-extension-value
  "returns the value of a FHIR resource extension. the 'extension' argument should be
   a single extension element that includes a url key identifying the extension.
   returns nil if the extension value could not be found or the extension type
   is not recognized.

   reference:
   extensions: http://hl7.org/implement/standards/fhir/extensibility.html
   extension elements: http://hl7.org/implement/standards/fhir/extensibility.html#extension"
  (fn [extension]
    (->> (keys extension)
         (remove #(= :url %))
         (first))))

; TODO: this is currently *NOT* a complete list of all possible extension types!
;       need to fill this out more ....

(defmethod get-extension-value :valueInteger [extension]
  (:valueInteger extension))

(defmethod get-extension-value :valueDecimal [extension]
  (:valueDecimal extension))

(defmethod get-extension-value :valueDateTime [extension]
  (util/parse-timestamp (:valueDateTime extension)))

(defmethod get-extension-value :valueDate [extension]
  (util/parse-date (:valueDate extension)))

(defmethod get-extension-value :valueInstant [extension]
  (util/parse-timestamp (:valueInstant extension)))

(defmethod get-extension-value :valueString [extension]
  (:valueString extension))

(defmethod get-extension-value :valueUri [extension]
  (:valueUri extension))

(defmethod get-extension-value :valueBoolean [extension]
  (boolean (:valueBoolean extension)))

(defmethod get-extension-value :valueCode [extension]
  (:valueCode extension))

(defmethod get-extension-value :valueCoding [extension]
  (get-in extension [:valueCoding :code]))

(defmethod get-extension-value :valueResource [extension]
  (get-in extension [:valueResource :reference]))

(defmethod get-extension-value :extension [extension]
  (get-extension-value (:extension extension)))

(defmethod get-extension-value :default [_]
  ; TODO: maybe better to throw an exception? if it's not recognized that probably is
  ;       a bad thing and indicates a problem with the data format ... ?
  nil)

(defn get-extension
  "given a set of one or more extension elements, returns the value for the extension
   matching the extension URL given, or nil if not found.

   reference:
   extensions: http://hl7.org/implement/standards/fhir/extensibility.html
   extension elements: http://hl7.org/implement/standards/fhir/extensibility.html#extension"
  [extension-values extension-url]
  (->> extension-values
       (filter #(= extension-url (:url %)))
       (first)
       (get-extension-value)))

;(def server-url "http://fhir2.healthintersections.com.au/open")
;(def server-url "http://spark.furore.com/fhir")
;(def server-url "http://fhirtest.uhn.ca/baseDstu2")
