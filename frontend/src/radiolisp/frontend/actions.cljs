(ns radiolisp.frontend.actions
  "Every state change in radiolisp. `handle` is pure: it takes the state and
  an action vector and returns `{:state next-state :effects [effect ...]}`.
  Effects are data; `radiolisp.frontend.effects` runs them."
  (:require [clojure.string :as str]
            [radiolisp.common.favorites :as favs]
            [radiolisp.frontend.views.art :as art]))

(def min-query-length 2)
(def debounce-ms 300)
(def connect-timeout-ms 15000)
(def toast-ms 2500)
(def import-accept
  "M3U, the list of uuids and Radiolise exports (`.txt`)."
  ".m3u,.m3u8,.list,.txt,audio/x-mpegurl,audio/mpegurl")

(defn query-active?
  "True when `query` is long enough to search for."
  [query]
  (>= (count (str/trim (or query ""))) min-query-length))

(defn visible-stations
  "The stations the palette lists right now."
  [{:keys [status results]}]
  (if (= :done status) results []))

(defn filtered-favorites
  "The favourites the grid shows under the current filter."
  [{:keys [favorites stations] text :filter}]
  (favs/matching (:items favorites) stations text))

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

(defn- add-effects [result & effects]
  (update result :effects into effects))

(defn- save
  "Writes the favourites of `result` to storage."
  [result]
  (add-effects result [:storage/save (get-in result [:state :favorites :items])]))

(defn- toast [result & messages]
  (then result [:toast/show (vec messages)]))

(defmethod handle :default [state action]
  (ok state [[:log/warn "Unhandled action" action]]))

(defn- current-server [state]
  (let [{:keys [servers current]} (:api state)]
    (get servers current)))

(defn- station-by-id [state id]
  (get-in state [:stations id]))

(defn- items [state]
  (get-in state [:favorites :items]))

