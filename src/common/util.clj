(ns common.util
  (:import [com.amazonaws.services.sns.model PublishRequest
            CreatePlatformEndpointRequest]
           [com.twilio.sdk TwilioRestClient]
           [org.apache.http.message BasicNameValuePair]
           [java.util ArrayList])
  (:require [clojure.string :as s]
            [clojure.walk :refer [postwalk]]
            [clj-aws.core :as aws]
            [clj-aws.sns :as sns]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [clj-time.format :as time-format]
            [postal.core :as postal]
            [common.config :as config]
            [common.db :refer [conn]]
            [ardoq.analytics-clj :as segment]
            [version-clj.core :as version]))

(defmacro !
  "Keeps code from running during compilation."
  [& body]
  `(when-not *compile-files*
     ~@body))

(defmacro only-prod
  "Only run this code when in production mode."
  [& body]
  `(when (= config/db-user "purplemasterprod")
     ~@body))

(defmacro catch-notify
  "A try catch block that emails me exceptions."
  [& body]
  `(try ~@body
        (catch Exception e#
          (only-prod (send-email {:to "chris@purpledelivery.com"
                                  :subject "Purple - Exception Caught"
                                  :body (str e#)})))))

(defmacro unless-p
  "Use x unless the predicate is true for x, then use y instead."
  [pred x y]
  `(if-not (~pred ~x)
     ~x
     ~y))

