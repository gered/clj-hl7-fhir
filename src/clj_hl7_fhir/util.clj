(ns clj-hl7-fhir.util
  (:import (java.util TimeZone Date)
           (java.text SimpleDateFormat))
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [cemerick.url :refer [url url-encode]]
            [cheshire.core :as json]))

(def tz (TimeZone/getDefault))
(def iso8601-timestamp "yyyy-MM-dd'T'HH:mm:ssXXX")
(def iso8601-date "yyyy-MM-dd")

(defn format-date [^Date date ^String format]
  (if date
    (let [df (SimpleDateFormat. format)]
      (.setTimeZone df tz)
      (.format df date))))

(defn ->iso-timestamp
  "returns an ISO8601 formatted date/time string for the given date object"
  [^Date date]
  (format-date date iso8601-timestamp))

(defn ->iso-date
  "returns an ISO8601 formatted date string for the given date object"
  [^Date date]
  (format-date date iso8601-date))

(defn map->query-string [m]
  (->> m
       (reduce
         (fn [query-values [param-name value]]
           (concat
             query-values
             (map
               #(str (url-encode (name param-name))
                     "="
                     (url-encode (str %)))
               (if (vector? value) value [value]))))
         [])
       (interpose "&")
       (flatten)
       (apply str)))

(defn kv-vector->query
  "should really be using cemerick.url/map->query in all cases except when you need 2 values under the
   name name in the query string (such as with FHIR's date 'between' search support)"
  [kvs]
  (some->> (partition 2 kvs)
           (map (fn [[k v]]
                  [(url-encode (name k))
                   "="
                   (url-encode (str v))]))
           (interpose "&")
           flatten
           (apply str)))

(defn join-paths [& paths]
  (as-> paths x
        (remove nil? x)
        (str/join "/" x)
        (str/replace x #"(/+)" "/")))

(defn build-url [base-url path & [params]]
  (-> (url base-url)
      (update-in [:path] (fn [existing-path]
                           (join-paths existing-path path)))
      (assoc :query params)
      (str)))

(defn http-get-json [url]
  (-> (http/get url {:accept "application/json+fhir"})
      :body
      (json/parse-string true)))