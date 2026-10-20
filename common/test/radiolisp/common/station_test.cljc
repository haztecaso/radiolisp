(ns radiolisp.common.station-test
  (:require [clojure.test :refer [deftest is testing]]
            [radiolisp.common.station :as station]))

(def adroit
  {"stationuuid"  "a1b2"
   "name"         "  Adroit Jazz Underground "
   "url"          "https://icecast.walmradio.com:8443/jazz.m3u"
   "url_resolved" "https://icecast.walmradio.com:8443/jazz"
   "countrycode"  "US"
   "codec"        "MP3"
   "bitrate"      320
   "clickcount"   445
   "tags"         "avant-garde,bebop,big band,bop,combos"
   "hls"          0
   "homepage"     "https://walmradio.com/jazz"
   "favicon"      "https://icecast.walmradio.com:8443/jazz.jpg"
   "votes"        184141
   "lastcheckok"  1
   "lastchecktime_iso8601"   "2026-10-17T15:03:57Z"
   "lastcheckoktime_iso8601" "2026-10-17T15:03:57Z"})

(def france-inter
  {"stationuuid"  "f1"
   "name"         "France Inter"
   "url"          "http://icecast.radiofrance.fr/franceinter-midfi.mp3"
   "url_resolved" ""
   "countrycode"  ""
   "codec"        "UNKNOWN"
   "bitrate"      0
   "clickcount"   734
   "tags"         ""
   "hls"          0
   "homepage"     ""
   "favicon"      ""
   "votes"        0
   "lastcheckok"  0
   "lastchecktime_iso8601"   "2026-10-17T08:30:40Z"
   "lastcheckoktime_iso8601" "2026-01-15T07:55:30Z"})

(deftest normalize-keeps-what-the-app-shows
  (is (= {:id      "a1b2"
          :name    "Adroit Jazz Underground"
          :url     "https://icecast.walmradio.com:8443/jazz"
          :country "US"
          :codec   "MP3"
          :bitrate 320
          :clicks  445
          :tags    ["avant-garde" "bebop" "big band"]
          :hls?    false
          :homepage      "https://walmradio.com/jazz"
          :favicon       "https://icecast.walmradio.com:8443/jazz.jpg"
          :votes         184141
          :check-ok?     true
          :last-check-at "2026-10-17T15:03:57Z"
          :last-ok-at    "2026-10-17T15:03:57Z"}
         (station/normalize adroit))))

(deftest normalize-turns-unknown-values-into-nil
  (let [s (station/normalize france-inter)]
    (testing "unknown codec and zero bitrate"
      (is (nil? (:codec s)))
      (is (nil? (:bitrate s))))
    (testing "empty country and tags"
      (is (nil? (:country s)))
      (is (= [] (:tags s))))
    (testing "falls back to url when url_resolved is empty"
      (is (= "http://icecast.radiofrance.fr/franceinter-midfi.mp3" (:url s))))))

(deftest normalize-detects-hls
  (is (true? (:hls? (station/normalize (assoc adroit "hls" 1)))))
  (is (true? (:hls? (station/normalize (assoc adroit "url_resolved" "https://x.example/live/index.m3u8?token=1")))))
  (is (false? (:hls? (station/normalize adroit)))))

(deftest normalize-cleans-tags
  (is (= ["jazz" "soul"] (:tags (station/normalize (assoc adroit "tags" " jazz, ,soul,jazz"))))))

(deftest normalize-keeps-health-and-links
  (let [s (station/normalize france-inter)]
    (is (false? (:check-ok? s)))
    (is (= "2026-01-15T07:55:30Z" (:last-ok-at s)))
    (is (nil? (:homepage s)) "empty homepage becomes nil")
    (is (nil? (:favicon s)))
    (is (= 0 (:votes s)))))
