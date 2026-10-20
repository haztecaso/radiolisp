(ns radiolisp.frontend.actions
  "Every state change in radiolisp. `handle` is pure: it takes the state and
  an action vector and returns `{:state next-state :effects [effect ...]}`.
  Effects are data; `radiolisp.frontend.effects` runs them."
  (:require [clojure.string :as str]))

(def min-query-length 2)
(def debounce-ms 300)
(def connect-timeout-ms 15000)

(defn query-active?
  "True when `query` is long enough to search for."
  [query]
  (>= (count (str/trim (or query ""))) min-query-length))

(defn visible-stations
  "The stations the list shows right now."
  [{:keys [status results]}]
  (if (= :done status) results []))

(defn- clamp [lo hi n]
  (max lo (min hi n)))

(defmulti handle (fn [_state [action-type]] action-type))

(defn- ok
  ([state] {:state state :effects []})
  ([state effects] {:state state :effects (vec effects)}))

(defn- then
  "Runs `action` on the state of `result`, appending its effects."
  [{:keys [state effects]} action]
  (let [next-result (handle state action)]
    {:state   (:state next-result)
     :effects (into effects (:effects next-result))}))

(defmethod handle :default [state action]
  (ok state [[:log/warn "Unhandled action" action]]))

;; ---------------------------------------------------------------- app

(defn- volume-effect [{:keys [volume muted?]}]
  [:audio/volume (if muted? 0 (/ volume 100))])

(defmethod handle :app/init [state [_ {:keys [locale]}]]
  (ok (assoc state :locale locale)
      [[:dom/lang locale]
       (volume-effect (:player state))
       [:api/servers]
       [:dom/focus :search-input]]))

(defmethod handle :api/servers-loaded [state [_ servers]]
  (-> (ok (assoc state :api {:servers (vec servers) :current 0}))
      (then [:search/run])))

;; ---------------------------------------------------------------- search

(defn- current-server [state]
  (let [{:keys [servers current]} (:api state)]
    (get servers current)))

(defn- fetch-effect [state request]
  (let [query (get-in state [:search :query])]
    [:api/fetch {:server (current-server state)
                 :query  (when (query-active? query) (str/trim query))
                 :seq    request}]))

(defmethod handle :search/open [state _]
  (ok (update state :search assoc :open? true :mode :insert)
      [[:dom/focus :search-input]]))

(defmethod handle :search/close [state _]
  (if (get-in state [:player :station])
    (ok (assoc-in state [:search :open?] false) [[:dom/focus :opener]])
    (ok state)))

(defmethod handle :search/mode [state [_ mode]]
  (ok (assoc-in state [:search :mode] mode)
      [[:dom/focus (if (= mode :normal) :search-list :search-input)]]))

(defmethod handle :search/typed [state [_ query]]
  (let [state (update state :search assoc :query query :highlight 0)
        top   (get-in state [:search :top])]
    (cond
      (query-active? query)
      (ok (assoc-in state [:search :status] :loading)
          [[:timer/start :search debounce-ms [:search/run]]])

      (seq top)
      (ok (update state :search
                  (fn [search]
                    (-> search
                        (assoc :status :done :results top :error nil)
                        (update :seq inc))))
          [[:timer/cancel :search]])

      :else
      (-> (ok state [[:timer/cancel :search]])
          (then [:search/run])))))

(defmethod handle :search/run [state _]
  (let [request (inc (get-in state [:search :seq]))
        state   (update state :search assoc :status :loading :seq request :retried? false)]
    (if (current-server state)
      (ok state [[:timer/cancel :search] (fetch-effect state request)])
      (ok state))))

(defmethod handle :search/loaded [state [_ request stations]]
  (if (not= request (get-in state [:search :seq]))
    (ok state)
    (let [top? (not (query-active? (get-in state [:search :query])))]
      (ok (update state :search
                  (fn [search]
                    (cond-> (assoc search :status :done :results (vec stations)
                                   :highlight 0 :error nil :retried? false)
                      top? (assoc :top (vec stations)))))))))

(defmethod handle :search/failed [state [_ request at]]
  (let [{:keys [servers current]} (:api state)
        search                    (:search state)]
    (cond
      (not= request (:seq search))
      (ok state)

      (and (not (:retried? search)) (> (count servers) 1))
      (let [state (-> state
                      (assoc-in [:api :current] (mod (inc current) (count servers)))
                      (assoc-in [:search :retried?] true))]
        (ok state [(fetch-effect state request)]))

      :else
      (ok (update state :search assoc :status :error :retried? false
                  :error [:error/network {:at at}])))))

