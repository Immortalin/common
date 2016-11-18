(ns common.coupons
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [common.config :as config]
            [common.db :refer [!select !insert mysql-escape-str]]
            [common.users :as users]
            [common.util :refer [in? split-on-comma rand-str
                                 rand-str-alpha-num coerce-double]]))

(defn gen-coupon-code []
  (rand-str (remove (set [65  ;; A  - removing vowels to avoid offensive words
                          69  ;; E
                          73  ;; I
                          79  ;; O
                          85  ;; U
                          48  ;; 0  - removing nums that look like chars
                          49  ;; 1
                          79  ;; O  - removing chars that look like nums
                          73]);; I
                    (concat (range 48 58)  ;; 0-9
                            (range 65 91)))  ;; A-Z
            5))

;; keep this idempotent!
(defn format-coupon-code
  "Format coupon code to consistent format."
  [code]
  (s/replace (s/upper-case code) #" " ""))

(defn get-coupon-by-code
  "Get a coupon from DB given its code (e.g., GAS15)."
  [db-conn code]
  (first (!select db-conn
                  "coupons"
                  ["*"]
                  {:code code})))

(defn get-license-plate-by-vehicle-id
  "Get the license of a vehicle given its id. Or nil."
  [db-conn vehicle-id]
  (some-> (!select db-conn
                   "vehicles"
                   ["license_plate"]
                   {:id vehicle-id})
          first
          :license_plate))

(defn is-owner?
  "Is user-id the owner of this coupon?"
  [c user-id]
  (= (:owner_user_id c) user-id))

(defn used-by-license-plate?
  "Has this coupon been used by this license plate?"
  [c license-plate]
  (-> (:used_by_license_plates c)
      split-on-comma
      (in? license-plate)))

(defn used-by-user-id?
  "Has this coupon been used by this user ID?"
  [c user-id]
  (-> (:used_by_user_ids c)
      split-on-comma
      (in? user-id)))

(defn num-uses
  "How many times has this coupon been used?"
  [c]
  (-> (:used_by_user_ids c)
      split-on-comma
      (#(filter (complement s/blank?) %))
      count))

(defn under-max-uses?
  "Has this coupon been used less than the maximum number of times?"
  [c]
  (< (num-uses c) (:max_uses c)))

(defn has-ordered?
  "Has this license plate or user-id been used in a past order?"
  [db-conn license-plate user-id]
  (seq (!select db-conn "orders" [:id] {}
                :custom-where
                (str "(license_plate = \""
                     (mysql-escape-str license-plate)
                     "\" OR user_id = \""
                     (mysql-escape-str user-id)
                     "\") AND status != \"cancelled\""))))

(defn valid-zip-code?
  "Can this coupon code be used in this zip-code?"
  [c zip-code]
  (if (s/blank? (:zip_codes c))
    true ;; blank means: any zip code is okay
    (-> (:zip_codes c)
        split-on-comma
        (in? zip-code))))

(defn code->value
  "Get the value for a coupon code contextualized by user and vehicle choice."
  [db-conn code vehicle-id user-id zip-code & {:keys [bypass-zip-code-check]}]
  (let [coupon (get-coupon-by-code db-conn code)
        license-plate (get-license-plate-by-vehicle-id db-conn vehicle-id)]
    (cond
      (not (and coupon license-plate))
      {:success false
       :message "Sorry, that code is invalid."
       :value 0}

      (and (not bypass-zip-code-check)
           (not (valid-zip-code? coupon zip-code)))
      {:success false
       :message "Sorry, that coupon code cannot be used in this zip code."
       :value 0}

      (or (< (:expiration_time coupon) (quot (System/currentTimeMillis) 1000))
          (not (under-max-uses? coupon)))
      {:success false
       :message "Sorry, that code is expired."
       :value 0}

      (is-owner? coupon user-id)
      {:success false
       :message "Sorry, you cannot use your own coupon code. Send it to your friends to earn free gallons!"
       :value 0}

      (or (used-by-license-plate? coupon license-plate)
          (used-by-user-id? coupon user-id))
      {:success false
       :message "Sorry, you have already used that code. (Or, your license plate may be invalid.)"
       :value 0}

      (and (:only_for_first_orders coupon)
           (has-ordered? db-conn license-plate user-id))
      {:success false
       :message "Sorry, that code is only for a first-time order. (Or, your license plate may be invalid.)"
       :value 0}

      :else
      (case (:type coupon)
        "standard" {:success true
                    :value (:value coupon)}
        "referral" {:success true
                    :value config/referral-referred-value}))))

(defn is-code-available?
  [db-conn code]
  (empty? (doall (!select db-conn "coupons" [:id] {:code code}))))

;; this could be faster if just tried the insert and then retried if failing
;; on duplicate unique key: code
(defn create-referral-coupon
  "Creates a new referral coupon and returns its code."
  [db-conn user-id]
  (let [code (->> (repeatedly gen-coupon-code)
                  (filter (partial is-code-available? db-conn))
                  first)]
    (!insert db-conn "coupons" {:id (rand-str-alpha-num 20)
                                :code code
                                :type "referral"
                                :value 0
                                :owner_user_id user-id
                                :expiration_time 1999999999})
    code))

(defn mark-code-as-used
  "Mark a coupon as used given its code."
  [db-conn code license-plate user-id]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE coupons SET "
          "used_by_license_plates = CONCAT(used_by_license_plates, \""
          (mysql-escape-str license-plate) "\", ','), "
          "used_by_user_ids = CONCAT(used_by_user_ids, \""
          (mysql-escape-str user-id) "\", ',') "
          " WHERE code = \"" (mysql-escape-str code) "\""))))

