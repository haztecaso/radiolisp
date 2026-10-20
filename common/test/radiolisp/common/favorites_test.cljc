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

(def uuid-a "960e57c5-0601-11e8-ae97-52543be04c81")
(def uuid-b "9617a958-0601-11e8-ae97-52543be04c81")

(deftest list-export
  (is (= "j1\nb1\nf1\n" (favs/->list three)))
  (testing "stations without a known stream are kept: only the uuid travels"
    (is (= "zz\n" (favs/->list [{:id "zz" :name "Gone" :key nil}]))))
  (is (= "" (favs/->list []))))

(deftest list-import
  (is (= [{:id uuid-a :name nil} {:id uuid-b :name "Mi Radio"}]
         (favs/parse-list (favs/->list [{:id uuid-a} {:id uuid-b :custom-name "Mi Radio"}]))))
  (testing "CRLF, blanks, comments, junk, repeats and upper case"
    (is (= [{:id uuid-a :name nil} {:id uuid-b :name nil}]
           (favs/parse-list (str "# my radios\r\n"
                                 "  " (str/upper-case uuid-a) "  \r\n"
                                 "\r\n"
                                 "not-a-uuid\r\n"
                                 "https://a.example/s\r\n"
                                 uuid-b "\r\n"
                                 uuid-a "\r\n")))))
  (testing "a name after the uuid, its spaces tidied; the first one of a repeat wins"
    (is (= [{:id uuid-a :name "Los 40 Clásicos"}]
           (favs/parse-list (str uuid-a "\t Los 40   Clásicos \n" uuid-a " Otro\n")))))
  (is (= [] (favs/parse-list nil)))
  (is (= [] (favs/parse-list "hello"))))

;; ---------------------------------------------------------------- custom names

(def renamed
  [{:id "j1" :name "Jazz Radio" :key "1" :custom-name "Jazz"}
   {:id "b1" :name "Blues FM" :key nil}])

(deftest display-name-prefers-the-custom-one
  (is (= "Jazz" (favs/display-name (first renamed) jazz)))
  (is (= "Blues FM" (favs/display-name (second renamed) blues)))
  (testing "the stored name when Radio Browser does not know it"
    (is (= "Gone" (favs/display-name {:id "zz" :name "Gone"} nil))))
  (is (= "Jazz Radio" (favs/display-name nil jazz))))

(deftest clean-name-tidies-whitespace
  (is (= "Radio 3" (favs/clean-name "  Radio\n\t 3 ")))
  (is (nil? (favs/clean-name "  \n ")))
  (is (nil? (favs/clean-name nil))))

(deftest rename-sets-and-clears
  (is (= "Smooth" (:custom-name (first (favs/rename three "j1" " Smooth " "Jazz Radio")))))
  (is (= (rest three) (rest (favs/rename three "j1" "Smooth" "Jazz Radio"))) "others untouched")
  (testing "blank or the original name takes the custom one away"
    (is (= three (favs/rename (favs/rename three "j1" "Smooth" "Jazz Radio") "j1" "  " "Jazz Radio")))
    (is (= three (favs/rename (favs/rename three "j1" "Smooth" "Jazz Radio") "j1" "Jazz  Radio" "Jazz Radio")))
    (is (= three (favs/rename three "j1" nil "Jazz Radio"))))
  (is (= three (favs/rename three "zz" "X" "Y")) "unknown id"))

(deftest add-imported-keeps-names-that-differ
  (let [added (favs/add-imported [{:id "j1" :name "Jazz Radio" :key "1" :custom-name "Mine"}]
                                 [jazz blues inter]
                                 {"j1" "From file" "b1" "Blues" "f1" "France Inter"})]
    (is (= [{:id "j1" :name "Jazz Radio" :key "1" :custom-name "Mine"}
            {:id "b1" :name "Blues FM" :key nil :custom-name "Blues"}
            {:id "f1" :name "France Inter" :key nil}]
           added))))

(deftest matching-looks-at-custom-names-too
  (is (= ["j1"] (ids (favs/matching renamed stations "jazz r"))))
  (is (= ["b1"] (ids (favs/matching [{:id "b1" :name "Blues FM" :key nil :custom-name "Delta"}] stations "delta")))))

