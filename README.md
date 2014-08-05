# clj-hl7-fhir

[HL7 FHIR](http://hl7.org/implement/standards/fhir/) JSON client for use in Clojure applications.
This is a fairly low-level wrapper over the HL7 FHIR [RESTful API](http://hl7.org/implement/standards/fhir/http.html)
and does _not_ include any model classes (or anything of the sort) for the various HL7 resources.
FHIR API calls wrapped by this library work with JSON data represented as Clojure EDN data.

The primary goal of clj-hl7-fhir is to make getting data into and out of an HL7 FHIR server as
simple as possible, without needing to know much about the RESTful API (the URL conventions working 
with HTTP status codes, reading back data from paged bundles, encoding search parameters, etc). 
How you create and/or read the HL7 data and what you do with it is beyond the scope of this library.

## Leiningen

```clojure
[clj-hl7-fhir "0.1"]
```
	
## TODO

This library is still early along in development, and some important features are missing at the moment:

* Authentication support
* Remaining API calls
  * [history](http://hl7.org/implement/standards/fhir/http.html#history)
  * [validate](http://hl7.org/implement/standards/fhir/http.html#validate)
  * [transaction](http://hl7.org/implement/standards/fhir/http.html#transaction)
  * [conformance](http://hl7.org/implement/standards/fhir/http.html#conformance)
  
## Limitations

This library only supports HL7 FHIR servers which support JSON. The FHIR specification requires that
servers support XML, but JSON support is optional. There are no immediate or long-term plans to add
XML support to this library.

All API requests sent by this library include an `Accept` HTTP header with the value
`application/json+fhir` to indicate to the server what format requests and responses
are to be in. This is handled automatically, and does not need to be specified by
your application's code. As a result the optional `_format` parameter is not needed.
	
## Usage

Most of the basic RESTful API operations are supported currently.

* [read](http://hl7.org/implement/standards/fhir/http.html#read)
* [vread](http://hl7.org/implement/standards/fhir/http.html#vread)
* [search](http://hl7.org/implement/standards/fhir/http.html#search)
* [create](http://hl7.org/implement/standards/fhir/http.html#create)
* [update](http://hl7.org/implement/standards/fhir/http.html#update)
* [delete](http://hl7.org/implement/standards/fhir/http.html#delete)

All of the functions that wrap FHIR API calls are located in `clj-hl7-fhir.core`:

```clojure
(use 'clj-hl7-fhir.core)
```

### General Concepts

Most API functions take a `type` parameter which should be either a keyword or string
specifying the [FHIR resource](http://hl7.org/implement/standards/fhir/resourcelist.html) 
that the operation is dealing with. The resource can be specified either in "camel case"
(e.g. `"Patient"` or `"DiagnosticReport"`) or in "kebab case", which is what most Clojure
code uses (e.g. `:patient` or `:diagnostic-report`). FHIR servers will typically return
an error if you pass in a resource type that is not recognized.

Return values will almost always (if not nil) be a FHIR resource or bundle ([as described here](http://hl7.org/implement/standards/fhir/json.html)).
The main difference is that a bundle can contain any number of resources (even zero) and 
also contains a bit of extra metadata about each resource contained inside. The functions
which return resources as bundles will always return a bundle as long as an error did
not occur, however the bundle may contain zero resources. The functions which return
raw resources will either return a resource or nil on success. Exceptions to this
will be noted in the individual function documentation (e.g. `create` and `update`).

### base-url

All core API functions take a `base-url` parameter. This is the [Service Root URL](http://hl7.org/implement/standards/fhir/http.html#root)
for which all API calls are made to.

For example, to use [UHN's HAPI FHIR test server](http://fhirtest.uhn.ca/):

```clojure
(def server-url "http://fhirtest.uhn.ca/base")
```

### read / vread

There are a couple options for reading single resources by ID. 

`get-resource` takes the resource type and ID and returns a FHIR resource. 
Alternatively, you can specify a relative resource URL instead of separate type
and ID arguments. You can also optionally include a specific version number to
retrieve.

`get-resource-bundle` works similarly to `get-resource`, except that it returns
a FHIR [bundle](http://www.hl7.org/implement/standards/fhir/extras.html#bundle) 
instead of a resource.

##### Reading via Relative URLs

Many FHIR resources will link to other resources using relative URLs. For example,
an Encounter resource is associated with a Patient resource and the link is
specified like so:

```clojure
{:resourceType "Encounter"

 ; ...
 
 :subject {:resource "Patient/1234"}
 
 ; ...
 
 }
```

Where `Patient/1234` is a [relative URL](http://www.hl7.org/implement/standards/fhir/references.html#atom-rel). 
`get-resource` can also accept a relative URL instead of the resource type and ID arguments.
This can sometimes be more convenient when reading resources related to a parent resource
that has already been retrieved.

##### Examples

```clojure
; reading a single resource by ID
(get-resource server-url :patient 37)
=> {:address
    [{:use "home"
      :line ["10 Duxon Street"]
      :city "VICTORIA"
      :state "BC"
      :zip "V8N 1Y4"
      :country "Can"}]
    :managingOrganization {:resource "Organization/1.3.6.1.4.1.12201"}
    :name [{:family ["Duck"] 
            :given ["Donald"]}]
    :birthDate "1980-06-01T00:00:00"
    :resourceType "Patient"
    :identifier
    [{:use "official"
      :label "UHN MRN 7000135"
      :system "urn:oid:2.16.840.1.113883.3.239.18.148"
      :value "7000135"
      :assigner {:resource "Organization/1.3.6.1.4.1.12201"}}]
    :telecom
    [{:system "phone" :use "home"}
     {:system "phone" :use "work"}
     {:system "phone" :use "mobile"}
     {:system "email" :use "home"}]
    :gender
    {:coding
     [{:system "http://hl7.org/fhir/v3/AdministrativeGender"
       :code "M"}]}
    :text
    {:status "generated"
     :div
     "<div><div class=\"hapiHeaderText\"> Donald <b>DUCK </b></div><table class=\"hapiPropertyTable\"><tbody><tr><td>Identifier</td><td>UHN MRN 7000135</td></tr><tr><td>Address</td><td><span>10 Duxon Street </span><br/><span>VICTORIA </span><span>BC </span><span>Can </span></td></tr><tr><td>Date of birth</td><td><span>01 June 1980</span></td></tr></tbody></table></div>"}}
     
; trying to read a non-existant resource
(get-resource server-url :patient 9001)
=> nil

; reading a specific version of a resource
(get-resource server-url :patient 1654 :version 3)
=> { 
    ; ... similar to the above example resource return value ... 
    }

; reading a resource via relative URL (this was taken from the 
; patient resource retrieved above)
(get-resource server-url "Organization/1.3.6.1.4.1.12201")
=> {:resourceType "Organization"
    ; ... full resource contents ommitted ...
    }

; trying to read an invalid resource
(get-resource server-url :foobar 42)
=> ExceptionInfo FHIR request failed: HTTP 400
```

### search

Searching for resources is performed via `search`. It returns a FHIR bundle containing
all the resources that matched the search parameters given. If you provide no search
parameters then all resources of the type given will be returned (though they will
be paged likely, as per the FHIR specs).

##### Search Parameters

Search parameters are specified as a vector, where each parameter should be defined
using the helper functions:

| Helper function | Description and usage example
| ----------------|------------------------------
| `eq` | Equals<br />`(eq :name "smith")`
| `lt` | Less then<br />`(lt :value 10)`
| `lte` | Less then or equal to<br />`(lte :date "2013-08-15")`
| `gt` | Greater then<br />`(gt :value 10)`
| `gte` | Greater then or equal to<br />`(gte :date "2013-08-15")`
| `between` | Between<br />`(between :date "2013-01-01" "2013-12-31")`

_Note that you can also use a plain old string for parameter names instead of keywords if you wish._

If a parameter value needs to include a namespace, you can use the `namespaced` helper function 
to properly encode this information in the search parameters:

```clojure
(eq :gender (namespaced "http://hl7.org/fhir/v3/AdministrativeGender" "M"))
```

###### Date Parameter Formatting

There are also a few helper functions in `clj-hl7-fhir.util` for converting `java.util.Date` objects
into properly formatted ISO date/time strings that match FHIR specifications:

| Function | Format | Example output
|----------|--------|---------------
| `->timestamp` | `yyyy-MM-dd'T'HH:mm:ssXXX` | `2014-08-05T10:49:37-04:00`
| `->local-timestamp` | `yyyy-MM-dd'T'HH:mm:ss` | `2014-08-05T10:49:37-04:00`
| `->date` | `yyyy-MM-dd` | `2014-08-05`

##### Search Results

As mentioned above, search results will be returned in a FHIR bundle, which contains a
vector of all the matching resources. For convenience, you can use `collect-resources`
to return a sequence of just the resources by passing/threading the results from
`search` into this function.

```clojure
(collect-resources
  (search server-url ...)
```

##### Paged Results

Larger search results will be [paged](http://hl7.org/implement/standards/fhir/http.html#paging).
Some helper functions are available to make working with paged search results easier:
* `fetch-next-page` takes a search result bundle and uses it to get and return the next page of search results. If there are no more pages of results, returns nil.
* `fetch-all` takes a search result bundle, and fetches all pages of search results, and then returns a bundle which contains the full list of match resources.
* `search-and-fetch` convenience function that is the same as doing: `(fetch-all (search ...))`. Takes the same arguments as `search`.

If you wish, you can specify `:_count max-results` in your call to `search` to specify an
arbitrary number of results per page. As per the FHIR specifications, the server is free to
return less then this number if it chooses to, but it will never return more then the amount
you specify here.


##### Examples

```clojure
; list all patients
; (http://server-url/Patient)
(search server-url :patient [])

; list all patients, but only show 5 per page of results.
; note that the above call (that does not specify a count) will also be paged too if
; there are a lot of results. the count parameter just lets you change the number of
; results per page to something else, it doesn't necessarily let you turn off paging.
; (http://fhirtest.uhn.ca/base/Patient?_count=5)
(search server-url :patient [] :_count 5)

; find all patients with name "dogie"
; (http://server-url/Patient?name=dogie)
(search server-url :patient [(eq :name "dogie")])

; find all female patients
; (http://server-url/Patient?gender=http%3A%2F%2Fhl7.org%2Ffhir%2Fv3%2FAdministrativeGender%7CF)
(search server-url :patient [(eq :gender (namespaced "http://hl7.org/fhir/v3/AdministrativeGender" "F"))])

; also works (depending on the exact data and server spec compliance)
; (http://server-url/Patient?gender=F)
(search server-url :patient [(eq :gender "F")])

; find all male patients with a birthdate before Jan 1, 1980
; (http://server-url/Patient?birthdate=%3C1980-01-01&gender=M)
(search server-url :patient [(eq :gender "M")
                             (lt :birthdate "1980-01-01")])
                             
; find all encounter (visit) resources for a patient specified by 
; identifier (MRN in this case)
; (http://server-url/Encounter?subject.identifier=7007482)
(search server-url :encounter [(eq :subject.identifier "7007482")])

; search using an invalid parameter (unrecognized by the server)
; (http://server-url/Patient?foobar=baz)
(search server-url :patient [(eq :foobar "baz")])
=> ExceptionInfo FHIR request failed: HTTP 400
```

### create

Adding new resources is a simple matter once you have a FHIR resource represented as a Clojure map.
Simply pass the resource to `create`. By default, if creation is successful the new resource is
returned. 

Optionally you can specify `:return-resource? false` to have `create` return a full URL to the 
newly created resource instead (this can be useful if you need the new resource's ID for example,
as the returned FHIR resource would not otherwise include this information).

`create` will throw an exception if the resource you pass is not a Clojure map that contains
a `:resourceType` key with a value that is anything other then `"Bundle"`).

##### Examples

```clojure
(def new-patient
  {:managingOrganization {:resource "Organization/1.3.6.1.4.1.12201"}
   :name [{:given ["Nurse"]
           :family ["Test"]}]
   :birthDate "1965-11-19T00:00:00-05:00"
   :resourceType "Patient"
   :identifier
   [{:assigner {:resource "Organization/1.3.6.1.4.1.12201"}
     :system "urn:oid:2.16.840.1.113883.3.239.18.148"
     :use "official"
     :value "7010168"
     :label "University Health Network MRN 7010168"}]
   :telecom
   [{:system "phone" :use "home" :value "(416)000-0000"}
    {:system "phone" :use "work"}
    {:system "phone" :use "mobile"}
    {:system "email" :use "home"}]
   :gender
   {:coding
    [{:system "http://hl7.org/fhir/v3/AdministrativeGender"
      :code "F"}]}
   :text {:div "<div/>"}}

; create a new resource. will return a map that should look almost identical to the 
; above (some servers may autogenerate the :text :div value, if so that value will 
; be included in the returned map of course)
(create server-url :patient new-patient)
=> {
    ; resource
    } 

; create a new resource, but only return the URL to the created resource
(create server-url :patient new-patient :return-resource? false)
=> "http://server-url/Patient/1234/_history/1"

; trying to create a resource with an invalid resource map
(create server-url :patient {:foo "bar"})
=> Exception Not a valid FHIR resource 

; trying to create a resource that the server rejects
; (exact HTTP status returned may vary from server to server unfortunately! some 
; servers do validation better then others and may return an HTTP 400 instead. 
; HTTP 422 is another result defined in the spec for an invalid/unusable resource)
(create server-url :patient {:resourceType "foobar" 
                             :foo "bar"})
=> ExceptionInfo FHIR request failed: HTTP 500
```

### update

Updating existing resources is accomplished via `update` which takes an ID along with
a FHIR resource map, similar to what you would provide with `create`. The ID of course
specifies the existing resource to be updated. By default, if the update is successful 
the newly updated resource is returned.

Optionally you can specify `:return-resource? false` to return a full URL to the 
updated resource instead (this can be useful if you need the resource's ID/version 
for example, as the returned FHIR resource would not otherwise include this 
information).

Additionally, you can limit updates to only proceed if the latest version of the
resource on the server matches a version number you specify by passing an extra 
`:version version-number` argument. If the latest version of the resource on the 
server does not match, the resource will not be updated and an exception is thrown.

`update` will throw an exception if the resource you pass is not a Clojure map that 
contains a `:resourceType` key with a value that is anything other then `"Bundle"`).

##### Examples

```clojure
(def updated-patient
  {:managingOrganization {:resource "Organization/1.3.6.1.4.1.12201"}
   :name [{:given ["Nurse"]
           :family ["Test"]}]
   :birthDate "1965-11-19T00:00:00-05:00"
   :resourceType "Patient"
   :identifier
   [{:assigner {:resource "Organization/1.3.6.1.4.1.12201"}
     :system "urn:oid:2.16.840.1.113883.3.239.18.148"
     :use "official"
     :value "7010168"
     :label "University Health Network MRN 7010168"}]
   :telecom
   [{:system "phone" :use "home" :value "(416)000-0000"}
    {:system "phone" :use "work" :value "555-555-5555"}
    {:system "phone" :use "mobile"}
    {:system "email" :use "home"}]
   :gender
   {:coding
    [{:system "http://hl7.org/fhir/v3/AdministrativeGender"
      :code "F"}]}
   :text {:div "<div/>"}}

; updates an existing resource. will return a map that should look almost identical to the 
; above (some servers may autogenerate the :text :div value, if so that value will be 
; included in the returned map of course)
(update server-url :patient 1234 updated-patient)
=> {
    ; resource
    } 

; updates an existing resource, but only return the URL to the updated resource
(update server-url :patient 1234 updated-patient)
=> "http://server-url/Patient/1234/_history/2"

; update an existing resource only if the version matches
(update server-url :patient 1234 updated-patient :version 1)
=> {
    ; resource
    } 

; NOTE: error responses are identical to clj-hl7-fhir.core/create. see examples for that
;       function for more information
```

### delete

Deleting existing resources is accomplished with `delete` which takes the ID of the
resource to be deleted. The return value will typically be `nil` on success, though
some servers may return an OperationOutcome resource that includes more details about
the successful deletion.

You can use the helper function `deleted?` to determine if a resource has been deleted
or not, since `get-resource` returns nil for both deleted resources and resources
which do not exist at all (an important distinction, as in FHIR a deleted resource
technically still exists under previous version numbers).

##### Examples

```clojure
; delete an existing patient
(delete server-url :patient 1654)
=> nil

; try to delete the same patient again. nothing happens
(delete server-url :patient 1654)
=> nil

; try to delete a non-existant patient
(delete server-url :patient 9001)
=> ExceptionInfo FHIR request failed: HTTP 404

; testing if a resource has been deleted or not
(deleted? server-url :patient 1654)
=> true

; testing if a non-existant resource has been deleted
(deleted? server-url :patient 9001)
=> false
```

### Error Handling

All API functions throw exceptions via `ex-info` when an unexpected error response is
returned from the HL7 FHIR server. An "unexpected error response" is anything that
is not defined to be part of the particular operation's successful result(s). e.g.
a "read" operation that returns an HTTP 400 or HTTP 500 status instead of HTTP 200.

When this type of response is encountered, an exception is thrown which will contain
the response, which can be obtained in your exception handler via `ex-data`. If the
response is detected to be a FHIR [OperationOutcome](http://www.hl7.org/implement/standards/fhir/operationoutcome.html)
resource, it will be parsed and set as the response, otherwise the raw response body 
is set in the exception.

```clojure
; trying to read an invalid resource
(get-resource server-url :foobar 42)
=> ExceptionInfo FHIR request failed: HTTP 400

; more detailed error information can be obtained via ex-data
(try
  (get-resource server-url :foobar 42)
  (catch Exception e
    (let [operation-outcome (ex-data e)]
      ; TODO: proper error handling goes here
      operation-outcome)))
=> {:status 400
    :fhir-resource? true
    :response
    {:resourceType "OperationOutcome"
     :text
     {:status "empty"
      :div
      "<div>No narrative template available for resource profile: http://hl7.org/fhir/profiles/OperationOutcome</div>"}
     :issue
     [{:severity "error"
       :details
       "Unknown resource type 'Foobar' - Server knows how to handle: [User, Condition, Supply, GVFVariant, Organization, Group, ValueSet, Coverage, ImmunizationRecommendation, Appointment, MedicationDispense, MedicationPrescription, Slot, AppointmentResponse, MedicationStatement, SequencingLab, Questionnaire, Composition, OperationOutcome, Conformance, Media, Other, Profile, DocumentReference, Immunization, Microarray, OrderResponse, ConceptMap, Practitioner, ImagingStudy, GVFMeta, CarePlan, Provenance, Device, Query, Order, Procedure, Substance, DiagnosticReport, Medication, MessageHeader, DocumentManifest, Availability, MedicationAdministration, Encounter, SecurityEvent, GeneExpression, SequencingAnalysis, List, DeviceObservationReport, Claim, FamilyHistory, Location, AllergyIntolerance, GeneticAnalysis, Observation, RelatedPerson, Specimen, Alert, Patient, Remittance, AdverseReaction, DiagnosticOrder]"}]}}
```

## License

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
