(ns radiolisp.backend.icy-test
  (:require [clojure.test :refer [deftest is testing]]
            [radiolisp.backend.icy :as icy]))

(defn- bytes-of [& xs]
  (byte-array (mapcat (fn [x] (if (string? x) (.getBytes ^String x "UTF-8") (map unchecked-byte x))) xs)))

(defn- meta-block
  "A metadata block: its length byte and the text padded with \\0 to 16-byte units."
  [^String text]
  (let [raw     (.getBytes text "UTF-8")
        units   (long (Math/ceil (/ (alength raw) 16.0)))
        padded  (byte-array (* 16 units))]
    (System/arraycopy raw 0 padded 0 (alength raw))
    (concat [units] (map long padded))))

(def metaint 16)
(def audio-a (range 0 16))
(def audio-b (range 16 32))
(def audio-c (range 32 48))
(def audio-d (range 48 56))

(def stream
  "Three audio blocks, each followed by metadata: a title, nothing (0), another title."
  (byte-array (map unchecked-byte
                   (concat audio-a (meta-block "StreamTitle='One';")
                           audio-b [0]
                           audio-c (meta-block "StreamTitle='Two';StreamUrl='';")
                           audio-d))))

(defn- run [chunks]
  (reduce (fn [{:keys [state audio metadata]} piece]
            (let [r (icy/feed state piece)]
              {:state    (:state r)
               :audio    (into audio (mapcat seq (:audio r)))
               :metadata (into metadata (map icy/stream-title (:metadata r)))}))
          {:state (icy/initial-state metaint) :audio [] :metadata []}
          chunks))

(defn- split-at-cuts [^bytes bs cuts]
  (let [points (concat [0] cuts [(alength bs)])]
    (map (fn [[a b]] (java.util.Arrays/copyOfRange bs (int a) (int b)))
         (partition 2 1 points))))

(deftest feed-splits-audio-and-metadata
  (let [expected-audio (map unchecked-byte (concat audio-a audio-b audio-c audio-d))]
    (testing "in one piece"
      (let [{:keys [audio metadata]} (run [stream])]
        (is (= expected-audio audio))
        (is (= ["One" "Two"] metadata))))
    (testing "cut at every single position"
      (doseq [cut (range 1 (alength ^bytes stream))]
        (let [{:keys [audio metadata]} (run (split-at-cuts stream [cut]))]
          (is (= expected-audio audio) (str "cut at " cut))
          (is (= ["One" "Two"] metadata) (str "cut at " cut)))))
    (testing "one byte at a time"
      (let [{:keys [audio metadata]} (run (split-at-cuts stream (range 1 (alength ^bytes stream))))]
        (is (= expected-audio audio))
        (is (= ["One" "Two"] metadata))))))

(deftest feed-with-an-empty-chunk
  (let [r (icy/feed (icy/initial-state metaint) (byte-array 0))]
    (is (= [] (:audio r)))
    (is (= [] (:metadata r)))))

(defn- title [& xs] (icy/stream-title (apply bytes-of xs)))

(deftest stream-title
  (is (= "Artist - Song" (title "StreamTitle='Artist - Song';")))
  (is (= "Artist - Song" (title "StreamTitle='Artist - Song';StreamUrl='http://x';")))
  (is (= "Artist - Song" (title "StreamTitle='  Artist - Song ';" [0 0 0])) "trims and drops padding")
  (testing "quotes inside the title"
    (is (= "Guns N' Roses - Don't Cry" (title "StreamTitle='Guns N' Roses - Don't Cry';StreamUrl='';")))
    (is (= "It's" (title "StreamTitle='It's';"))))
  (testing "encodings"
    (is (= "Mägo de Oz - Fiesta pagana" (title "StreamTitle='Mägo de Oz - Fiesta pagana';")))
    (is (= "Mägo" (icy/stream-title (byte-array (map unchecked-byte
                                                     (concat (.getBytes "StreamTitle='M" "UTF-8")
                                                             [0xE4]
                                                             (.getBytes "go';" "UTF-8"))))))
        "ISO-8859-1 when the bytes are not UTF-8"))
  (testing "empty title means nothing is on now"
    (is (= "" (title "StreamTitle='';"))))
  (testing "no title"
    (is (nil? (title "StreamUrl='x';")))
    (is (nil? (title ""))))
  (testing "advertisements"
    (is (nil? (title "StreamTitle='Buy now';adw_ad='true';durationMilliseconds='3000';")))
    (is (nil? (title "StreamTitle='Buy';AdCreativeId='1';")))))
