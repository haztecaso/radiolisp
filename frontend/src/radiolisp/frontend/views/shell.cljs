(ns radiolisp.frontend.views.shell
  (:require [clojure.string :as str]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.help :as help]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.player :as player]
            [radiolisp.frontend.views.search :as search]))

(defn- translator
  "`t` bound to one locale, so views call `(t :key)` or `(t :key vars)`."
  [locale]
  (fn
    ([k] (i18n/t locale k))
    ([k vars] (i18n/t locale k vars))))

(defn- language-button [locale current]
  [:button {:lang         (name locale)
            :aria-pressed (str (= locale current))
            :on           {:click [[:i18n/set locale]]}}
   (str/upper-case (name locale))])

(defn app [{:keys [locale search] :as state}]
  (let [t (translator locale)]
    [:div.app {:class (when (:open? search) "searching")}
     [:a.brand {:href "/"} icons/mark [:b (t :brand/name)]]
     [:div.corner
      [:button {:on {:click [[:help/open]]}} (t :help/open) " " [:kbd "?"]]
      [:span {:role "group" :aria-label (t :lang/label)}
       (language-button :es locale) " · " (language-button :en locale)]]
     [:main.stage
      [:div.col
       (player/view state t)
       (search/view state t)]]
     (help/view t)]))
