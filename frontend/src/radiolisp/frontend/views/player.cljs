(ns radiolisp.frontend.views.player
  (:require [radiolisp.common.format :as fmt]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.station :as sv]))

(defn- status-line [t {:keys [status error started-at]} now]
  (case status
    :error      [:div.status.err (t (first error))]
    :connecting [:div.status icons/waves [:span (t :player/connecting)]]
    :playing    [:div.status icons/waves
                 [:span.live (t :player/live)]
                 [:span.num.clock (fmt/clock (fmt/elapsed started-at now))]]
    :paused     [:div.status icons/waves [:span (t :player/paused)]]
    nil))

(defn view [{:keys [player now]} t]
  (let [{:keys [station status volume muted?]} player
        active? (contains? #{:playing :connecting} status)
        shown   (if muted? 0 volume)]
    [:section#player.player {:class     [(when (= :playing status) "is-playing")
                                         (when-not station "empty")]
                             :aria-live "polite"}
     (when station (status-line t player now))
     [:h1 {:title (:name station)}
      (if station (:name station) (t :player/idle-title))]
     [:p.facts
      (if station
        (list (sv/country-code station) " · " (sv/format-badge station) " · " (sv/tags-line t station))
        (t :player/idle-hint))]
     [:div.controls
      [:button#player-toggle.toggle
       {:disabled   (nil? station)
        :aria-label (t (if active? :player/pause :player/play) {:name (:name station "")})
        :on         {:click [[:player/toggle]]}}
       (if active? icons/pause icons/play)]
      [:button.icon-btn.mute
       {:aria-label (t (if muted? :player/unmute :player/mute))
        :on         {:click [[:player/mute]]}}
       (if muted? icons/mute icons/volume)]
      [:div.slider
       [:span.track]
       [:span.fill {:style {:width (str shown "%")}}]
       [:span.thumb {:style {:left (str "calc(" shown "% - 6px)")}}]
       [:input {:type       "range" :min "0" :max "100" :value (str shown)
                :aria-label (t :player/volume)
                :on         {:input [[:player/volume :event.target/value-as-number]]}}]]
      [:span.vol-val.num (str shown " %")]]]))
