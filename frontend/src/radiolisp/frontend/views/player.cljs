(ns radiolisp.frontend.views.player
  "The player: a bar fixed at the bottom."
  (:require [radiolisp.common.favorites :as favs]
            [radiolisp.frontend.actions :as actions]
            [radiolisp.frontend.views.art :as art]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.station :as sv]))

(defn- meta-line
  "Line 2 of the bar: what is going on now."
  [t {:keys [station status error now-playing retries]}]
  (let [title (:title now-playing)]
    [:div.meta-line {:aria-live "polite"}
     (cond
       (nil? station)                   [:span.tags (t :player/idle-hint)]
       (= :error status)                [:span.err-line (apply t error)]
       (= :reconnecting status)         [:span.wait (t :player/reconnecting {:n   retries
                                                                             :max (count actions/reconnect-delays-ms)})]
       (= :connecting status)           [:span.wait (t :player/connecting)]
       (and title (= :playing status))  [:span.now-title {:title title} title]
       :else                            [:span.tags (sv/tags-line station)])]))

(defn- volume-control [t {:keys [volume muted?]}]
  (let [shown (if muted? 0 volume)]
    [:div.vol.hide-s
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

(defn view [{:keys [player favorites panel?] :as state} t]
  (let [{:keys [station status]} player
        {:keys [id]} station
        favorite     (when station (favs/find-favorite (:items favorites) id))
        station-name (when station (favs/display-name favorite station))
        active?      (contains? #{:playing :connecting :reconnecting} status)]
    [:footer#player.bar {:class      [(when (= :playing status) "is-playing")
                                      (when-not station "idle")]
                         :aria-label (t :player/label)}
     [:button#now-btn.now-btn {:disabled      (nil? station)
                               :aria-expanded (str (boolean panel?))
                               :aria-controls "panel"
                               :title         (when station (t :panel/title))
                               :on            {:click [[:panel/toggle]]}}
      (if station
        (art/view state (assoc station :name station-name :display (:display favorite) :color (:color favorite)) "thumb")
        [:div.thumb])
      [:div.now
       [:b (when station icons/waves)
        [:span.nm (or station-name (t :player/idle-title))]
        (when station icons/chevron-up)]
       (meta-line t player)]]
     [:div.ctrls
      (volume-control t player)
      [:button#player-toggle.toggle
       {:disabled   (nil? station)
        :aria-label (t (if active? :player/pause :player/play) {:name (or station-name "")})
        :on         {:click [[:player/toggle]]}}
       (if active? icons/pause icons/play)]]]))
