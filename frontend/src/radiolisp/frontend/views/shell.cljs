(ns radiolisp.frontend.views.shell
  (:require [clojure.string :as str]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.dialogs :as dialogs]
            [radiolisp.frontend.views.grid :as grid]
            [radiolisp.frontend.views.help :as help]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.menu :as menu]
            [radiolisp.frontend.views.player :as player]
            [radiolisp.frontend.views.search :as search]))

(defn- translator
  "`t` bound to one locale, so views call `(t :key)` or `(t :key vars)`."
  [locale]
  (fn
    ([k] (i18n/t locale k))
    ([k vars] (i18n/t locale k vars))))

(defn- language-button [locale current]
  [:button.text-btn {:lang         (name locale)
                     :aria-pressed (str (= locale current))
                     :on           {:click [[:i18n/set locale]]}}
   (str/upper-case (name locale))])

(defn- top-bar [{:keys [locale menu] text :filter} t]
  [:header.top
   [:a.brand {:href "/"} icons/mark [:b (t :brand/name)]]
   [:label.filter
    [:span.vh (t :filter/label)]
    icons/search
    [:input#filter-input {:type         "search"
                          :value        text
                          :placeholder  (t :filter/label)
                          :autocomplete "off"
                          :spellcheck   "false"
                          :on           {:input [[:filter/changed :event.target/value]]}}]]
   [:div.top-actions
    [:button.btn {:on {:click [[:search/open]]}} icons/plus [:span.add-label (t :search/open)]]
    [:button#list-menu.icon-btn {:aria-label    (t :menu/list)
                                 :aria-haspopup "menu"
                                 :aria-expanded (str (= "list-menu" (:anchor menu)))
                                 :on            {:click [[:menu/open {:kind :list :anchor "list-menu"}]]}}
     icons/dots]
    [:span.sep.hide-s]
    [:button.text-btn.hide-s {:on {:click [[:help/open]]}} (t :help/open) " " [:kbd "?"]]
    [:span.lang.hide-s {:role "group" :aria-label (t :lang/label)}
     (language-button :es locale) " · " (language-button :en locale)]]])

(defn- toast [{messages :toast} t]
  [:div.toast {:role "status" :class (when messages "show")}
   (str/join " · " (map (fn [[k vars]] (t k vars)) messages))])

(defn app [{:keys [locale] :as state}]
  (let [t (translator locale)]
    [:div.app
     (top-bar state t)
     [:main (grid/view state t)]
     (player/view state t)
     (search/view state t)
     (menu/view state t)
     (toast state t)
     (dialogs/view state t)
     (help/view t)]))
