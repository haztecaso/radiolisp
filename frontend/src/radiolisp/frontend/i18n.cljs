(ns radiolisp.frontend.i18n
  "Translations. Dictionaries are EDN files inlined at compile time; the
  state only ever holds keys and data, the views call `t`."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [shadow.resource :as rc]))

(def dictionaries
  {:es (reader/read-string (rc/inline "i18n/es.edn"))
   :en (reader/read-string (rc/inline "i18n/en.edn"))})

(def locales [:es :en])

(def default-locale :en)

(defn detect-locale
  "First supported locale in a browser preference list such as
  [\"es-ES\" \"en\"]; `default-locale` when none is supported."
  [languages]
  (or (some (fn [lang]
              (let [base (keyword (first (str/split (str/lower-case lang) #"-")))]
                (some #{base} locales)))
            languages)
      default-locale))

(defn format-number [locale n]
  (.format (js/Intl.NumberFormat. (name locale)) n))

(defn- plural-form [locale n]
  (keyword (.select (js/Intl.PluralRules. (name locale)) n)))

(defn t
  "Translates `k` into `locale`, replacing `{var}` with values from `vars`.
  A message that is a map is a set of plural forms chosen by `(:count vars)`.
  Numbers are formatted for the locale. A missing key renders as itself."
  ([locale k] (t locale k {}))
  ([locale k vars]
   (let [message (get-in dictionaries [locale k] (str k))
         message (if (map? message)
                   (get message (plural-form locale (:count vars)) (:other message))
                   message)]
     (str/replace message #"\{(\w+)\}"
                  (fn [[_ var-name]]
                    (let [v (get vars (keyword var-name))]
                      (if (number? v) (format-number locale v) (str v))))))))

(defn format-time
  "Hours and minutes of `ms` (epoch milliseconds) in the locale's style."
  [locale ms]
  (.format (js/Intl.DateTimeFormat. (name locale) #js {:hour "2-digit" :minute "2-digit"})
           (js/Date. ms)))

(defn format-date
  "Day and short month of an ISO 8601 timestamp, or nil."
  [locale iso]
  (when iso
    (let [date (js/Date. iso)]
      (when-not (js/isNaN (.getTime date))
        (.format (js/Intl.DateTimeFormat. (name locale) #js {:day "numeric" :month "short"}) date)))))
