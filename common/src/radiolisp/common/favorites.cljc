(ns radiolisp.common.favorites
  "The list of favourite stations: a vector of `{:id :name :key}` whose order
  is the order of the grid, plus `:custom-name` on the favourites the user
  renamed and `:display` on those whose look they chose. Pure and shared
  with the future backend."
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

(defn find-favorite [favorites id]
  (some #(when (= id (:id %)) %) favorites))

(defn member? [favorites id]
  (some? (index-of favorites id)))

(defn add
  "Appends `station` unless it is already a favourite."
  [favorites {:keys [id] station-name :name}]
  (if (member? favorites id)
    favorites
    (conj (vec favorites) {:id id :name station-name :key nil})))

(defn display-name
  "The name to show for `favorite`: the one the user gave it, else the one
  Radio Browser gives `station`, else the stored one."
  [favorite station]
  (or (:custom-name favorite) (:name station) (:name favorite)))

(defn clean-name
  "`text` trimmed, with its runs of whitespace (newlines too) made one space;
  nil when nothing is left."
  [text]
  (let [s (str/trim (str/replace (or text "") #"\s+" " "))]
    (when-not (str/blank? s) s)))

(defn rename
  "Gives the favourite `id` the name `text`. A blank name, or the one Radio
  Browser already gives it (`original`), takes the custom name away."
  [favorites id text original]
  (let [custom (clean-name text)]
    (mapv (fn [fav]
            (cond
              (not= id (:id fav))                     fav
              (or (nil? custom) (= custom original))  (dissoc fav :custom-name)
              :else                                   (assoc fav :custom-name custom)))
          favorites)))

(def display-modes
  "How a favourite with artwork may be shown: the image, the image with a
  margin, or its name. Absent means automatic."
  #{:image :pad :text})

(defn set-display
  "Shows the favourite `id` as `mode`; nil (or an unknown mode) makes it
  automatic again."
  [favorites id mode]
  (if (or (nil? mode) (contains? display-modes mode))
    (mapv (fn [fav]
            (cond
              (not= id (:id fav)) fav
              mode                (assoc fav :display mode)
              :else               (dissoc fav :display)))
          favorites)
    favorites))

(defn add-imported
  "Appends the imported `stations` that are not favourites yet. `names` maps
  station ids to the name the file gave them; one that differs from Radio
  Browser's becomes the custom name. Favourites already in the list keep
  theirs."
  [favorites stations names]
  (reduce (fn [favorites {:keys [id] :as station}]
            (if (member? favorites id)
              favorites
              (rename (add favorites station) id (get names id) (:name station))))
          favorites
          stations))

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
  "Favourites whose name, custom name or tags contain `query`, ignoring case.
  `stations` maps ids to normalized stations; unknown ids use the stored name."
  [favorites stations query]
  (let [q (str/lower-case (str/trim (or query "")))]
    (if (str/blank? q)
      favorites
      (filterv (fn [{:keys [id custom-name] stored-name :name}]
                 (let [station (get stations id)]
                   (some #(str/includes? (str/lower-case (or % "")) q)
                         (list* custom-name (or (:name station) stored-name) (:tags station)))))
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
        (mapv (fn [fav]
                (let [custom (when (string? (:custom-name fav)) (clean-name (:custom-name fav)))]
                  (cond-> (select-keys (merge {:key nil} fav) [:id :name :key])
                    custom                                  (assoc :custom-name custom)
                    (contains? display-modes (:display fav)) (assoc :display (:display fav)))))
              favs)))))

;; ---------------------------------------------------------------- M3U

(defn- m3u-entry [{:keys [id] :as favorite} {:keys [url favicon] :as station}]
  (when url
    (str "#EXTINF:-1 radiobrowser-uuid=\"" id "\""
         (when favicon (str " tvg-logo=\"" favicon "\""))
         "," (display-name favorite station) "\n" url "\n")))

(defn ->m3u
  "Extended M3U with the favourites whose stream is known, each titled with
  the name the grid shows. Keys are personal and are not exported."
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

;; ---------------------------------------------------------------- list of uuids

(def ^:private uuid-re
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(def ^:private list-line-re
  #"(?i)^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:\s+(.*))?$")

(defn ->list
  "One Radio Browser uuid per line, every favourite in grid order. A renamed
  favourite carries its custom name after the uuid."
  [favorites]
  (apply str (map (fn [{:keys [id custom-name]}]
                    (str id (when custom-name (str " " custom-name)) "\n"))
                  favorites)))

(defn parse-list
  "`[{:id :name}]` from `text`: a uuid at the start of each line, optionally
  followed by a name, in order and without repeated uuids. Blank lines, `#`
  comments and lines that do not start with a uuid are ignored."
  [text]
  (->> (str/split-lines (or text ""))
       (keep #(when-let [[_ id station-name] (re-find list-line-re (str/trim %))]
                {:id (str/lower-case id) :name (clean-name station-name)}))
       (reduce (fn [[seen entries] {:keys [id] :as entry}]
                 (if (seen id) [seen entries] [(conj seen id) (conj entries entry)]))
               [#{} []])
       second))

;; ---------------------------------------------------------------- Radiolise

(defn- unquote-yaml [s]
  (let [s (str/trim s)]
    (if-let [[_ inner] (or (re-find #"^\"(.*)\"$" s) (re-find #"^'(.*)'$" s))]
      inner
      s)))

(defn- radiolise-entry [{id "id" url "url" station-name "name"}]
  (let [id    (some-> id unquote-yaml str/lower-case)
        url   (some-> url unquote-yaml)
        entry {:id   (when (and id (re-find uuid-re id)) id)
               :name (some-> station-name unquote-yaml not-empty)
               :url  (when (and url (re-find #"(?i)^https?://\S+$" url)) url)}]
    (when (or (:id entry) (:url entry))
      entry)))

(defn parse-radiolise
  "`[{:id :name :url}]` from a Radiolise export: YAML whose lists hold one map
  per station, each opened by `- key: value`. Only `id` (the Radio Browser
  uuid), `name` and `url` are read; other keys and folded lines are ignored."
  [text]
  (let [stations (reduce (fn [stations line]
                           (if-let [[_ k v] (re-find #"^\s*-\s+([\w-]+):\s*(.*)$" line)]
                             (conj stations {k v})
                             (if-let [[_ k v] (and (seq stations)
                                                   (re-find #"^\s+([\w-]+):\s*(.*)$" line))]
                               (conj (pop stations) (assoc (peek stations) k v))
                               stations)))
                         []
                         (str/split-lines (or text "")))]
    (into [] (keep radiolise-entry) stations)))

;; ---------------------------------------------------------------- files

(def formats
  "Export formats: their extension and media type."
  {:m3u  {:extension "m3u" :mime "audio/x-mpegurl"}
   :list {:extension "list" :mime "text/plain"}})

(def default-file-stem "radiolisp-favoritas")

(def ^:private extension-re #"(?i)\.(m3u8?|list)$")

(defn file-format
  "The import format of a file called `filename`, from its extension, or nil.
  Radiolise exports its favourites as `.txt`."
  [filename]
  (when-let [[_ ext] (re-find #"(?i)\.(m3u8?|list|txt)$" (or filename ""))]
    (case (str/lower-case ext)
      "list" :list
      "txt"  :radiolise
      :m3u)))

(defn export-filename
  "`stem` made safe as a file name, with the extension of `kind` (a key of
  `formats`). A known extension typed in the stem is dropped; a blank stem
  becomes `default-file-stem`."
  [stem kind]
  (let [stem (-> (or stem "")
                 (str/replace #"[\\/:*?\"<>|\x00-\x1f]" "")
                 str/trim
                 (str/replace extension-re "")
                 (str/replace #"^\.+" "")
                 str/trim)]
    (str (if (str/blank? stem) default-file-stem stem)
         "." (get-in formats [kind :extension]))))
