(ns radiolisp.frontend.views.grid
  "The favourites: a full-width grid of square tiles."
  (:require [clojure.string :as str]
            [radiolisp.frontend.actions :as actions]
            [radiolisp.frontend.views.art :as art]
            [radiolisp.frontend.views.icons :as icons]))

(defn- tile [{:keys [stations favorites player drag menu] :as state} t {:keys [id] shortcut :key stored-name :name}]
  (let [station      (get stations id)
        station-name (or (:name station) stored-name)
        missing?     (contains? (:missing favorites) id)
        sick?        (false? (:check-ok? station))
        current?     (= id (get-in player [:station :id]))
        anchor       (str "more-" id)]
    [:article.tile {:replicant/key id
                    :class         [(when current? "playing")
                                    (when (= id (:id drag)) "dragging")
                                    (when (and drag (= id (:over drag)) (not= id (:id drag))) "drop-before")]
                    :draggable     "true"
                    :on            {:dragstart [[:drag/start id]]
                                    :dragover  [[:drag/over id]]
                                    :drop      [[:drag/drop id]]
                                    :dragend   [[:drag/end]]}}
     [:button.play {:aria-label (t (if (and current? (#{:playing :connecting} (:status player)))
                                     :player/pause
                                     :player/play)
                                   {:name station-name})
                    :disabled   (nil? station)
                    :on         {:click [[:favorites/play id]]}}
      (art/view (:art state) {:id id :name station-name :favicon (:favicon station)} "art")
      [:span.name {:title station-name} station-name]]
     [:button.keycap {:class      (when-not shortcut "empty")
                      :aria-label (if shortcut (t :tile/key {:key shortcut}) (t :tile/assign-key))
                      :title      (if shortcut (t :menu/change-key) (t :tile/assign-key))
                      :on         {:click [[:dialog/open-key id]]}}
      (or shortcut "+")]
     [:button.more {:id            anchor
                    :aria-label    (t :tile/options {:name station-name})
                    :aria-haspopup "menu"
                    :aria-expanded (str (= anchor (:anchor menu)))
                    :on            {:click [[:menu/open {:kind :station :id id :anchor anchor}]]}}
      icons/dots]
     (cond
       missing? [:span.flag (t :tile/gone)]
       sick?    [:span.flag (t :tile/no-signal)])]))

(defn- add-tile [{:keys [drag]} t]
  [:button.tile.add-tile {:replicant/key "add"
                          :class         (when (= :end (:over drag)) "drop-before")
                          :on            {:click    [[:search/open]]
                                          :dragover [[:drag/over :end]]
                                          :drop     [[:drag/drop :end]]}}
   [:span icons/plus (t :search/open)]])

(defn view [{:keys [favorites] text :filter :as state} t]
  (let [items  (:items favorites)
        shown  (actions/filtered-favorites state)
        query  (str/trim text)]
    [:section.favs {:aria-labelledby "favs-title"}
     [:h1#favs-title.vh (t :grid/title)]
     (cond
       (= :idle (:status favorites))
       nil

       (empty? items)
       [:div.empty-state
        [:b (t :grid/empty-title)]
        [:span (t :grid/empty-hint)]
        [:div.empty-actions
         [:button.btn.primary {:on {:click [[:search/open]]}} icons/plus (t :search/open)]
         [:button.btn {:on {:click [[:favorites/import-pick]]}} icons/upload (t :grid/empty-import)]]]

       (empty? shown)
       [:div.empty-state
        [:b (t :grid/no-match {:query query})]
        [:button.text-btn {:on {:click [[:search/open query]]}} (t :grid/search-for {:query query})]]

       :else
       [:div.grid
        (for [favorite shown] (tile state t favorite))
        (when (str/blank? query) (add-tile state t))])]))
