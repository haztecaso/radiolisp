(ns radiolisp.frontend.effects
  "The only impure layer. `execute!` runs one effect vector; results come
  back as actions through `dispatch`."
  (:require [radiolisp.frontend.api :as api]
            [radiolisp.frontend.audio :as audio]))

(def focus-targets
  {:search-input "#search-input"
   :search-list  "#search-list"
   :opener       "#search-opener"
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

;; audio

(defmethod execute! :audio/play [_ [_ url]] (audio/play! url))
(defmethod execute! :audio/pause [_ _] (audio/pause!))
(defmethod execute! :audio/stop [_ _] (audio/stop!))
(defmethod execute! :audio/volume [_ [_ volume]] (audio/set-volume! volume))

;; DOM. The render runs synchronously before effects, so the nodes exist.

(defmethod execute! :dom/focus [_ [_ target]]
  (some-> (js/document.querySelector (focus-targets target)) (.focus)))

(defmethod execute! :dom/scroll-highlight [_ _]
  (some-> (js/document.querySelector "#search-list [aria-selected=true]")
          (.scrollIntoView #js {:block "nearest"})))

(defmethod execute! :dom/lang [_ [_ locale]]
  (set! (.-lang js/document.documentElement) (name locale)))

(defmethod execute! :dom/show-help [_ _]
  (let [^js dialog (js/document.getElementById "help")]
    (when-not (.-open dialog) (.showModal dialog))))
