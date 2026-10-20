(ns radiolisp.frontend.views.search
  (:require [clojure.string :as str]
            [radiolisp.frontend.actions :as actions]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.station :as sv]))

(defn- list-head [t {:keys [mode]} label]
  [:div.list-head
   [:span label]
   [:span.mode (t (if (= :normal mode) :search/mode-normal :search/mode-insert))]])

(defn- station-row [t {:keys [highlight]} playing-id index station]
  [:li.st {:id            (str "st-" index)
           :replicant/key (:id station)
           :role          "option"
           :aria-selected (str (= index highlight))
           :class         [(when (= index highlight) "hl")
                           (when (= playing-id (:id station)) "on")]
           :on            {:click [[:search/choose index]]}}
   [:span.n {:title (:name station)} (:name station)]
   [:span.f (sv/format-badge station)]
   [:span.m (sv/country-code station) " · "
    (t :station/listens {:count (:clicks station)}) " · "
    (sv/tags-line t station)]])

(defn- skeleton-rows []
  [:ul.results
   (for [i (range 5)]
     [:li.st.skeleton {:replicant/key i}
      [:span.skel {:style {:width "55%"}}]
      [:span.skel {:style {:width "56px"}}]
      [:span.m [:span.skel {:style {:width "35%"}}]]])])

(defn- body [{:keys [search player locale]} t]
  (let [{:keys [query status error highlight]} search
        stations (actions/visible-stations search)
        active?  (actions/query-active? query)]
    (cond
      (= :error status)
      (let [[message {:keys [at]}] error]
        [:div.note.error
         [:span [:span.dot] (t message {:time (i18n/format-time locale at)})]
         [:button.text-btn {:on {:click [[:search/run]]}} (t :error/retry)]])

      (= :loading status)
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
        (map-indexed (fn [i station] (station-row t search (get-in player [:station :id]) i station))
                     stations)]))))

(defn view [{:keys [search player] :as state} t]
  (if-not (:open? search)
    [:button#search-opener.opener {:on {:click [[:search/open]]}}
     icons/search [:span (t :search/open)]]
    [:section#panel.panel {:class      (when (= :normal (:mode search)) "normal")
                           :aria-label (t :search/label)}
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
      (when (:station player)
        [:button.icon-btn.close {:aria-label (t :search/close)
                                 :on         {:click [[:search/close]]}}
         icons/close])]
     (body state t)]))