(defn- favorite-name [state id]
  (or (:name (station-by-id state id))
      (:name (first (filter #(= id (:id %)) (items state))))))

;; ---------------------------------------------------------------- app

(defn- volume-effect [{:keys [volume muted?]}]
  [:audio/volume (if muted? 0 (/ volume 100))])

(defmethod handle :app/init [state [_ {:keys [locale]}]]
  (ok (assoc state :locale locale)
      [[:dom/lang locale]
       (volume-effect (:player state))
       [:storage/load]
       [:storage/persist]
       [:api/servers]]))

(defmethod handle :api/servers-loaded [state [_ servers]]
  (cond-> (ok (assoc state :api {:servers (vec servers) :current 0}))
    true                            (then [:stations/fetch])
    (get-in state [:search :open?]) (then [:search/run])))

(defn- next-server [state]
  (let [{:keys [servers current]} (:api state)]
    (assoc-in state [:api :current] (mod (inc current) (count servers)))))

(defn- can-retry? [state retried?]
  (and (not retried?) (> (count (get-in state [:api :servers])) 1)))

;; ---------------------------------------------------------------- favourites: loading

(defmethod handle :favorites/loaded [state [_ loaded]]
  (-> (ok (update state :favorites assoc
                  :items (vec loaded)
                  :status (if (seq loaded) :pending :done)))
      (then [:stations/fetch])))

(defmethod handle :stations/fetch [state _]
  (let [server (current-server state)]
    (if (and server (= :pending (get-in state [:favorites :status])))
      (ok (assoc-in state [:favorites :status] :loading)
          [[:api/stations-by-id {:server server :ids (mapv :id (items state))}]])
      (ok state))))

(defn- by-id [stations]
  (zipmap (map :id stations) stations))

(defmethod handle :stations/loaded [state [_ stations]]
  (let [found (by-id stations)]
    (ok (-> state
            (update :stations merge found)
            (update :favorites assoc
                    :status :done
                    :retried? false
                    :missing (set (remove found (map :id (items state)))))))))

(defmethod handle :stations/failed [state _]
  (if (can-retry? state (get-in state [:favorites :retried?]))
    (-> (ok (-> (next-server state)
                (update :favorites assoc :status :pending :retried? true)))
        (then [:stations/fetch]))
    (-> (ok (update state :favorites assoc :status :error :retried? false))
        (toast [:error/stations {}]))))

;; ---------------------------------------------------------------- player

(defn- active? [player]
  (contains? #{:playing :connecting} (:status player)))

(defn- start-playback
  "Connects to the station in the player and arms the connect timeout."
  [state]
  (let [{:keys [url hls?]} (get-in state [:player :station])]
    (ok (update state :player assoc :status :connecting :error nil)
        [[:audio/play url hls?]
         [:timer/start :connect connect-timeout-ms [:audio/timeout]]])))

(defmethod handle :player/toggle [state _]
  (let [{:keys [station] :as player} (:player state)]
    (cond
      (nil? station)
      (ok state)

      (active? player)
      (ok (assoc-in state [:player :status] :paused)
          [[:audio/pause] [:timer/cancel :connect]])

      :else
      (start-playback state))))

(defn- play-station
  "Plays `station`, or pauses it when it is already the one in the player.
  Starting a station counts a click for it in Radio Browser."
  [state station]
  (cond
    (nil? station)
    (ok state)

    (= (:id station) (get-in state [:player :station :id]))
    (handle state [:player/toggle])

    :else
    (-> (start-playback (assoc-in state [:player :station] station))
        (add-effects [:api/click {:server (current-server state) :id (:id station)}]
                     [:media/update {:title (:name station) :artwork (:favicon station)}]))))

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

(defmethod handle :audio/playing [state _]
  (ok (update state :player assoc :status :playing :error nil)
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

(defn- fail [state error]
  (ok (update state :player assoc :status :error :error error)
      [[:timer/cancel :connect]]))

(defmethod handle :audio/failed [state _]
  (if (status-in? state #{:playing :connecting})
    (fail state [:error/stream {}])
    (ok state)))

(defmethod handle :audio/unsupported [state _]
  (if (status-in? state #{:playing :connecting})
    (add-effects (fail state [:error/unsupported {}]) [:audio/stop])
    (ok state)))

(defmethod handle :audio/timeout [state _]
  (if (status-in? state #{:connecting})
    (ok (update state :player assoc :status :error :error [:error/stream {}])
        [[:audio/stop]])
    (ok state)))

;; ---------------------------------------------------------------- favourites: playing

(defmethod handle :favorites/play [state [_ id]]
  (play-station state (station-by-id state id)))

(defmethod handle :favorites/play-key [state [_ k]]
  (if-let [id (favs/key-owner (items state) k)]
    (handle state [:favorites/play id])
    (ok state)))

(defmethod handle :favorites/play-next [state [_ delta]]
  (let [ids     (filterv #(station-by-id state %) (map :id (items state)))
        current (get-in state [:player :station :id])
        i       (.indexOf ids current)
        next-id (when (seq ids)
                  (get ids (if (neg? i) 0 (mod (+ i delta) (count ids)))))]
    (if (or (nil? next-id) (= next-id current))
      (ok state)
      (handle state [:favorites/play next-id]))))

;; ---------------------------------------------------------------- favourites: editing

(defmethod handle :favorites/toggle [state [_ id]]
  (let [station (station-by-id state id)]
    (cond
      (favs/member? (items state) id)
      (handle state [:dialog/open-remove id])

      station
      (-> (ok (update-in state [:favorites :items] favs/add station))
          save
          (toast [:toast/added {:name (:name station)}]))

      :else
      (ok state))))

(defn- close-dialog [result]
  (if (get-in result [:state :dialog])
    (-> result
        (assoc-in [:state :dialog] nil)
        (add-effects [:dom/close-dialog "dialog"]))
    result))

(defmethod handle :favorites/remove [state [_ id]]
  (let [station-name (favorite-name state id)]
    (-> (ok (update-in state [:favorites :items] favs/remove id))
        close-dialog
        save
        (toast [:toast/removed {:name station-name}]))))

(defmethod handle :favorites/move-before [state [_ id target-id]]
  (-> (ok (update-in state [:favorites :items] favs/move-before id target-id))
      save))

(defmethod handle :favorites/move-to-front [state [_ id]]
  (-> (ok (update-in state [:favorites :items] favs/move-to-front id))
      save
      (toast [:toast/moved {:name (favorite-name state id)}])))

;; ---------------------------------------------------------------- drag and drop

(defmethod handle :drag/start [state [_ id]]
  (ok (assoc state :drag {:id id :over nil})))

(defmethod handle :drag/over [state [_ target]]
  (if (and (:drag state) (not= target (get-in state [:drag :over])))
    (ok (assoc-in state [:drag :over] target))
    (ok state)))

(defmethod handle :drag/drop [state [_ target]]
  (if-let [id (get-in state [:drag :id])]
    (-> (ok (assoc state :drag nil))
        (then [:favorites/move-before id (when-not (= :end target) target)]))
    (ok state)))

(defmethod handle :drag/end [state _]
  (ok (assoc state :drag nil)))

;; ---------------------------------------------------------------- filter

(defmethod handle :filter/changed [state [_ text]]
  (ok (assoc state :filter text)))

(defmethod handle :filter/focus [state _]
  (ok (assoc state :filter-open? true) [[:dom/focus :filter-input]]))

(defmethod handle :filter/clear [state _]
  (ok (assoc state :filter "" :filter-open? false) [[:dom/blur :filter-input]]))

(defmethod handle :filter/blurred [state _]
  (if (str/blank? (:filter state))
    (ok (assoc state :filter-open? false))
    (ok state)))

(defmethod handle :filter/play-first [state _]
  (if-let [{:keys [id]} (first (filtered-favorites state))]
    (handle state [:favorites/play id])
    (ok state)))

;; ---------------------------------------------------------------- palette (search Radio Browser)

(defn- fetch-effect [state request]
  (let [query (get-in state [:search :query])]
    [:api/fetch {:server (current-server state)
                 :query  (when (query-active? query) (str/trim query))
                 :seq    request}]))

(defmethod handle :search/open [state [_ query]]
  (let [opened (ok (update state :search assoc :open? true :mode :insert)
                   [[:dom/focus :search-input]])]
    (cond
      (some? query)                                (then opened [:search/typed query])
      (= :idle (get-in state [:search :status]))   (then opened [:search/run])
      :else                                        opened)))

(defmethod handle :search/close [state _]
  (ok (update state :search assoc :open? false :mode :insert)
      [[:timer/cancel :search]]))

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
      (ok (-> state
              (update :stations merge (by-id stations))
              (update :search
                      (fn [search]
                        (cond-> (assoc search :status :done :results (vec stations)
                                       :highlight 0 :error nil :retried? false)
                          top? (assoc :top (vec stations))))))))))

(defmethod handle :search/failed [state [_ request at]]
  (let [search (:search state)]
    (cond
      (not= request (:seq search))
      (ok state)

      (can-retry? state (:retried? search))
      (let [state (-> (next-server state)
                      (assoc-in [:search :retried?] true))]
        (ok state [(fetch-effect state request)]))

      :else
      (ok (update state :search assoc :status :error :retried? false
                  :error [:error/network {:at at}])))))

(defn- chosen [state index]
  (let [{:keys [highlight] :as search} (:search state)]
    (get (visible-stations search) (or index highlight))))

(defmethod handle :search/choose [state [_ index]]
  (let [state (cond-> state index (assoc-in [:search :highlight] index))]
    (play-station state (chosen state index))))

(defmethod handle :search/toggle-favorite [state [_ index]]
  (if-let [station (chosen state index)]
    (handle state [:favorites/toggle (:id station)])
    (ok state)))

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

;; ---------------------------------------------------------------- menus

(defmethod handle :menu/open [state [_ menu]]
  (if (= (:anchor menu) (get-in state [:menu :anchor]))
    (handle state [:menu/close])
    (ok (assoc state :menu menu) [[:dom/place-menu (:anchor menu)]])))

(defmethod handle :menu/close [state _]
  (if-let [{:keys [anchor]} (:menu state)]
    (ok (assoc state :menu nil) [[:dom/focus anchor]])
    (ok state)))

(defmethod handle :menu/move [state [_ delta]]
  (ok state [[:dom/focus-step "menu" delta]]))

;; ---------------------------------------------------------------- dialogs

(defmethod handle :dialog/open-remove [state [_ id]]
  (ok (assoc state :dialog {:kind :remove :id id}) [[:dom/show-dialog "dialog"]]))

(defmethod handle :dialog/open-key [state [_ id]]
  (ok (assoc state :dialog {:kind :key :id id :pending nil :message nil})
      [[:dom/show-dialog "dialog"]]))

(defmethod handle :dialog/close [state _]
  (close-dialog (ok state)))

(defn- key-label [k]
  (if (= " " k) "␣" k))

(defmethod handle :key-dialog/pressed [state [_ k]]
  (let [{:keys [id]} (:dialog state)
        owner        (favs/key-owner (items state) k)]
    (ok (update state :dialog merge
                (cond
                  (not= 1 (count k))
                  {:message [:key/invalid {}]}

                  (favs/reserved-key? k)
                  {:message [:key/reserved {:key (key-label k)}]}

                  :else
                  {:pending k
                   :message (when (and owner (not= owner id))
                              [:key/taken {:name (favorite-name state owner)}])})))))

(defmethod handle :key-dialog/save [state _]
  (let [{:keys [id pending]} (:dialog state)]
    (if pending
      (-> (ok (update-in state [:favorites :items] favs/assign-key id pending))
          close-dialog
          save
          (toast [:toast/key-saved {:key pending :name (favorite-name state id)}]))
      (ok state))))

(defmethod handle :key-dialog/clear [state _]
  (let [{:keys [id]} (:dialog state)]
    (-> (ok (update-in state [:favorites :items] favs/clear-key id))
        close-dialog
        save)))

;; ---------------------------------------------------------------- station menu

(defmethod handle :station/vote [state [_ id]]
  (if-let [server (current-server state)]
    (ok state [[:api/vote {:server server :id id}]])
    (ok state)))

(defmethod handle :vote/done [state [_ id voted?]]
  (if voted?
    (-> (ok (cond-> state
              (station-by-id state id) (update-in [:stations id :votes] inc)))
        (toast [:toast/voted {:name (favorite-name state id)}]))
    (toast (ok state) [:toast/vote-failed {}])))

(defmethod handle :station/copy-url [state [_ id]]
  (if-let [url (:url (station-by-id state id))]
    (ok state [[:clipboard/write url]])
    (ok state)))

;; ---------------------------------------------------------------- export and import

(defmethod handle :dialog/open-export [state _]
  (ok (assoc state :dialog {:kind :export :format :m3u :stem favs/default-file-stem})
      [[:dom/show-dialog "dialog"]]))

(defmethod handle :export-dialog/format [state [_ format]]
  (ok (cond-> state
        (contains? favs/formats format) (assoc-in [:dialog :format] format))))

(defmethod handle :export-dialog/stem [state [_ stem]]
  (ok (assoc-in state [:dialog :stem] stem)))

(defn- export-file
  "The file for `format`, and how many favourites it carries: M3U needs a
  known stream, the list only the uuid."
  [state format]
  (let [{:keys [stations]} state
        favorites          (items state)]
    (case format
      :m3u  {:text     (favs/->m3u favorites stations)
             :exported (count (filter #(get-in stations [(:id %) :url]) favorites))}
      :list {:text     (favs/->list favorites)
             :exported (count favorites)})))

(defmethod handle :export-dialog/save [state _]
  (let [{:keys [format stem]} (:dialog state)
        {:keys [text exported]} (export-file state format)]
    (-> (ok state [[:file/download {:filename (favs/export-filename stem format)
                                    :mime     (get-in favs/formats [format :mime])
                                    :text     text}]])
        close-dialog
        (toast [:toast/exported {:count exported}]))))

(defmethod handle :favorites/import-pick [state _]
  (ok state [[:file/pick {:accept import-accept :action :import/read}]]))

(defn- entries->request
  "Entries with a uuid are looked up by it; the rest by their stream URL."
  [entries]
  {:ids  (vec (distinct (keep :id entries)))
   :urls (vec (distinct (map :url (remove :id entries))))})

(defn- import-request
  "`{:ids :urls}` to resolve from the text of a file in `format`."
  [format text]
  (case format
    :m3u       (entries->request (favs/parse-m3u text))
    :radiolise (entries->request (favs/parse-radiolise text))
    :list      {:ids (favs/parse-list text) :urls []}))

(defmethod handle :import/read [state [_ text filename]]
  (let [format (favs/file-format filename)
        server (current-server state)
        {:keys [ids urls] :as request} (when format (import-request format text))]
    (cond
      (nil? format)                    (toast (ok state) [:toast/import-unsupported {}])
      (and (empty? ids) (empty? urls)) (toast (ok state) [:toast/import-empty {}])
      (nil? server)                    (toast (ok state) [:toast/import-failed {}])
      :else                            (ok state [[:api/import (assoc request :server server)]]))))

(defmethod handle :import/resolved [state [_ {:keys [stations missing]}]]
  (let [before (count (items state))
        state  (-> state
                   (update :stations merge (by-id stations))
                   (update-in [:favorites :items] #(reduce favs/add % stations))
                   (update-in [:favorites :missing] #(apply disj % (map :id stations))))
        added  (- (count (items state)) before)]
    (apply toast (save (ok state))
           (cond-> [[:toast/imported {:count added}]]
             (pos? missing) (conj [:toast/not-found {:count missing}])))))

(defmethod handle :import/failed [state _]
  (toast (ok state) [:toast/import-failed {}]))

;; ---------------------------------------------------------------- toast, art, misc

(defmethod handle :toast/show [state [_ messages]]
  (ok (assoc state :toast messages) [[:timer/start :toast toast-ms [:toast/hide]]]))

(defmethod handle :toast/hide [state _]
  (ok (assoc state :toast nil)))

(defmethod handle :art/sized [state [_ id w h]]
  (ok (assoc-in state [:art id] (art/fit w h))))

(defmethod handle :art/failed [state [_ id]]
  (ok (assoc-in state [:art id] :broken)))

(defmethod handle :i18n/set [state [_ locale]]
  (ok (assoc state :locale locale) [[:dom/lang locale]]))

(defmethod handle :help/open [state _]
  (ok (assoc state :help? true) [[:dom/show-dialog "help"]]))

(defmethod handle :help/close [state _]
  (ok (assoc state :help? false)))
