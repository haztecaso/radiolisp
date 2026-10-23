(ns radiolisp.frontend.views.menu
  "Pop-up menus: the list menu (⋯ in the top bar) and a station's menu (⋯ on
  its tile). The list menu also holds the theme and the language, which opens
  as a second page of the same menu. `effects` places it next to its button;
  a transparent scrim behind it closes it on any outside click."
  (:require [radiolisp.common.favorites :as favs]
            [radiolisp.frontend.config :as config]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.icons :as icons]))

(def radio-browser-url "https://www.radio-browser.info/history/")

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
        {shortcut :key} (favs/find-favorite (:items favorites) id)]
    (list
     (when-let [homepage (:homepage station)]
       (link icons/globe (t :menu/homepage) homepage))
     (when station
       (item icons/thumb (t :menu/vote) [[:station/vote id]]
             [:span.meta.num (i18n/format-number locale (:votes station))]))
     (item icons/key-icon (t (if shortcut :menu/change-key :tile/assign-key)) [[:dialog/open-key id]]
           (when shortcut [:span.meta shortcut]))
     (item icons/sliders (t :menu/customize) [[:customize/open id]])
     (item icons/up (t :menu/move-to-front) [[:favorites/move-to-front id]])
     (when (:url station)
       (item icons/link (t :menu/copy-url) [[:station/copy-url id]]))
     (link icons/external (t :menu/radio-browser) (str radio-browser-url id))
     (when station
       (list [:hr] (health state t station)))
     [:hr]
     [:button.danger {:role "menuitem" :on {:click [[:menu/close] [:dialog/open-remove id]]}}
      icons/trash [:span (t :fav/remove)]])))

(defn- list-items [{:keys [theme]} t]
  (let [dark? (= :dark (:current theme))]
    (list
     (item icons/download (t :menu/export) [[:dialog/open-export]])
     (item icons/upload (t :menu/import) [[:favorites/import-pick]])
     [:hr]
     (item (if dark? icons/sun icons/moon) (t (if dark? :theme/to-light :theme/to-dark)) [[:theme/toggle]])
     [:button.sub {:role "menuitem" :aria-haspopup "menu" :on {:click [[:menu/page :language]]}}
      icons/globe [:span (t :lang/label)] [:span.meta (t :lang/name)] icons/chevron-right]
     [:hr]
     (item icons/key-icon (t :help/title) [[:help/open]] [:span.meta "?"])
     (link icons/code (t :menu/source) config/repository))))

(defn- language-items
  "Each language is listed by its own name, so it can be found from any other."
  [{:keys [locale]} t]
  (list
   [:button {:role "menuitem" :on {:click [[:menu/page nil]]}}
    icons/chevron-left [:span (t :lang/label)]]
   [:hr]
   (for [l i18n/locales]
     [:button {:replicant/key l
               :role          "menuitemradio"
               :aria-checked  (str (= l locale))
               :lang          (name l)
               :on            {:click [[:menu/close] [:i18n/set l]]}}
      [:span (i18n/t l :lang/name)]
      (when (= l locale) [:span.meta icons/check])])))

(defn view [{:keys [menu stations favorites] :as state} t]
  (when-let [{:keys [kind id page]} menu]
    (list
     [:div.menu-scrim {:replicant/key "scrim" :on {:click [[:menu/close]]}}]
     [:div#menu.menu {:replicant/key "menu"
                      :role          "menu"
                      :aria-label    (cond
                                       (= :language page) (t :lang/label)
                                       (= :list kind)     (t :menu/list)
                                       :else              (t :tile/options {:name (favs/display-name
                                                                                   (favs/find-favorite (:items favorites) id)
                                                                                   (get stations id))}))}
      (cond
        (= :language page) (language-items state t)
        (= :list kind)     (list-items state t)
        :else              (station-items state t id))])))
