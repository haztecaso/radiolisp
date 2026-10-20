(ns radiolisp.frontend.views.art
  "Station artwork. Radio Browser favicons go from 16 px icons to wide
  banners, so each image is shown according to its natural size: covering
  the box when big and squarish, contained on a tint otherwise, and a
  monogram when there is none."
  (:require [clojure.string :as str]))

(def cover-min-side 150)
(def small-max-side 96)

(defn fit
  "How to show an image of `w` × `h` natural pixels: :cover, :contain,
  :small (contained, never enlarged) or :broken when it has no pixels."
  [w h]
  (let [w (or w 0) h (or h 0)]
    (cond
      (or (zero? w) (zero? h))                            :broken
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

(defn tint
  "One of four tints, derived from the station id so it never changes."
  [id]
  (mod (reduce (fn [h c] (mod (+ (* h 31) (.charCodeAt c 0)) 4294967296)) 7 (or id ""))
       4))
