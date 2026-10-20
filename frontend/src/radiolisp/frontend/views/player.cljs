(ns radiolisp.frontend.views.player
  "The player: a bar fixed at the bottom."
  (:require [radiolisp.common.favorites :as favs]
            [radiolisp.frontend.views.art :as art]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.station :as sv]))

(defn- meta-line [t {:keys [station status error]}]
  [:div.meta-line {:aria-live "polite"}
   (cond
     (nil? station)          [:span.tags (t :player/idle-hint)]
     (= :error status)       [:span.err-line (t (first error))]
     (= :connecting status)  [:span.tags (t :player/connecting)]
     :else                   (list (sv/quality station)
                                   [:span.tags (sv/tags-line station)]))])

(defn- volume-control [t {:keys [volume muted?]}]
  (let [shown (if muted? 0 volume)]
    [:div.vol
     [:div.slider
      [:div.slider-in
       [:span.track]
       [:span.fill {:style {:width (str shown "%")}}]
       [:span.knob {:style {:left (str "calc(" shown "% - 6px)")}}]
       [:input {:type       "range" :min "0" :max "100" :value (str shown)
                :aria-label (t :player/volume)
                :on         {:input [[:player/volume :event.target/value-as-number]]}}]]]
     [:button.icon-btn.mute
      {:aria-label (t (if muted? :player/unmute :player/mute))
       :title      (str (t :player/volume) " " shown " %")
       :on         {:click [[:player/mute]]}}
      (if muted? icons/mute icons/volume)]]))

(defn view [{:keys [player favorites] :as state} t]
  (let [{:keys [station status]} player
        {:keys [id homepage] station-name :name} station
        active?   (contains? #{:playing :connecting} status)
        favorite? (and station (favs/member? (:items favorites) id))]
    [:footer#player.bar {:class      [(when (= :playing status) "is-playing")
                                      (when-not station "idle")]
                         :aria-label (t :player/label)}
     (if station
       (art/view (:art state) station "thumb")
       [:div.thumb])
     [:div.now
      [:b (when station icons/waves)
       [:span {:title station-name} (or station-name (t :player/idle-title))]]
      (meta-line t player)]
     [:div.ctrls
      (when homepage
        [:a.icon-btn.hide-s {:href       homepage :target "_blank" :rel "noopener"
                             :aria-label (t :player/homepage {:name station-name})
                             :title      (t :menu/homepage)}
         icons/globe])
      (when station
        [:button.icon-btn.fav {:class        (when favorite? "on")
                               :aria-pressed (str favorite?)
                               :aria-label   (t (if favorite? :fav/remove :fav/add))
                               :title        (t (if favorite? :fav/remove :fav/add))
                               :on           {:click [[:favorites/toggle id]]}}
         (if favorite? icons/heart-on icons/heart)])
      (volume-control t player)
      [:button#player-toggle.toggle
       {:disabled   (nil? station)
        :aria-label (t (if active? :player/pause :player/play) {:name (or station-name "")})
        :on         {:click [[:player/toggle]]}}
       (if active? icons/pause icons/play)]]]))
