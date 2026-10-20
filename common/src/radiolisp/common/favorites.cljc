(ns radiolisp.common.favorites
  "The list of favourite stations: a vector of `{:id :name :key}` whose order
  is the order of the grid. Pure and shared with the future backend."
  (:refer-clojure :exclude [remove])
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]))

(def version 1)

(def reserved-keys
  "Keys the app uses for itself; they can never play a favourite."
  #{"/" "?" "a" "h" "j" "k" "l" "m" "g" "i" "G" "f" " " "Enter" "Escape" "Tab"})

(defn reserved-key? [k]
  (contains? reserved-keys k))

(defn- index-of [favorites id]
  (first (keep-indexed (fn [i fav] (when (= id (:id fav)) i)) favorites)))

(defn member? [favorites id]
  (some? (index-of favorites id)))

(defn add
  "Appends `station` unless it is already a favourite."
  [favorites {:keys [id] station-name :name}]
  (if (member? favorites id)
    favorites
    (conj (vec favorites) {:id id :name station-name :key nil})))

(defn remove [favorites id]
  (filterv #(not= id (:id %)) favorites))

(defn move-before
  "Moves the favourite `id` just before `target-id`, or to the end when
  `target-id` is nil. Unknown ids change nothing."
  [favorites id target-id]
  (let [i (index-of favorites id)]
    (if (or (nil? i) (= id target-id) (and target-id (not (member? favorites target-id))))
      favorites
      (let [fav    (get favorites i)
            others (remove favorites id)
            j      (if target-id (index-of others target-id) (count others))]
        (into (conj (subvec others 0 j) fav) (subvec others j))))))

(defn move-to-front [favorites id]
  (if-let [first-id (:id (first favorites))]
    (move-before favorites id first-id)
    favorites))

(defn key-owner
  "Id of the favourite that `k` plays, or nil."
  [favorites k]
  (when k
    (:id (first (filter #(= k (:key %)) favorites)))))

(defn assign-key
  "Gives `k` to the favourite `id`, taking it from any other favourite.
  Reserved keys are refused."
  [favorites id k]
  (if (reserved-key? k)
    favorites
    (mapv (fn [fav]
            (cond
              (= id (:id fav)) (assoc fav :key k)
              (= k (:key fav)) (assoc fav :key nil)
              :else            fav))
          favorites)))

(defn clear-key [favorites id]
  (mapv #(if (= id (:id %)) (assoc % :key nil) %) favorites))

(defn matching
  "Favourites whose name or tags contain `query`, ignoring case. `stations`
  maps ids to normalized stations; unknown ids use the stored name."
  [favorites stations query]
  (let [q (str/lower-case (str/trim (or query "")))]
    (if (str/blank? q)
      favorites
      (filterv (fn [{:keys [id] stored-name :name}]
                 (let [station (get stations id)]
                   (some #(str/includes? (str/lower-case (or % "")) q)
                         (cons (or (:name station) stored-name) (:tags station)))))
               favorites))))

;; ---------------------------------------------------------------- storage

(defn serialize [favorites]
  (pr-str {:version version :favorites (vec favorites)}))

(defn- valid-favorite? [fav]
  (and (map? fav) (string? (:id fav)) (not (str/blank? (:id fav)))))

(defn deserialize
  "The favourites stored by `serialize`, or nil when `s` is missing,
  unreadable or written by another version."
  [s]
  (when-not (str/blank? s)
    (let [data (try (edn/read-string s) (catch #?(:clj Exception :cljs :default) _ nil))
          favs (:favorites data)]
      (when (and (map? data)
                 (= version (:version data))
                 (vector? favs)
                 (every? valid-favorite? favs))
        (mapv #(select-keys (merge {:key nil} %) [:id :name :key]) favs)))))

;; ---------------------------------------------------------------- M3U

(defn- m3u-entry [{:keys [id] stored-name :name} {:keys [url favicon] station-name :name}]
  (when url
    (str "#EXTINF:-1 radiobrowser-uuid=\"" id "\""
         (when favicon (str " tvg-logo=\"" favicon "\""))
         "," (or station-name stored-name) "\n" url "\n")))

(defn ->m3u
  "Extended M3U with the favourites whose stream is known. Keys are personal
  and are not exported."
  [favorites stations]
  (apply str "#EXTM3U\n"
         (keep #(m3u-entry % (get stations (:id %))) favorites)))

(def ^:private extinf-re
  #"^#EXTINF:\s*-?[\d.]+((?:\s+[\w-]+=\"[^\"]*\")*)\s*,(.*)$")

(defn- parse-extinf [line]
  (when-let [[_ attrs title] (re-find extinf-re line)]
    (let [attrs (into {} (map (fn [[_ k v]] [k v])) (re-seq #"([\w-]+)=\"([^\"]*)\"" attrs))]
      {:id   (not-empty (get attrs "radiobrowser-uuid"))
       :name (not-empty (str/trim title))})))

(defn parse-m3u
  "`[{:id :name :url}]` from M3U text; `:id` is the Radio Browser uuid when the
  file carries one. Lines that are not http(s) URLs are ignored."
  [text]
  (loop [[line & more] (str/split-lines (or text ""))
         info           nil
         entries        []]
    (if (nil? line)
      entries
      (let [line (str/trim line)]
        (cond
          (str/starts-with? line "#EXTINF:")
          (recur more (parse-extinf line) entries)

          (re-find #"(?i)^https?://\S+$" line)
          (recur more nil (conj entries (merge {:id nil :name nil} info {:url line})))

          :else
          (recur more info entries))))))