(deftest custom-names-travel
  (testing "storage"
    (is (= renamed (favs/deserialize (favs/serialize renamed))))
    (is (= [{:id "a" :name "A" :key nil}]
           (favs/deserialize "{:version 1 :favorites [{:id \"a\" :name \"A\" :custom-name \"  \"}]}"))
        "a blank custom name is dropped")
    (is (= [{:id "a" :name "A" :key nil}]
           (favs/deserialize "{:version 1 :favorites [{:id \"a\" :name \"A\" :custom-name 3}]}"))))
  (testing "M3U titles each station with the name the grid shows"
    (is (= [{:id "j1" :name "Jazz" :url "https://jazz.example/stream"}
            {:id "b1" :name "Blues FM" :url "http://blues.example/stream"}]
           (favs/parse-m3u (favs/->m3u renamed stations)))))
  (testing "the list writes the custom name after the uuid"
    (is (= "j1 Jazz\nb1\n" (favs/->list renamed)))))

(def radiolise-export
  (str "version: \"2\"\n"
       "type: list\n"
       "data:\n"
       "  Favorites:\n"
       "    - name: NTS Radio 1\n"
       "      url: http://stream-relay-geo.ntslive.net/stream\n"
       "      icon: \"\"\n"
       "      tags: concert,music,news,northern soul,politics,punk,rock'n'roll,soul,\n"
       "        radio,political,radio libre\n"
       "      id: " (str/upper-case uuid-a) "\n"
       "      hue: 120\n"
       "    - name: \"Radio Onda d'urto\"\n"
       "      url: https://radio.example/live\n"
       "      id: \"\"\n"
       "    - name: Not a station\n"
       "      url: \"\"\n"
       "      id: nope\n"
       "    - id: " uuid-b "\n"
       "      name: Rádio Batuta\n"
       "      url: ftp://odd.example/s\n"))

(deftest radiolise-import
  (is (= [{:id uuid-a :name "NTS Radio 1" :url "http://stream-relay-geo.ntslive.net/stream"}
          {:id nil :name "Radio Onda d'urto" :url "https://radio.example/live"}
          {:id uuid-b :name "Rádio Batuta" :url nil}]
         (favs/parse-radiolise radiolise-export)))
  (testing "CRLF line ends"
    (is (= 3 (count (favs/parse-radiolise (str/replace radiolise-export "\n" "\r\n"))))))
  (is (= [] (favs/parse-radiolise nil)))
  (is (= [] (favs/parse-radiolise "version: \"2\"\ndata:\n  Favorites: []\n"))))

(deftest file-formats
  (is (= :m3u (favs/file-format "radios.m3u")))
  (is (= :m3u (favs/file-format "Radios.M3U8")))
  (is (= :list (favs/file-format "radios.list")))
  (is (= :radiolise (favs/file-format "Favorites_1792522073000.txt")))
  (is (nil? (favs/file-format "radios.pls")))
  (is (nil? (favs/file-format "m3u")))
  (is (nil? (favs/file-format nil))))

(deftest export-filenames
  (is (= "radiolisp-favoritas.m3u" (favs/export-filename "radiolisp-favoritas" :m3u)))
  (is (= "mis radios.list" (favs/export-filename "  mis radios " :list)))
  (testing "a typed extension is not doubled, nor kept when the format differs"
    (is (= "radios.m3u" (favs/export-filename "radios.m3u" :m3u)))
    (is (= "radios.list" (favs/export-filename "radios.m3u" :list))))
  (testing "path separators and other unsafe characters go"
    (is (= "etcpasswd.list" (favs/export-filename "../etc/passwd" :list)))
    (is (= "ab.m3u" (favs/export-filename "a:*?\"<>|b" :m3u))))
  (testing "blank falls back to the default"
    (is (= "radiolisp-favoritas.list" (favs/export-filename "" :list)))
    (is (= "radiolisp-favoritas.m3u" (favs/export-filename nil :m3u)))
    (is (= "radiolisp-favoritas.m3u" (favs/export-filename ".m3u" :m3u)))))
