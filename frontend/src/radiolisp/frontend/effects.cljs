(ns radiolisp.frontend.effects
  "The only impure layer. `execute!` runs one effect vector; results come
  back as actions through `dispatch`."
  (:require [radiolisp.common.favorites :as favs]
            [radiolisp.frontend.api :as api]
            [radiolisp.frontend.audio :as audio]
            [radiolisp.frontend.socket :as socket]
            [radiolisp.frontend.views.art :as art]))

(def storage-key "radiolisp/favorites")
(def cache-key "radiolisp/station-cache")
(def theme-key "radiolisp/theme") ; also read by the script in index.html

(def dark-query "(prefers-color-scheme: dark)")

(defn initial-theme
  "The theme to start with: the stored choice, else the browser's preference."
  []
  (let [stored (try (js/localStorage.getItem theme-key) (catch :default _ nil))]
    (if (#{"light" "dark"} stored)
      {:current (keyword stored) :chosen? true}
      {:current (if (.-matches (js/matchMedia dark-query)) :dark :light) :chosen? false})))

(def focus-targets
  {:search-input "#search-input"
   :search-list  "#search-list"
   :filter-input "#filter-input"
   :toggle       "#player-toggle"})

(defonce ^:private timers (atom {}))

(defmulti execute! (fn [_dispatch [effect-type]] effect-type))

(defmethod execute! :default [_ effect]
  (js/console.warn "Unknown effect" (pr-str effect)))

(defmethod execute! :log/warn [_ [_ message data]]
  (js/console.warn message (pr-str data)))

;; timers: one per id; starting an id again replaces it

(defn- cancel-timer! [id]
  (js/clearTimeout (get @timers id))
  (swap! timers dissoc id))

(defmethod execute! :timer/start [dispatch [_ id ms action]]
  (cancel-timer! id)
  (swap! timers assoc id (js/setTimeout #(do (swap! timers dissoc id) (dispatch action)) ms)))

(defmethod execute! :timer/cancel [_ [_ id]]
  (cancel-timer! id))

;; storage. localStorage can throw (private modes, quota), so every access is guarded.

(defmethod execute! :storage/load [dispatch _]
  (let [raw       (try (js/localStorage.getItem storage-key) (catch :default _ nil))
        favorites (favs/deserialize raw)]
    (when (and raw (nil? favorites))
      (js/console.warn "radiolisp: the stored favourites are unreadable; starting empty."))
    (dispatch [:favorites/loaded (or favorites [])])))

(defmethod execute! :storage/save [_ [_ favorites]]
  (try (js/localStorage.setItem storage-key (favs/serialize favorites))
       (catch :default e (js/console.warn "radiolisp: could not save the favourites" e))))

(defn- cache-probe [{:strs [src edge] :as probe}]
  (when (and (map? probe) (string? src))
    {:src    src
     :alpha? (true? (get probe "alpha?"))
     :dark?  (get probe "dark?")
     :edge   (when (string? edge) edge)}))

(defn- cache-entry [[id {:strs [favicon probe] station-name "name"}]]
  (when (and (string? id) (string? station-name))
    [id (cond-> {:name station-name :favicon (when (string? favicon) favicon)}
          (cache-probe probe) (assoc :probe (cache-probe probe)))]))

(defmethod execute! :cache/load [dispatch _]
  ;; Only a speed-up: anything unreadable is simply an empty cache.
  (let [raw   (try (js/localStorage.getItem cache-key) (catch :default _ nil))
        data  (try (js->clj (js/JSON.parse raw)) (catch :default _ nil))
        cache (when (map? data) (into {} (keep cache-entry) data))]
    (dispatch [:cache/loaded (or cache {})])))

(defmethod execute! :cache/save [_ [_ cache]]
  (try (js/localStorage.setItem cache-key (js/JSON.stringify (clj->js cache)))
       (catch :default _ nil)))

(defmethod execute! :theme/save [_ [_ theme]]
  (try (js/localStorage.setItem theme-key (name theme))
       (catch :default _ nil)))

(defmethod execute! :storage/persist [_ _]
  ;; Asks the browser not to evict our storage under pressure.
  (when-let [^js storage (.-storage js/navigator)]
    (some-> (.persist storage) (.catch (fn [_] nil)))))

;; Radio Browser

(defmethod execute! :api/servers [dispatch _]
  (-> (api/fetch-servers)
      (.then #(dispatch [:api/servers-loaded %]))))

(defmethod execute! :api/fetch [dispatch [_ {:keys [server query] request :seq}]]
  (-> (api/fetch-stations server query)
      (.then #(dispatch [:search/loaded request %]))
      (.catch (fn [error]
                (when-not (api/aborted? error)
                  (dispatch [:search/failed request (js/Date.now)]))))))

(defmethod execute! :api/stations-by-id [dispatch [_ {:keys [server ids]}]]
  (-> (api/fetch-by-ids server ids)
      (.then #(dispatch [:stations/loaded %]))
      (.catch #(dispatch [:stations/failed]))))

(defmethod execute! :api/click [_ [_ {:keys [server id]}]]
  (when server (api/click! server id)))

(defmethod execute! :api/vote [dispatch [_ {:keys [server id]}]]
  (-> (api/vote server id)
      (.then #(dispatch [:vote/done id %]))
      (.catch #(dispatch [:vote/done id false]))))

(defmethod execute! :api/import [dispatch [_ {:keys [server ids urls names]}]]
  (-> (api/resolve-import server ids urls)
      (.then #(dispatch [:import/resolved (assoc % :names names)]))
      (.catch #(dispatch [:import/failed]))))

;; artwork detection

(def ^:private probe-side 32)

(defn- read-pixels
  "The RGBA pixels of `img` drawn at `probe-side` × `probe-side`, or nil
  when the canvas refuses to give them."
  [^js img]
  (try
    (let [canvas (js/document.createElement "canvas")
          _      (set! (.-width canvas) probe-side)
          _      (set! (.-height canvas) probe-side)
          ^js ctx (.getContext canvas "2d")]
      (.drawImage ctx img 0 0 probe-side probe-side)
      (vec (js/Array.from (.-data (.getImageData ctx 0 0 probe-side probe-side)))))
    (catch :default _ nil)))

(defmethod execute! :art/probe [dispatch [_ id src]]
  ;; A second copy, asked for with CORS: the one on screen must not be, or it
  ;; would not load at all from servers without it.
  (let [img (js/Image.)]
    (set! (.-crossOrigin img) "anonymous")
    (set! (.-referrerPolicy img) "no-referrer")
    (.addEventListener img "load" #(dispatch [:art/probed id src (some-> (read-pixels img) (art/probe-result probe-side))]))
    (.addEventListener img "error" #(dispatch [:art/probed id src nil]))
    (set! (.-src img) src)))

;; files and clipboard

(defmethod execute! :file/download [_ [_ {:keys [filename mime text]}]]
  (let [url  (js/URL.createObjectURL (js/Blob. #js [text] #js {:type mime}))
        link (js/document.createElement "a")]
    (set! (.-href link) url)
    (set! (.-download link) filename)
    (.click link)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))

(defn read-file!
  "Reads `file` as text and dispatches `[action text filename]`."
  [dispatch ^js file action]
  (-> (.text file)
      (.then #(dispatch [action % (.-name file)]))
      (.catch #(dispatch [:import/failed]))))

(defonce ^:private picker
  ;; The open file input. It is never in the document, so without this
  ;; reference it can be collected before the browser reports the choice.
  (atom nil))

(defmethod execute! :file/pick [dispatch [_ {:keys [accept action]}]]
  (let [input (reset! picker (js/document.createElement "input"))]
    (set! (.-type input) "file")
    (set! (.-accept input) accept)
    (.addEventListener input "change"
                       (fn []
                         (when-let [file (aget (.-files input) 0)]
                           (read-file! dispatch file action))))
    (.click input)))

(defmethod execute! :clipboard/write [dispatch [_ text]]
  (-> (js/navigator.clipboard.writeText text)
      (.then #(dispatch [:toast/show [[:toast/copied {}]]]))
      (.catch #(dispatch [:toast/show [[:toast/copy-failed {}]]]))))

;; audio

(defmethod execute! :audio/play [_ [_ url hls? id]] (audio/play! url hls? id))
(defmethod execute! :audio/pause [_ _] (audio/pause!))
(defmethod execute! :audio/stop [_ _] (audio/stop!))
(defmethod execute! :audio/volume [_ [_ volume]] (audio/set-volume! volume))

(defmethod execute! :media/update [_ [_ {:keys [title artist artwork]}]]
  (when (and (.-mediaSession js/navigator) (exists? js/MediaMetadata))
    (set! (.. js/navigator -mediaSession -metadata)
          (js/MediaMetadata. (clj->js (cond-> {:title title :artist (or artist "radiolisp")}
                                        artwork (assoc :artwork [{:src artwork}])))))))

;; now playing, from the backend

(defmethod execute! :socket/subscribe [dispatch [_ id]] (socket/subscribe! dispatch id))
(defmethod execute! :socket/unsubscribe [_ _] (socket/unsubscribe!))

;; DOM. The render runs synchronously before effects, so the nodes exist.

(defn- element [target]
  (if (keyword? target)
    (js/document.querySelector (focus-targets target))
    (js/document.getElementById target)))

(defmethod execute! :dom/focus [_ [_ target]]
  (some-> (element target) (.focus)))

(defmethod execute! :dom/select [_ [_ id]]
  ;; Focuses a text field with its text selected, ready to be typed over.
  (when-let [^js el (js/document.getElementById id)]
    (.focus el)
    (.select el)))

(def ^:private default-tile-px 180)

(defmethod execute! :dom/preview-size [_ _]
  ;; The preview in «Personalizar» is as wide as the tiles of the grid now.
  (when-let [^js dialog (js/document.getElementById "dialog")]
    (let [w (some-> (js/document.querySelector ".grid .tile") .-offsetWidth)]
      (.setProperty (.-style dialog) "--tile-w" (str (or w default-tile-px) "px")))))

(defmethod execute! :dom/scroll-top [_ _]
  (js/window.scrollTo #js {:top      0
                           :behavior (if (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))
                                       "auto"
                                       "smooth")}))

(defmethod execute! :dom/blur [_ [_ target]]
  (some-> (element target) (.blur)))

(defmethod execute! :dom/scroll-highlight [_ _]
  (some-> (js/document.querySelector "#search-list [aria-selected=true]")
          (.scrollIntoView #js {:block "nearest"})))

(defmethod execute! :dom/lang [_ [_ locale]]
  (set! (.-lang js/document.documentElement) (name locale)))

(defmethod execute! :dom/theme [_ [_ theme]]
  (set! (.. js/document -documentElement -dataset -theme) (name theme)))

(defmethod execute! :dom/show-dialog [_ [_ id]]
  (let [^js dialog (js/document.getElementById id)]
    (when-not (.-open dialog) (.showModal dialog))))

(defmethod execute! :dom/close-dialog [_ [_ id]]
  (let [^js dialog (js/document.getElementById id)]
    (when (.-open dialog) (.close dialog))))

(def ^:private menu-margin 8)

(defmethod execute! :dom/place-menu [_ [_ anchor-id]]
  ;; The menu is fixed, so it never gets clipped: under its button, aligned to
  ;; its right edge, and above it when there is no room below.
  (let [^js anchor (js/document.getElementById anchor-id)
        ^js menu   (js/document.getElementById "menu")]
    (when (and anchor menu)
      (let [r  (.getBoundingClientRect anchor)
            mw (.-offsetWidth menu)
            mh (.-offsetHeight menu)
            vw js/innerWidth
            vh js/innerHeight
            left (max menu-margin (min (- vw mw menu-margin) (- (.-right r) mw)))
            top  (if (< (+ (.-bottom r) mh menu-margin) vh)
                   (+ (.-bottom r) 4)
                   (max menu-margin (- (.-top r) mh 4)))]
        (set! (.. menu -style -left) (str left "px"))
        (set! (.. menu -style -top) (str top "px"))
        (some-> (.querySelector menu "[role^=menuitem]") (.focus))))))

(defmethod execute! :dom/focus-step [_ [_ container-id delta]]
  (when-let [^js container (js/document.getElementById container-id)]
    (let [items (vec (js/Array.from (.querySelectorAll container "[role^=menuitem]")))
          n     (count items)
          i     (.indexOf items js/document.activeElement)]
      (when (pos? n)
        (.focus ^js (get items (mod (+ (if (neg? i) (if (pos? delta) -1 0) i) delta) n)))))))
