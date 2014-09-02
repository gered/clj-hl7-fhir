(ns clj-hl7-fhir.util
  (:import (java.util TimeZone Date)
           (java.text SimpleDateFormat))
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [cemerick.url :refer [url url-encode]]
            [cheshire.core :as json]))

(def tz (TimeZone/getDefault))
(def iso8601-timestamp "yyyy-MM-dd'T'HH:mm:ssXXX")
(def iso8601-local-timestamp "yyyy-MM-dd'T'HH:mm:ss")
(def iso8601-date "yyyy-MM-dd")

(defn format-date [^Date date ^String format]
  (if date
    (let [df (SimpleDateFormat. format)]
      (.setTimeZone df tz)
      (.format df date))))

(defn ->timestamp
  "returns an ISO8601 formatted date/time string for the given date object"
  [^Date date]
  (format-date date iso8601-timestamp))

(defn ->local-timestamp
  "returns an ISO8601 formatted date/time string for the given date object. the timestamp
   will not include any timezone information and so is appropriate for local timezone
   date/times only."
  [^Date date]
  (format-date date iso8601-local-timestamp))

(defn ->date
  "returns an ISO8601 formatted date string for the given date object"
  [^Date date]
  (format-date date iso8601-date))

(defn parse-formatted-timestamp [^String timestamp ^String format]
  (if-not (str/blank? timestamp)
    (let [df (SimpleDateFormat. format)]
      (.setTimeZone df tz)
      (.parse df timestamp))))

(defn parse-timestamp
  "parses an ISO8601 formatted date/time string with timezone information, returning an
   equivalent Date object"
  [^String timestamp]
  (parse-formatted-timestamp timestamp iso8601-timestamp))

(defn parse-local-timestamp
  "parses an ISO8601 formatted date/time string without timezone information (appropriate
   for local timezone date/times only), returning an equivalent Date object"
  [^String timestamp]
  (parse-formatted-timestamp timestamp iso8601-local-timestamp))

(defn parse-date
  "parses an ISO8601 formatted date string, returning an equivalent Date object"
  [^String date]
  (parse-formatted-timestamp date iso8601-date))

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

(defn- http-request [f url & [params]]
  (f url (merge {:accept "application/json+fhir"} params)))

(defn http-get-json [url params]
  (http-request http/get url params))

(defn http-post-json [url params body]
  (http-request
    http/post url
    (merge
      {:content-type "application/json+fhir"}
      (cond
        (map? body)    {:body (json/generate-string body)}
        (string? body) {:body body})
      params)))

(defn http-post-form [url params body]
  (http-request
    http/post url
    (merge
      {:content-type "application/x-www-form-urlencoded"}
      (cond
        (map? body)    {:body (json/generate-string body)}
        (string? body) {:body body})
      params)))

(defn http-put-json [url params body]
  (http-request
    http/put url
    (merge
      {:content-type "application/json+fhir"}
      (cond
        (map? body)    {:body (json/generate-string body)}
        (string? body) {:body body})
      params)))

(defn http-delete-json [url params body]
  (http-request
    http/delete url
    (merge
      (if body {:body body})
      params)))

(defn second-last [coll]
  (if (and (seq coll)
           (> (count coll) 1))
    (nth coll (- (count coll) 2))))
