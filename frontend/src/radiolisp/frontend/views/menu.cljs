(ns radiolisp.frontend.views.menu
  "Pop-up menus: the list menu (⋯ in the top bar) and a station's menu (⋯ on
  its tile). `effects` places it next to its button; a transparent scrim
  behind it closes it on any outside click."
  (:require [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.icons :as icons]))

(def edit-url "https://www.radio-browser.info/history/")

(defn- item
  ([icon label actions] (item icon label actions nil))
  ([icon label actions extra]
   [:button {:role "menuitem" :on {:click (into [[:menu/close]] actions)}}
    icon [:span label] extra]))

(defn- link [icon label href]
  [:a {:role "menuitem" :href href :target "_blank" :rel "noopener" :on {:click [[:menu/close]]}}
   icon [:span label]])

(defn- health [{:keys [locale]} t {:keys [check-ok? last-check-at last-ok-at]}]
  (let [date #(or (i18n/format-date locale %) "—")]
    [:div.info {:class (when-not check-ok? "bad")}
     icons/pulse
     (cond
       (nil? last-check-at) (t :health/unknown)
       check-ok?            (t :health/ok {:date (date last-check-at)})
       :else                (t :health/failed {:date (date last-check-at) :ok (date last-ok-at)}))]))

(defn- station-items [{:keys [stations favorites locale] :as state} t id]
  (let [station  (get stations id)
        shortcut (:key (first (filter #(= id (:id %)) (:items favorites))))]
    (list
     (when-let [homepage (:homepage station)]
       (link icons/external (t :menu/homepage) homepage))
     (when station
       (item icons/thumb (t :menu/vote) [[:station/vote id]]
             [:span.meta.num (i18n/format-number locale (:votes station))]))
     (item icons/key-icon (t (if shortcut :menu/change-key :tile/assign-key)) [[:dialog/open-key id]]
           (when shortcut [:span.meta shortcut]))
     (item icons/up (t :menu/move-to-front) [[:favorites/move-to-front id]])
     (when (:url station)
       (item icons/link (t :menu/copy-url) [[:station/copy-url id]]))
     (link icons/edit (t :menu/edit) (str edit-url id))
     (when station
       (list [:hr] (health state t station)))
     [:hr]
     [:button.danger {:role "menuitem" :on {:click [[:menu/close] [:dialog/open-remove id]]}}
      icons/trash [:span (t :fav/remove)]])))

(defn- list-items [t]
  (list
   (item icons/download (t :menu/export) [[:favorites/export]])
   (item icons/upload (t :menu/import) [[:favorites/import-pick]])))

(defn view [{:keys [menu] :as state} t]
  (when-let [{:keys [kind id]} menu]
    (list
     [:div.menu-scrim {:replicant/key "scrim" :on {:click [[:menu/close]]}}]
     [:div#menu.menu {:replicant/key "menu"
                      :role          "menu"
                      :aria-label    (t (if (= :list kind) :menu/list :tile/options)
                                        {:name (get-in state [:stations id :name])})}
      (if (= :list kind)
        (list-items t)
        (station-items state t id))])))
