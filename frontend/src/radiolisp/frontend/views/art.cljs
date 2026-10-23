(ns radiolisp.frontend.views.art
  "Station artwork. Radio Browser favicons go from 16 px icons to wide
  banners, with or without a transparent background, so each station is
  shown in one of three modes: the image (covering the box when squarish,
  whole otherwise), the image with a margin on a plate, or its name. The
  mode is the user's choice, else guessed: from the image's pixels when its
  server allows reading them (CORS), else from its file extension. Without
  an image, a tile shows the whole name and a thumbnail a monogram."
  (:require [clojure.string :as str]))

(def small-max-side 96)

(defn- squarish? [w h]
  (and (pos? w) (pos? h) (< 0.8 (/ w h) 1.25)))

(defn fit
  "How an image of `w` × `h` natural pixels fills its box in `mode`: :cover
  when squarish, :contain otherwise. With a margin (:pad), a tiny image is
  :small: never enlarged past twice its size. As the image (:image) it
  fills the box whatever its size. Images without an intrinsic size (some
  SVGs) are contained."
  ([w h] (fit w h :pad))
  ([w h mode]
   (let [w (or w 0) h (or h 0)]
     (cond
       (or (zero? w) (zero? h))                          :contain
       (and (= :pad mode) (< (max w h) small-max-side))  :small
       (squarish? w h)                                   :cover
       :else                                             :contain))))

(defn guess-by-url
  "What the file extension of `url` says when the pixels cannot be read:
  JPEG has no transparency; anything else (PNG, ICO, SVG, WebP, GIF or
  unknown) is taken as transparent."
  [url]
  (let [path (str/lower-case (first (str/split (or url "") #"[?#]")))]
    {:alpha? (not (re-find #"\.jpe?g$" path))}))

(def ^:private dark-luminance 0.35)
(def ^:private edge-spread
  "How far, per channel, a border pixel may be from the mean for the border
  to count as one colour."
  24)

(defn- hex [rgb]
  (apply str "#" (map #(.padStart (.toString (js/Math.round %) 16) 2 "0") rgb)))

(defn probe-result
  "Reads the RGBA pixels `data` (flat, `n` × `n`) of an image: whether its
  border is mostly (semi)transparent (:alpha?), whether its opaque pixels are dark
  (:dark?), and the colour of its border when it is opaque and even (:edge)."
  [data n]
  (let [pixel   (fn [i] (subvec data (* 4 i) (* 4 (inc i))))
        border? (fn [i] (let [x (mod i n) y (quot i n)]
                          (or (zero? x) (zero? y) (= x (dec n)) (= y (dec n)))))
        all     (map pixel (range (* n n)))
        border  (map pixel (filter border? (range (* n n))))
        clear   (count (filter #(< (nth % 3) 128) border))
        opaque  (filter #(> (nth % 3) 128) all)
        lum     (fn [[r g b]] (/ (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b)) 255))
        mean    (fn [ps k] (/ (reduce + (map #(nth % k) ps)) (count ps)))
        rgb     (when (every? #(>= (nth % 3) 250) border)
                  (mapv #(mean border %) [0 1 2]))]
    {:alpha? (>= (* 2 clear) (count border))
     :dark?  (boolean (and (seq opaque)
                           (< (/ (reduce + (map lum opaque)) (count opaque)) dark-luminance)))
     :edge   (when (and rgb (every? (fn [p] (every? #(<= (js/Math.abs (- (nth p %) (nth rgb %))) edge-spread)
                                                    [0 1 2]))
                                    border))
               (hex rgb))}))

(defn probe
  "The detection result for the image `station` shows now, if any."
  [{:keys [probes]} {:keys [id favicon]}]
  (let [p (get probes id)]
    (when (and p (= favicon (:src p))) p)))

(defn shows-image?
  "True when `station` has an image that did not fail to load."
  [{:keys [art]} {:keys [id favicon]}]
  (boolean (and favicon (not= :broken (get art id)))))

(defn mode
  "How `station` is shown: :image, :pad or :text. Its :display (the user's
  choice) wins; otherwise the probe of its image, or its file extension,
  decides. Without a working image it is always :text."
  [state {:keys [favicon display] :as station}]
  (cond
    (not (shows-image? state station)) :text
    display                            display
    (:alpha? (or (probe state station) (guess-by-url favicon))) :pad
    :else                              :image))

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
  widths below are estimates."
  {:css 84 :fit 78})
(def name-line-height 1.1)
(def name-sizes
  "Font sizes tried for a name, in cqi, from the largest down."
  (range 24 6.5 -0.5))

(def ^:private wide-re (js/RegExp. "[\\p{Script=Han}\\p{Script=Hiragana}\\p{Script=Katakana}\\p{Script=Hangul}]" "u"))

(defn- char-width
  "Advance of `c` in em in the display face (Roboto Black), measured once and
  rounded up."
  [c]
  (cond
    (= " " c)               0.25
    (.test wide-re c)       1.0
    (#{"m" "w" "M" "W"} c)  0.88
    (#{"i" "l" "j" "t" "f" "r" "." "," "'" "!" "|" ":" ";" "-" "I"} c) 0.45
    (not= c (str/lower-case c)) 0.71
    :else                   0.59))

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

(defn tint
  "One of four tints, derived from the station id so it never changes."
  [id]
  (mod (reduce (fn [h c] (mod (+ (* h 31) (.charCodeAt c 0)) 4294967296)) 7 (or id ""))
       4))

(defn view
  "The artwork of `station` in a box of `class` (\"art\" or \"thumb\"),
  as `mode` says. In :text a tile shows the whole name and a thumbnail its
  initials. An image sits on the plate (seen through its transparent
  pixels) unless it is contained on its own edge colour."
  [state {:keys [id favicon] station-name :name :as station} class]
  (let [shown (get-in state [:art id])
        m     (mode state station)
        {:keys [w h]} (when (map? shown) shown)
        fitted (when (map? shown) (fit w h m))
        {:keys [edge dark?]} (probe state station)
        edge  (when (and (= :image m) (not= :cover fitted)) edge)]
    [:div {:class [class (str "t" (tint id)) (name m) (when fitted (name fitted))
                   (when (and (not= :text m) (not edge)) "plate")
                   (when (false? dark?) "lit")]
           :style (when edge {:background edge})}
     (case m
       :text
       (if (= "art" class)
         [:span.full-name {:aria-hidden "true" :style (name-style station-name)} station-name]
         [:span.mono {:aria-hidden "true"} (initials station-name)])

       [:img {:src            favicon
              :alt            ""
              :loading        "lazy"
              :referrerpolicy "no-referrer"
              :style          (when (= :small fitted) {:width (str (* 2 w) "px")})
              :on             {:load  [[:art/sized id favicon :event.target/natural-width :event.target/natural-height]]
                               :error [[:art/failed id]]}}])]))
