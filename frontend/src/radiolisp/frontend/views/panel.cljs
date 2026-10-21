(ns radiolisp.frontend.views.panel
  "«Sonando ahora»: what the bar has no room for. A popover over the bar on
  wide screens, a sheet over it on phones. Opened by the bar's station
  button (:panel/toggle); Esc, × or a click outside close it."
  (:require [radiolisp.common.favorites :as favs]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.art :as art]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.station :as sv]))

(defn- actions [{:keys [locale]} t {:keys [id homepage votes url]} favorite?]
  [:div.panel-actions
   (when homepage
     [:a {:href homepage :target "_blank" :rel "noopener"} icons/globe [:span (t :panel/web)]])
   [:button {:class        (when favorite? "on")
             :aria-pressed (str favorite?)
             :on           {:click [[:favorites/toggle id]]}}
    (if favorite? icons/heart-on icons/heart) [:span (t :panel/favorite)]]
   [:button {:on {:click [[:station/vote id]]}}
    icons/thumb [:span (t :menu/vote)]
    (when votes [:span.num (i18n/format-number locale votes)])]
   (when url
     [:button {:on {:click [[:station/copy-url id]]}} icons/link [:span (t :panel/copy)]])])

(defn view [{:keys [player favorites stations panel? locale] :as state} t]
  (when-let [{:keys [id] :as station} (when panel? (:station player))]
    (let [station      (merge station (get stations id)) ; votes go up after voting
          favorite     (favs/find-favorite (:items favorites) id)
          station-name (favs/display-name favorite station)
          title        (get-in player [:now-playing :title])
          {:keys [country tags]} station]
      (list
       [:div.panel-scrim {:replicant/key "panel-scrim" :on {:click [[:panel/close]]}}]
       [:section#panel.panel {:replicant/key   "panel"
                              :role            "dialog"
                              :aria-labelledby "panel-title"}
        [:div.panel-head
         (art/view state (assoc station :name station-name :display (:display favorite)) "art")
         [:div.panel-title
          [:h2#panel-title station-name]
          [:div.panel-playing [:small (t :panel/playing)]
           (if title [:span title] [:span.none (t :panel/no-title)])]]
         [:button#panel-close.icon-btn {:aria-label (t :panel/close) :on {:click [[:panel/close]]}}
          icons/close]]
        [:div.panel-meta
         [:div.facts (sv/quality station)
          (when country [:span (i18n/country-name locale country)])]
         (when (seq tags)
           [:div.chips (for [tag tags] [:span {:replicant/key tag} tag])])]
        (actions state t station (some? favorite))]))))
