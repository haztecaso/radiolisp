(ns radiolisp.frontend.views.art
  "Station artwork. Radio Browser favicons go from 16 px icons to wide
  banners, so each image is shown according to its natural size: covering
  the box when big and squarish, contained on a tint otherwise. Without one,
  a tile shows the whole name and a thumbnail a monogram."
  (:require [clojure.string :as str]))

(def cover-min-side 150)
(def small-max-side 96)

(defn fit
  "How to show an image of `w` × `h` natural pixels: :cover, :contain or
  :small (contained, never enlarged). Images without an intrinsic size
  (some SVGs) are contained."
  [w h]
  (let [w (or w 0) h (or h 0)]
    (cond
      (or (zero? w) (zero? h))                            :contain
      (and (>= (min w h) cover-min-side) (< 0.8 (/ w h) 1.25)) :cover
      (< (max w h) small-max-side)                        :small
      :else                                               :contain)))

(defn initials
  "Up to two capital initials of `station-name`, or a dot."
  [station-name]
  (let [words (-> (or station-name "")
                  (.replace (js/RegExp. "[^\\p{L}\\p{N} ]" "gu") " ")
                  str/trim
                  (str/split #"\s+"))
        letters (->> words (remove str/blank?) (take 2) (map first) (apply str))]
    (if (str/blank? letters) "•" (str/upper-case letters))))

(def name-box
  "The share of the tile, in container units (cqi), a name may fill each way
  (`.full-name` in the CSS). Names are fitted to a little less, since the
  widths below are estimates and italics overhang."
  {:css 84 :fit 78})
(def name-line-height 1.1)
(def name-sizes
  "Font sizes tried for a name, in cqi, from the largest down."
  (range 24 6.5 -0.5))

(def ^:private wide-re (js/RegExp. "[\\p{Script=Han}\\p{Script=Hiragana}\\p{Script=Katakana}\\p{Script=Hangul}]" "u"))

(defn- char-width
  "Advance of `c` in em in the display face, measured once and rounded up."
  [c]
  (cond
    (= " " c)               0.23
    (.test wide-re c)       1.0
    (#{"m" "w" "M" "W"} c)  0.83
    (#{"i" "l" "j" "t" "f" "r" "." "," "'" "!" "|" ":" ";" "-"} c) 0.31
    (not= c (str/lower-case c)) 0.59
    :else                   0.52))

(defn- word-width [word]
  (reduce + (map char-width (js/Array.from word))))

(defn- line-count
  "How many lines `words` take at `width` em, wrapping only between words,
  or nil when a word is wider than a line."
  [words width]
  (first
   (reduce (fn [[lines used] word]
             (let [w     (word-width word)
                   added (+ used (char-width " ") w)]
               (cond
                 (> w width)      (reduced nil)
                 (zero? used)     [lines w]
                 (<= added width) [lines added]
                 :else            [(inc lines) w])))
           [1 0]
           words)))

(defn name-size
  "The largest size in `name-sizes` (cqi) at which `station-name` fits the
  tile without breaking a word, or the smallest one (the CSS then breaks it
  anywhere). Every length scales with the tile, so the answer holds for any
  tile width; the CSS clamps it in px."
  [station-name]
  (let [words (remove str/blank? (str/split (or station-name "") #"\s+"))
        box   (:fit name-box)]
    (or (first (filter (fn [size]
                         (when-let [lines (line-count words (/ box size))]
                           (<= (* lines size name-line-height) box)))
                       name-sizes))
        (last name-sizes))))

(defn name-style
  "Inline style for a tile name: its fitted size between the CSS bounds."
  [station-name]
  {:font-size (str "clamp(var(--name-min), " (name-size station-name) "cqi, var(--name-max))")})

(defn shows-image?
  "True when the artwork of `station` is an image that loaded."
  [fits {:keys [id favicon]}]
  (boolean (and favicon (not= :broken (get fits id)))))

(defn tint
  "One of four tints, derived from the station id so it never changes."
  [id]
  (mod (reduce (fn [h c] (mod (+ (* h 31) (.charCodeAt c 0)) 4294967296)) 7 (or id ""))
       4))

(defn view
  "The artwork of `station` in a box of `class` (\"art\" or \"thumb\").
  `fits` maps station ids to the result of `fit`, or :broken. Without an
  image, a tile (\"art\") shows the whole name and a thumbnail its initials."
  [fits {:keys [id favicon] station-name :name :as station} class]
  (let [shown (get fits id)]
    [:div {:class [class (str "t" (tint id)) (when shown (name shown))]}
     (cond
       (shows-image? fits station)
       [:img {:src            favicon
              :alt            ""
              :loading        "lazy"
              :referrerpolicy "no-referrer"
              :on             {:load  [[:art/sized id :event.target/natural-width :event.target/natural-height]]
                               :error [[:art/failed id]]}}]

       (= "art" class)
       [:span.full-name {:aria-hidden "true" :style (name-style station-name)} station-name]

       :else
       [:span.mono {:aria-hidden "true"} (initials station-name)])]))