(defn mark-code-as-unused
  "Mark a coupon as unused (available for use) given its code."
  [db-conn code vehicle-id user-id]
  (let [coupon (get-coupon-by-code db-conn code)
        license-plate (get-license-plate-by-vehicle-id db-conn vehicle-id)
        used-by-license-plates (-> (:used_by_license_plates coupon)
                                   split-on-comma
                                   set
                                   (disj (mysql-escape-str license-plate)))
        used-by-user-ids (-> (:used_by_user_ids coupon)
                             split-on-comma
                             set
                             (disj (mysql-escape-str user-id)))]
    (sql/with-connection db-conn
      (sql/do-prepared
       (str "UPDATE coupons SET "
            "used_by_license_plates = \""
            (s/join "," used-by-license-plates)
            (when (seq used-by-license-plates) ",")
            "\", used_by_user_ids = \""
            (s/join "," used-by-user-ids)
            (when (seq used-by-user-ids) ",")
            "\" WHERE code = \"" (mysql-escape-str code) "\"")))))

(defn mark-gallons-as-used
  "Use gallons from user's referral gallons. Assumes gallons are available."
  [db-conn user-id gallons]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE users SET referral_gallons = referral_gallons - "
          (coerce-double gallons)
          " WHERE id = \"" (mysql-escape-str user-id) "\""))))

(defn mark-gallons-as-unused
  "Un-use gallons from user's referral gallons. I.e., add gallons to user."
  [db-conn user-id gallons]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE users SET referral_gallons = referral_gallons + "
          (coerce-double gallons)
          " WHERE id = \"" (mysql-escape-str user-id) "\""))))

(defn apply-referral-bonus
  "Add benefits of referral to origin account."
  [db-conn user-id]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE users SET referral_gallons = referral_gallons + "
          config/referral-referrer-gallons
          " WHERE id = \"" (mysql-escape-str user-id) "\"")))
  (users/send-push db-conn user-id (str "Thank you for sharing Purple with your"
                                        " friend! We've added "
                                        config/referral-referrer-gallons
                                        " gallons to your account!")))
