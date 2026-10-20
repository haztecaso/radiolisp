(ns radiolisp.common.favorites-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [radiolisp.common.favorites :as favs]))

(def jazz {:id "j1" :name "Jazz Radio" :url "https://jazz.example/stream"
           :favicon "https://jazz.example/logo.png" :tags ["jazz" "bebop"]})
(def blues {:id "b1" :name "Blues FM" :url "http://blues.example/stream"
            :favicon nil :tags ["blues"]})
(def inter {:id "f1" :name "France Inter" :url "https://inter.example/live.mp3"
            :favicon nil :tags []})

(def stations {"j1" jazz "b1" blues "f1" inter})

(def three
  [{:id "j1" :name "Jazz Radio" :key "1"}
   {:id "b1" :name "Blues FM" :key nil}
   {:id "f1" :name "France Inter" :key "x"}])

(defn- ids [favorites] (mapv :id favorites))

(deftest reserved-keys
  (is (favs/reserved-key? "/"))
  (is (favs/reserved-key? " "))
  (is (favs/reserved-key? "Escape"))
  (is (favs/reserved-key? "G"))
  (is (not (favs/reserved-key? "x")))
  (is (not (favs/reserved-key? "1"))))

(deftest add-appends-once
  (is (= [{:id "j1" :name "Jazz Radio" :key nil}] (favs/add [] jazz)))
  (is (= ["j1" "b1"] (ids (favs/add (favs/add [] jazz) blues))))
  (is (= [{:id "j1" :name "Jazz Radio" :key nil}] (favs/add (favs/add [] jazz) jazz))))

(deftest member
  (is (favs/member? three "b1"))
  (is (not (favs/member? three "zz"))))

(deftest remove-drops-the-entry
  (is (= ["j1" "f1"] (ids (favs/remove three "b1"))))
  (is (= three (favs/remove three "zz")))
  (is (vector? (favs/remove three "b1"))))

(deftest move-before-reorders
  (is (= ["f1" "j1" "b1"] (ids (favs/move-before three "f1" "j1"))))
  (is (= ["b1" "j1" "f1"] (ids (favs/move-before three "j1" "f1"))))
  (testing "a nil target moves to the end"
    (is (= ["b1" "f1" "j1"] (ids (favs/move-before three "j1" nil)))))
  (testing "dropping on itself or an unknown id changes nothing"
    (is (= three (favs/move-before three "b1" "b1")))
    (is (= three (favs/move-before three "zz" "j1")))
    (is (= three (favs/move-before three "j1" "zz")))))

(deftest move-to-front
  (is (= ["f1" "j1" "b1"] (ids (favs/move-to-front three "f1"))))
  (is (= three (favs/move-to-front three "j1"))))

(deftest keys-are-unique
  (testing "assigning takes the key from its previous owner"
    (let [result (favs/assign-key three "b1" "x")]
      (is (= [["j1" "1"] ["b1" "x"] ["f1" nil]] (map (juxt :id :key) result)))))
  (testing "reserved keys are refused"
    (is (= three (favs/assign-key three "b1" "j"))))
  (is (= "f1" (favs/key-owner three "x")))
  (is (nil? (favs/key-owner three "q")))
  (is (nil? (favs/key-owner three nil)))
  (is (= [nil nil "x"] (map :key (favs/clear-key three "j1")))))

(deftest matching-filters-by-name-or-tag
  (is (= three (favs/matching three stations "")))
  (is (= three (favs/matching three stations "  ")))
  (is (= ["j1"] (ids (favs/matching three stations "JAZZ"))))
  (is (= ["j1"] (ids (favs/matching three stations "bebop"))))
  (is (= ["b1" "f1"] (ids (favs/matching three stations "f"))))
  (testing "falls back to the stored name when the station is unknown"
    (is (= ["f1"] (ids (favs/matching three {} "inter"))))))

(deftest serialize-round-trip
  (is (= three (favs/deserialize (favs/serialize three))))
  (is (= [] (favs/deserialize (favs/serialize []))))
  (is (str/includes? (favs/serialize three) ":version 1")))

(deftest deserialize-is-defensive
  (is (nil? (favs/deserialize nil)))
  (is (nil? (favs/deserialize "")))
  (is (nil? (favs/deserialize "{:version 1 :favorites")) "unreadable")
  (is (nil? (favs/deserialize "{:version 2 :favorites []}")) "another version")
  (is (nil? (favs/deserialize "[1 2 3]")))
  (is (nil? (favs/deserialize "{:version 1 :favorites [{:name \"no id\"}]}")))
  (testing "a missing key becomes nil"
    (is (= [{:id "a" :name "A" :key nil}]
           (favs/deserialize "{:version 1 :favorites [{:id \"a\" :name \"A\"}]}")))))

(deftest m3u-export
  (is (= (str "#EXTM3U\n"
              "#EXTINF:-1 radiobrowser-uuid=\"j1\" tvg-logo=\"https://jazz.example/logo.png\",Jazz Radio\n"
              "https://jazz.example/stream\n"
              "#EXTINF:-1 radiobrowser-uuid=\"b1\",Blues FM\n"
              "http://blues.example/stream\n")
         (favs/->m3u (take 2 three) stations)))
  (testing "stations without a known stream are left out"
    (is (= "#EXTM3U\n" (favs/->m3u [{:id "zz" :name "Gone" :key nil}] stations)))))

(deftest m3u-round-trip
  (is (= [{:id "j1" :name "Jazz Radio" :url "https://jazz.example/stream"}
          {:id "b1" :name "Blues FM" :url "http://blues.example/stream"}
          {:id "f1" :name "France Inter" :url "https://inter.example/live.mp3"}]
         (favs/parse-m3u (favs/->m3u three stations)))))

(deftest m3u-from-other-players
  (testing "plain M3U, CRLF line ends, commas in titles"
    (is (= [{:id nil :name "Radio, the best" :url "http://a.example/s"}
            {:id nil :name nil :url "http://b.example/s"}]
           (favs/parse-m3u (str "#EXTM3U\r\n"
                                "#EXTINF:-1 tvg-name=\"x, y\",Radio, the best\r\n"
                                "http://a.example/s\r\n"
                                "\r\n"
                                "# a comment\r\n"
                                "http://b.example/s\r\n")))))
  (testing "garbage gives nothing"
    (is (= [] (favs/parse-m3u "")))
    (is (= [] (favs/parse-m3u nil)))
    (is (= [] (favs/parse-m3u "hello world\nnot a url")))))
