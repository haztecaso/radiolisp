(ns radiolisp.frontend.views.shell
  (:require [clojure.string :as str]
            [radiolisp.common.favorites :as favs]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.dialogs :as dialogs]
            [radiolisp.frontend.views.grid :as grid]
            [radiolisp.frontend.views.help :as help]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.menu :as menu]
            [radiolisp.frontend.views.panel :as panel]
            [radiolisp.frontend.views.player :as player]
            [radiolisp.frontend.views.search :as search]))

(defn- translator
  "`t` bound to one locale, so views call `(t :key)` or `(t :key vars)`."
  [locale]
  (fn
    ([k] (i18n/t locale k))
    ([k vars] (i18n/t locale k vars))))

(defn page-title
  "The browser tab's title: the station on air before the app's name, or
  the app's name alone when nothing plays."
  [{:keys [player favorites locale]}]
  (let [{:keys [station status]} player
        app (i18n/t locale :brand/name)]
    (if (and station (contains? #{:playing :connecting :reconnecting} status))
      (str (favs/display-name (favs/find-favorite (:items favorites) (:id station)) station) " - " app)
      app)))

(defn- filter-field
  "A funnel button that unfolds into the filter field, which folds back once
  it is left empty."
  [{text :filter :keys [filter-open?]} t]
  (if filter-open?
    [:label.filter
     [:span.vh (t :filter/label)]
     icons/funnel
     [:input#filter-input {:type         "search"
                           :value        text
                           :placeholder  (t :filter/label)
                           :autocomplete "off"
                           :spellcheck   "false"
                           :on           {:input [[:filter/changed :event.target/value]]
                                          :blur  [[:filter/blurred]]}}]]
    [:button.icon-btn {:aria-label (t :filter/label)
                       :title      (str (t :filter/label) " (/)")
                       :on         {:click [[:filter/focus]]}}
     icons/funnel]))

(defn- top-bar [{:keys [menu] :as state} t]
  [:header.top
   [:button.brand {:title (t :brand/top) :on {:click [[:brand/top]]}} icons/mark [:b (t :brand/name)]]
   [:div.tools
    [:button.icon-btn.add {:aria-label (t :search/open)
                           :title      (str (t :search/open) " (a)")
                           :on         {:click [[:search/open]]}}
     icons/plus]
    (filter-field state t)]
   [:div.top-actions
    [:button#list-menu.icon-btn {:aria-label    (t :menu/list)
                                 :aria-haspopup "menu"
                                 :aria-expanded (str (= "list-menu" (:anchor menu)))
                                 :on            {:click [[:menu/open {:kind :list :anchor "list-menu"}]]}}
     icons/dots]]])

(defn- toast [{messages :toast} t]
  [:div.toast {:role "status" :class (when messages "show")}
   (str/join " · " (map (fn [[k vars]] (t k vars)) messages))])

(defn- drop-zone [{:keys [file-drag?]} t]
  [:div.drop-zone {:class (when file-drag? "show") :aria-hidden "true"}
   [:div.drop-box
    icons/upload
    [:b (t :drop/title)]
    [:span (t :drop/hint)]]])

(defn app [{:keys [locale] :as state}]
  (let [t (translator locale)]
    [:div.app
     (top-bar state t)
     [:main (grid/view state t)]
     (player/view state t)
     (panel/view state t)
     (search/view state t)
     (menu/view state t)
     (toast state t)
     (dialogs/view state t)
     (drop-zone state t)
     (help/view t)]))