(defn in? 
  "true if seq contains elm"
  [seq elm]  
  (some #(= elm %) seq))

(defn not-nil-vec
  [k v]
  (when-not (nil? v) [k v]))

(defn ver<
  "Same as <, but works on version number strings (e.g., 2.13.0)."
  [x y]
  (= -1 (version/version-compare x y)))

(defn five-digit-zip-code
  [zip-code]
  (subs zip-code 0 5))

(defn cents->dollars
  "Integer of cents -> Double of dollars."
  [x]
  (if (zero? x)
    0
    (double (/ x 100))))

(defn cents->dollars-str
  "To display an integer of cents as string in dollars with a decimal."
  [x]
  (let [y (str x)]
    (->> (split-at (- (count y) 2) y)
         (interpose ".")
         flatten
         (apply str))))

(def time-zone (time/time-zone-for-id "America/Los_Angeles"))

(defn unix->DateTime
  [x]
  (time-coerce/from-long (* 1000 x)))

(def full-formatter (time-format/formatter "M/d h:mm a"))

(defn unix->full
  "Convert integer unix timestamp to formatted date string."
  [x]
  (time-format/unparse
   (time-format/with-zone full-formatter time-zone)
   (unix->DateTime x)))

(def fuller-formatter (time-format/formatter "M/d/y h:mm a"))

(defn unix->fuller
  "Convert integer unix timestamp to formatted date string."
  [x]
  (time-format/unparse
   (time-format/with-zone fuller-formatter time-zone)
   (unix->DateTime x)))

(def hour-formatter (time-format/formatter "H"))

(defn unix->hour-of-day
  "Convert integer unix timestamp to integer hour of day 0-23."
  [x]
  (Integer.
   (time-format/unparse
    (time-format/with-zone hour-formatter time-zone)
    (unix->DateTime x))))

(def minute-formatter (time-format/formatter "m"))

(defn unix->minute-of-hour
  "Convert integer unix timestamp to integer minute of hour."
  [x]
  (Integer.
   (time-format/unparse
    (time-format/with-zone minute-formatter time-zone)
    (unix->DateTime x))))

(defn unix->minute-of-day
  "How many minutes (int) since beginning of day?"
  [x]
  (+ (* (unix->hour-of-day x) 60)
     (unix->minute-of-hour x)))

(def hmma-formatter (time-format/formatter "h:mm a"))
(defn unix->hmma
  "Convert integer unix timestamp to formatted date string."
  [x]
  (time-format/unparse
   (time-format/with-zone hmma-formatter time-zone)
   (unix->DateTime x)))

(defn minute-of-day->hmma
  "Convert number of minutes since the beginning of today to a unix timestamp."
  [m]
  (unix->hmma
   (+ (* m 60)
      (-> (time/date-time 1976) ;; it'll be wrong day but same hmma
          (time/from-time-zone time-zone)
          time-coerce/to-long
          (quot 1000)))))

(defn map->java-hash-map
  "Recursively convert Clojure PersistentArrayMap to Java HashMap."
  [m]
  (postwalk #(unless-p map? % (java.util.HashMap. %)) m))

(defn unix->DateTime
  [x]
  (time-coerce/from-long (* 1000 x)))

(defn rand-str
  [ascii-codes length]
  (apply str (repeatedly length #(char (rand-nth ascii-codes)))))

(defn rand-str-alpha-num
  [length]
  (rand-str (concat (range 48 58)  ;; 0-9
                    (range 65 91)  ;; A-Z
                    (range 97 123) ;; a-z
                    )
            length))

(defn split-on-comma [x] (s/split x #","))

(defn new-auth-token []
  (rand-str-alpha-num 128))

(defn get-event-time
  "Get time of event from event log as unix timestamp Integer.
  If event hasn't occurred yet, then nil."
  [event-log event]
  (some-> event-log
          (#(unless-p s/blank? % nil))
          (s/split #"\||\s")
          (->> (apply hash-map))
          (get event)
          (Integer.)))

;; could this be an atom that is set to nil and initilized later?
(when-let [segment-write-key (System/getProperty "SEGMENT_WRITE_KEY")]
  (def segment-client (segment/initialize
                       segment-write-key)))

;; Amazon SNS (Push Notifications)
(when-let [aws-access-key-id (System/getProperty "AWS_ACCESS_KEY_ID")]
  (def sns-client
    (sns/client (aws/credentials aws-access-key-id
                                 (System/getProperty "AWS_SECRET_KEY"))))
  (.setEndpoint sns-client "https://sns.us-west-2.amazonaws.com"))

(defn send-email [message-map]
  (try (postal/send-message config/email
                            (assoc message-map
                                   :from (str "Purple Services Inc <"
                                              config/email-from-address
                                              ">")))
       {:success true}
       (catch Exception e
         {:success false
          :message "Message could not be sent to that address."})))

(defn sns-create-endpoint
  [client device-token user-id sns-app-arn]
  (try
    (let [req (CreatePlatformEndpointRequest.)]
      ;; (.setCustomUserData req "no custom data")
      (.setToken req device-token)
      (.setPlatformApplicationArn req sns-app-arn)
      (.getEndpointArn (.createPlatformEndpoint client req)))
    (catch Exception e
      (only-prod (send-email {:to "chris@purpledelivery.com"
                              :subject "Purple - Error"
                              :body (str "AWS SNS Create Endpoint Exception: "
                                         (.getMessage e)
                                         "\n\n"
                                         "user-id: "
                                         user-id)}))
      "")))



(defn sns-publish
  [client target-arn message]
  (try
    (let [req (PublishRequest.)
          is-gcm? (.contains target-arn "GCM/Purple")]
      (.setMessage req (if is-gcm?
                         (str "{\"GCM\": \"{ "
                              "\\\"data\\\": { \\\"message\\\": \\\""
                              message
                              "\\\" } }\"}")
                         message))
      (when is-gcm? (.setMessageStructure req "json"))
      (.setTargetArn req target-arn)
      (.publish client req))
    (catch Exception e
      (only-prod (send-email {:to "chris@purpledelivery.com"
                              :subject "Purple - Error"
                              :body (str "AWS SNS Publish Exception: "
                                         (.getMessage e)
                                         "\n\n"
                                         "target-arn: "
                                         target-arn
                                         "\nmessage: "
                                         message)})))))

;; Twilio (SMS & Phone Calls)
(when config/twilio-account-sid
  (def twilio-client (TwilioRestClient. config/twilio-account-sid
                                        config/twilio-auth-token))
  (def twilio-sms-factory (.getMessageFactory (.getAccount twilio-client)))
  (def twilio-call-factory (.getCallFactory (.getAccount twilio-client))))

(defn send-sms
  [to-number message]
  (catch-notify
   (.create
    twilio-sms-factory
    (ArrayList. [(BasicNameValuePair. "Body" message)
                 (BasicNameValuePair. "To" to-number)
                 (BasicNameValuePair. "From" config/twilio-from-number)]))))

(defn make-call
  [to-number call-url]
  (catch-notify
   (.create
    twilio-call-factory
    (ArrayList. [(BasicNameValuePair. "Url" call-url)
                 (BasicNameValuePair. "To" to-number)
                 (BasicNameValuePair. "From" config/twilio-from-number)]))))

(defn timestamp->unix-epoch
  "Convert a java.sql.Timestamp timestamp to unix epoch seconds"
  [timestamp]
  (/ (.getTime timestamp) 1000))

(defn convert-timestamp
  "Replace :timestamp_created value in m with unix epoch seconds"
  [m]
  (assoc m :timestamp_created (timestamp->unix-epoch (:timestamp_created m))))

(defn convert-timestamps
  "Replace the :timestamp_created value with unix epoch seconds in each map of
  vector"
  [v]
  (map convert-timestamp v))