(defmethod handle :search/enter [state _]
  (if (= :done (get-in state [:search :status]))
    (handle state [:search/choose])
    (handle state [:search/run])))

(defmethod handle :search/move [state [_ delta]]
  (let [n (count (visible-stations (:search state)))]
    (if (zero? n)
      (ok state)
      (ok (update-in state [:search :highlight] #(clamp 0 (dec n) (+ % delta)))
          [[:dom/scroll-highlight]]))))

(defmethod handle :search/edge [state [_ edge]]
  (let [n (count (visible-stations (:search state)))]
    (if (zero? n)
      (ok state)
      (ok (assoc-in state [:search :highlight] (if (= edge :last) (dec n) 0))
          [[:dom/scroll-highlight]]))))

(defmethod handle :keys/g [state [_ now]]
  (ok (assoc-in state [:keys :last-g] now)))

;; ---------------------------------------------------------------- player

(defn- start-playback
  "Connects to the station in the player and arms the connect timeout."
  [state]
  (ok (update state :player assoc :status :connecting :error nil)
      [[:audio/play (get-in state [:player :station :url])]
       [:timer/start :connect connect-timeout-ms [:audio/timeout]]]))

(defmethod handle :search/choose [state [_ index]]
  (let [{:keys [highlight] :as search} (:search state)
        station (get (visible-stations search) (or index highlight))
        player  (:player state)
        closed  (assoc-in state [:search :open?] false)]
    (cond
      (nil? station)
      (ok state)

      (and (= (:id station) (get-in player [:station :id]))
           (#{:playing :connecting} (:status player)))
      (ok closed [[:dom/focus :toggle]])

      (:hls? station)
      (ok (update closed :player assoc :station station :status :error
                  :error [:error/unsupported {}] :started-at nil)
          [[:audio/stop] [:timer/cancel :connect] [:dom/focus :toggle]])

      :else
      (-> (start-playback (update closed :player assoc :station station :started-at nil))
          (update :effects conj [:dom/focus :toggle])))))

(defmethod handle :player/toggle [state _]
  (let [{:keys [station status]} (:player state)]
    (cond
      (or (nil? station) (:hls? station))
      (ok state)

      (#{:playing :connecting} status)
      (ok (assoc-in state [:player :status] :paused)
          [[:audio/pause] [:timer/cancel :connect]])

      :else
      (start-playback state))))

(defmethod handle :player/volume [state [_ volume]]
  (let [player (assoc (:player state)
                      :volume (clamp 0 100 (js/Math.round volume))
                      :muted? false)]
    (ok (assoc state :player player) [(volume-effect player)])))

(defmethod handle :player/volume-step [state [_ delta]]
  (handle state [:player/volume (+ (get-in state [:player :volume]) delta)]))

(defmethod handle :player/mute [state _]
  (let [player (update (:player state) :muted? not)]
    (ok (assoc state :player player) [(volume-effect player)])))

;; ---------------------------------------------------------------- audio element

(defn- status-in? [state statuses]
  (contains? statuses (get-in state [:player :status])))

(defmethod handle :audio/playing [state [_ now]]
  (ok (-> state
          (assoc :now now)
          (update :player (fn [player]
                            (cond-> (assoc player :status :playing :error nil)
                              (nil? (:started-at player)) (assoc :started-at now)))))
      [[:timer/cancel :connect]]))

(defmethod handle :audio/paused [state _]
  (if (status-in? state #{:playing})
    (ok (assoc-in state [:player :status] :paused))
    (ok state)))

(defmethod handle :audio/waiting [state _]
  (if (status-in? state #{:playing})
    (ok (assoc-in state [:player :status] :connecting)
        [[:timer/start :connect connect-timeout-ms [:audio/timeout]]])
    (ok state)))

(defmethod handle :audio/failed [state _]
  (if (status-in? state #{:playing :connecting})
    (ok (update state :player assoc :status :error :error [:error/stream {}])
        [[:timer/cancel :connect]])
    (ok state)))

(defmethod handle :audio/timeout [state _]
  (if (status-in? state #{:connecting})
    (ok (update state :player assoc :status :error :error [:error/stream {}])
        [[:audio/stop]])
    (ok state)))

;; ---------------------------------------------------------------- misc

(defmethod handle :clock/tick [state [_ now]]
  (ok (assoc state :now now)))

(defmethod handle :i18n/set [state [_ locale]]
  (ok (assoc state :locale locale) [[:dom/lang locale]]))

(defmethod handle :help/open [state _]
  (ok (assoc state :help? true) [[:dom/show-help]]))

(defmethod handle :help/close [state _]
  (ok (assoc state :help? false)))
