(ns radiolisp.frontend.views.art-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.views.art :as art]))

(deftest fit-covers-squarish-images
  (is (= :cover (art/fit 300 300)))
  (is (= :cover (art/fit 150 180)))
  (is (= :cover (art/fit 120 120)) "any squarish image of 96 px or more"))

(deftest fit-contains-wide-or-small-images
  (testing "too wide or too tall"
    (is (= :contain (art/fit 400 120)))
    (is (= :contain (art/fit 200 300))))
  (testing "with a margin, tiny images are not blown up"
    (is (= :small (art/fit 64 64 :pad)))
    (is (= :small (art/fit 95 40 :pad))))
  (testing "as the image, tiny ones fill the box all the same"
    (is (= :cover (art/fit 64 64 :image)))
    (is (= :contain (art/fit 95 40 :image)))))

(deftest images-without-a-size-are-contained
  (is (= :contain (art/fit 0 0)))
  (is (= :contain (art/fit nil nil))))

(deftest guess-by-url-reads-the-extension
  (is (= {:alpha? false} (art/guess-by-url "https://x.example/logo.JPG")))
  (is (= {:alpha? false} (art/guess-by-url "https://x.example/logo.jpeg?v=2")))
  (is (= {:alpha? true} (art/guess-by-url "https://x.example/logo.png")))
  (is (= {:alpha? true} (art/guess-by-url "https://x.example/favicon.ico#x")))
  (is (= {:alpha? true} (art/guess-by-url "https://x.example/logo")) "unknown counts as transparent")
  (is (= {:alpha? true} (art/guess-by-url nil))))

(defn- square
  "A flat RGBA vector of side `n`: `border` on the outer ring, `inside` elsewhere."
  [n border inside]
  (vec (mapcat (fn [i]
                 (let [x (mod i n) y (quot i n)]
                   (if (or (zero? x) (zero? y) (= x (dec n)) (= y (dec n))) border inside)))
               (range (* n n)))))

(deftest probe-result-reads-the-border
  (testing "a transparent border"
    (let [r (art/probe-result (square 8 [0 0 0 0] [0 0 0 255]) 8)]
      (is (:alpha? r))
      (is (:dark? r) "black ink")
      (is (nil? (:edge r)))))
  (testing "a light logo on transparent"
    (is (false? (:dark? (art/probe-result (square 8 [0 0 0 0] [250 250 250 255]) 8)))))
  (testing "an opaque white border"
    (let [r (art/probe-result (square 8 [255 255 255 255] [200 0 0 255]) 8)]
      (is (false? (:alpha? r)))
      (is (= "#ffffff" (:edge r)))))
  (testing "a mixed opaque border has no edge colour"
    (let [data (assoc (square 8 [255 255 255 255] [0 0 0 255]) 0 0 1 0 2 0)]
      (is (nil? (:edge (art/probe-result data 8)))))))

(def logo "https://x.example/logo.png")
(def photo "https://x.example/photo.jpg")

(deftest mode-decides-how-a-station-looks
  (is (= :text (art/mode {} {:id "a" :favicon nil})) "no image")
  (is (= :text (art/mode {:art {"a" :broken}} {:id "a" :favicon logo})) "broken image")
  (is (= :pad (art/mode {} {:id "a" :favicon logo})) "png: transparent by its extension")
  (is (= :image (art/mode {} {:id "a" :favicon photo})) "jpg: opaque by its extension")
  (is (= :image (art/mode {:probes {"a" {:src logo :alpha? false}}} {:id "a" :favicon logo}))
      "the canvas wins over the extension")
  (is (= :pad (art/mode {:probes {"a" {:src "old.jpg" :alpha? false}}} {:id "a" :favicon logo}))
      "a probe of another image is ignored")
  (is (= :text (art/mode {} {:id "a" :favicon photo :display :text})) "the user's choice wins")
  (is (= :text (art/mode {:art {"a" :broken}} {:id "a" :favicon logo :display :pad}))
      "no choice brings a broken image back"))

(deftest initials-take-two-words
  (is (= "FI" (art/initials "France Info")))
  (is (= "R" (art/initials "Radio")))
  (is (= "JR" (art/initials "jazz radio (24/7) best")))
  (is (= "ÉM" (art/initials "Émisora — Música")))
  (is (= "•" (art/initials "")))
  (is (= "•" (art/initials nil))))

(deftest tint-is-stable
  (is (= (art/tint "960e57c5-0601-11e8-ae97-52543be04c81")
         (art/tint "960e57c5-0601-11e8-ae97-52543be04c81")))
  (is (every? #{0 1 2 3} (map art/tint ["a" "b" "c" "d" "e" "f" "" nil]))))

(deftest name-size-fits-the-name-in-the-tile
  (testing "short names get the largest size"
    (is (= (first art/name-sizes) (art/name-size "RNE"))))
  (testing "longer names get smaller, never below the smallest"
    (is (< (art/name-size "Radio Nacional de España Clásica")
           (art/name-size "Radio Nacional")
           (first art/name-sizes)))
    (is (= (last art/name-sizes)
           (art/name-size (apply str (repeat 80 "w"))))))
  (testing "a long word shrinks the size rather than break"
    (is (< (art/name-size "Supercalifragilistic") (art/name-size "Super cali")))
    (is (< (art/name-size "中国之声中国之声") (art/name-size "中国之声"))))
  (is (= (first art/name-sizes) (art/name-size "")))
  (is (= (first art/name-sizes) (art/name-size nil))))

(deftest artless-tiles-show-the-whole-name
  (let [station {:id "a" :name "Radio Uno" :favicon nil}
        [_ _ child] (art/view {} station "art")]
    (is (= :span.full-name (first child)))
    (is (= "Radio Uno" (last child))))
  (let [[_ _ child] (art/view {} {:id "a" :name "Radio Uno"} "thumb")]
    (is (= "RU" (last child))))
  (let [[_ _ child] (art/view {} {:id "a" :name "Radio Uno" :favicon photo :display :text} "art")]
    (is (= :span.full-name (first child)) "a station shown as text")))

(deftest images-keep-their-natural-size-when-small
  (let [art {:art {"a" {:w 32 :h 32}}}
        [_ _ img] (art/view art {:id "a" :name "R" :favicon photo :display :pad} "art")]
    (is (= :img (first img)))
    (is (= "64px" (get-in img [1 :style :width])) "at most twice, with a margin")
    (let [[_ attrs img] (art/view art {:id "a" :name "R" :favicon photo :display :image} "art")]
      (is (nil? (get-in img [1 :style])) "as the image it fills the box")
      (is (some #{"cover"} (:class attrs))))))
