(ns radiolisp.frontend.views.search
  "The palette: a modal that searches the whole of Radio Browser."
  (:require [clojure.string :as str]
            [radiolisp.common.favorites :as favs]
            [radiolisp.frontend.actions :as actions]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.art :as art]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.station :as sv]))

(defn- list-head [t {:keys [mode]} label]
  [:div.list-head
   [:span label]
   [:span.mode (t (if (= :normal mode) :search/mode-normal :search/mode-insert))]])

(defn- station-row [{:keys [player favorites] :as state} t index {:keys [id] station-name :name :as station}]
  (let [highlight? (= index (get-in state [:search :highlight]))
        current?   (= id (get-in player [:station :id]))
        active?    (and current? (#{:playing :connecting} (:status player)))
        favorite?  (favs/member? (:items favorites) id)]
    [:li.row {:id            (str "st-" index)
              :replicant/key id
              :role          "option"
              :aria-selected (str highlight?)
              :class         [(when highlight? "hl") (when current? "playing")]
              :on            {:click [[:search/choose index]]}}
     (art/view (:art state) station "thumb")
     [:div.info
      [:b {:title station-name} station-name]
      [:div.meta-line (sv/quality station) [:span.tags (sv/tags-line station true)]]]
     [:div.acts
      [:button.icon-btn {:tabindex   "-1"
                         :aria-label (t (if active? :player/pause :player/play) {:name station-name})
                         :on         {:click [[:search/choose index]]}}
       (if active? icons/pause icons/play)]
      [:button.icon-btn.fav {:tabindex     "-1"
                             :class        (when favorite? "on")
                             :aria-pressed (str favorite?)
                             :aria-label   (t (if favorite? :fav/remove :fav/add))
                             :on           {:click [[:search/toggle-favorite index]]}}
       (if favorite? icons/heart-on icons/heart)]]]))

(defn- skeleton-rows []
  [:ul.results
   (for [i (range 5)]
     [:li.row.skeleton {:replicant/key i}
      [:span.thumb]
      [:div.info [:span.skel {:style {:width "55%"}}] [:span.skel {:style {:width "35%"}}]]])])

(defn- body [{:keys [search locale] :as state} t]
  (let [{:keys [query status error highlight]} search
        stations (actions/visible-stations search)
        active?  (actions/query-active? query)]
    (cond
      (= :error status)
      (let [[message {:keys [at]}] error]
        [:div.note.error
         [:span [:span.dot] (t message {:time (i18n/format-time locale at)})]
         [:button.text-btn {:on {:click [[:search/run]]}} (t :error/retry)]])

      (#{:loading :idle} status)
      (list (list-head t search (t :search/loading)) (skeleton-rows))

      (and active? (empty? stations))
      [:p.note (t :search/empty {:query (str/trim query)})]

      :else
      (list
       (list-head t search (if active?
                             (str (t :search/results {:count (count stations)}) " · " (t :search/order))
                             (t :search/top)))
       [:ul#search-list.results
        {:role                  "listbox"
         :tabindex              "-1"
         :aria-label            (t :search/label)
         :aria-activedescendant (str "st-" highlight)
         :on                    {:focus [[:search/mode :normal]]}}
        (map-indexed (fn [i station] (station-row state t i station)) stations)]))))

(defn view [{:keys [search] :as state} t]
  (when (:open? search)
    [:div.palette {:class (when (= :normal (:mode search)) "normal")}
     [:div.scrim {:on {:click [[:search/close]]}}]
     [:div.box {:role "dialog" :aria-modal "true" :aria-label (t :search/label)}
      [:div.field
       [:label.vh {:for "search-input"} (t :search/label)]
       icons/search
       [:input#search-input {:type         "search"
                             :value        (:query search)
                             :placeholder  (t :search/placeholder)
                             :autocomplete "off"
                             :spellcheck   "false"
                             :on           {:input [[:search/typed :event.target/value]]
                                            :focus [[:search/mode :insert]]}}]
       [:button.icon-btn.close {:aria-label (t :search/close)
                                :on         {:click [[:search/close]]}}
        icons/close]]
      (body state t)]]))
