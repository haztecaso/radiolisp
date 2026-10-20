(ns radiolisp.common.station
  "Turns a station from the Radio Browser JSON API into the map radiolisp
  works with. Pure and shared with the future backend."
  (:require [clojure.string :as str]))

(def max-tags 3)

(defn- blank->nil [s]
  (let [s (some-> s str str/trim)]
    (when-not (str/blank? s) s)))

(defn- parse-tags [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       distinct
       (take max-tags)
       vec))

(defn- hls-url? [url]
  (boolean (some->> url (re-find #"(?i)\.m3u8(\?|$)"))))

(defn normalize
  "Takes one station as decoded from Radio Browser JSON (string keys) and
  returns `{:id :name :url :country :codec :bitrate :clicks :tags :hls?
  :homepage :favicon :votes :check-ok? :last-check-at :last-ok-at}`.
  Values Radio Browser does not know (codec \"UNKNOWN\", bitrate 0, empty
  strings) become nil."
  [{:strs [stationuuid url url_resolved countrycode codec bitrate clickcount tags hls
           homepage favicon votes lastcheckok]
    station-name  "name"
    last-check-at "lastchecktime_iso8601"
    last-ok-at    "lastcheckoktime_iso8601"}]
  (let [stream (or (blank->nil url_resolved) (blank->nil url))
        codec  (blank->nil codec)]
    {:id      stationuuid
     :name    (or (blank->nil station-name) "")
     :url     stream
     :country (blank->nil countrycode)
     :codec   (when-not (= "UNKNOWN" (some-> codec str/upper-case)) codec)
     :bitrate (when (and (number? bitrate) (pos? bitrate)) bitrate)
     :clicks  (or clickcount 0)
     :tags    (parse-tags tags)
     :hls?    (or (= 1 hls) (hls-url? stream))
     :homepage      (blank->nil homepage)
     :favicon       (blank->nil favicon)
     :votes         (or votes 0)
     :check-ok?     (= 1 lastcheckok)
     :last-check-at (blank->nil last-check-at)
     :last-ok-at    (blank->nil last-ok-at)}))
