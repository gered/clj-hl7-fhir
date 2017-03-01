(defproject clj-hl7-fhir "0.3.1"
  :description  "HL7 FHIR JSON client"
  :url          "https://github.com/gered/clj-hl7-fhir"
  :license      {:name "Apache License, Version 2.0"
                 :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :dependencies [[cheshire "5.7.0"]
                 [clj-http "2.3.0"]
                 [com.cemerick/url "0.1.1"]
                 [camel-snake-kebab "0.4.0"]]

  :profiles     {:provided
                 {:dependencies [[org.clojure/clojure "1.8.0"]]}

                 :test
                 {:dependencies [[pjstadig/humane-test-output "0.8.1"]]
                  :injections   [(require 'pjstadig.humane-test-output)
                                 (pjstadig.humane-test-output/activate!)]}})
