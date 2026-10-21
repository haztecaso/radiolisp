(ns radiolisp.frontend.views.dialogs
  "The one native <dialog>: removing a favourite, choosing its key,
  customizing it, exporting the list and importing a dropped file. It always
  exists; `effects` opens and closes it and its content follows `:dialog` in
  the state."
  (:require [radiolisp.common.favorites :as favs]
            [radiolisp.frontend.views.art :as art]))

(defn- favorite [{:keys [stations favorites]} id]
  (let [stored (favs/find-favorite (:items favorites) id)]
    (assoc stored :name (favs/display-name stored (get stations id)))))

(defn- remove-body [state t id]
  (let [{station-name :name shortcut :key} (favorite state id)]
    (list
     [:h2#dialog-title (t :remove/title)]
     [:p (t :remove/body {:name station-name})
      (when shortcut (str " " (t :remove/key {:key shortcut})))]
     [:div.actions
      [:button.btn {:autofocus true :on {:click [[:dialog/close]]}} (t :dialog/cancel)]
      [:button.btn.danger {:on {:click [[:favorites/remove id]]}} (t :remove/confirm)]])))

(defn- key-body [state t {:keys [id pending message]}]
  (let [{station-name :name shortcut :key} (favorite state id)
        shown (or pending shortcut)
        [message-key vars] message]
    (list
     [:h2#dialog-title (t :key/title {:name station-name})]
     [:p (t :key/hint)]
     [:div.keyprev {:class (when shown "set")} (or shown "…")]
     [:div.keymsg {:class     (when (#{:key/invalid :key/reserved} message-key) "err")
                   :aria-live "polite"}
      (when message-key (t message-key vars))]
     [:div.actions
      (when shortcut
        [:button.btn.left {:on {:click [[:key-dialog/clear]]}} (t :key/clear)])
      [:button.btn {:autofocus true :on {:click [[:dialog/close]]}} (t :dialog/cancel)]
      [:button.btn.primary {:disabled (nil? pending) :on {:click [[:key-dialog/save]]}}
       (t :key/save)]])))

(def ^:private display-labels
  [[:image :customize/image] [:pad :customize/pad] [:text :customize/text]])

(defn- customize-body
  "Name and look of a favourite, with a preview of its tile as wide as the
  tiles of the grid (`--tile-w`, set by :dom/preview-size)."
  [{:keys [stations cache] :as state} t {:keys [id display] text :name}]
  (let [shown    (or (get stations id) (get cache id))
        original (:name shown)
        custom   (favs/clean-name text)
        preview  {:id id :name (or custom original) :favicon (:favicon shown) :display display}
        current  (art/mode state preview)]
    [:form {:on {:submit [[:customize/save]]}}
     [:h2#dialog-title (t :customize/title)]
     [:div.customize
      [:div.tile.preview {:aria-hidden "true"}
       (art/view state preview "art")
       (when (not= :text current)
         [:span.name (:name preview)])]
      [:div.fields
       [:div.field
        [:label.field-label {:for "customize-name"} (t :customize/name)]
        [:input#customize-name {:type         "text"
                                :autocomplete "off"
                                :spellcheck   false
                                :enterkeyhint "done"
                                :value        text
                                :placeholder  original
                                :on           {:input [[:customize/name :event.target/value]]}}]
        (when (and custom original (not= custom original))
          [:p.hint (t :customize/original {:name original}) " · "
           [:button.text-btn {:type "button" :on {:click [[:customize/use-original]]}}
            (t :customize/use-original)]])]
       (when (art/shows-image? state preview)
         [:div.field
          [:span#customize-show.field-label (t :customize/show)]
          [:div.seg {:role "radiogroup" :aria-labelledby "customize-show"}
           (for [[mode label] display-labels]
             [:button {:replicant/key (name mode)
                       :type          "button"
                       :role          "radio"
                       :aria-checked  (str (= mode current))
                       :on            {:click [[:customize/display mode]]}}
              (t label)])]])]]
     [:div.actions
      [:button.btn {:type "button" :on {:click [[:dialog/close]]}} (t :dialog/cancel)]
      [:button.btn.primary {:type "submit"} (t :customize/save)]]]))

(def ^:private format-text
  {:m3u  [:export/m3u-label :export/m3u-hint]
   :list [:export/list-label :export/list-hint]})

(defn- format-option [t chosen format]
  [:label.option {:class (when (= chosen format) "on")}
   [:input {:type    "radio"
            :name    "export-format"
            :value   (name format)
            :checked (= chosen format)
            :on      {:change [[:export-dialog/format format]]}}]
   (let [[label hint] (format-text format)]
     [:span [:b (t label)] [:small (t hint)]])])

(defn- export-body [t {:keys [format stem]}]
  [:form {:on {:submit [[:export-dialog/save]]}}
   [:h2#dialog-title (t :export/title)]
   [:fieldset.options
    [:legend.vh (t :export/format)]
    (format-option t format :m3u)
    (format-option t format :list)]
   [:label.field-label {:for "export-stem"} (t :export/filename)]
   [:div.filename
    [:input#export-stem {:type         "text"
                         :autofocus    true
                         :autocomplete "off"
                         :spellcheck   false
                         :value        stem
                         :placeholder  favs/default-file-stem
                         :on           {:input [[:export-dialog/stem :event.target/value]]}}]
    [:span.ext (str "." (get-in favs/formats [format :extension]))]]
   [:div.actions
    [:button.btn {:type "button" :on {:click [[:dialog/close]]}} (t :dialog/cancel)]
    [:button.btn.primary {:type "submit"} (t :export/save)]]])

(defn- import-body [t {:keys [filename] n :count}]
  [:form {:on {:submit [[:import-dialog/confirm]]}}
   [:h2#dialog-title (t :import/title)]
   [:p (t :import/body {:file filename :count n})]
   [:div.actions
    [:button.btn {:type "button" :on {:click [[:dialog/close]]}} (t :dialog/cancel)]
    [:button.btn.primary {:type "submit" :autofocus true} (t :import/confirm)]]])

(defn view [{:keys [dialog] :as state} t]
  [:dialog#dialog {:aria-labelledby "dialog-title"
                   :class           (some-> (:kind dialog) name)
                   :on              {:close [[:dialog/close]]}}
   (case (:kind dialog)
     :remove    (remove-body state t (:id dialog))
     :key       (key-body state t dialog)
     :customize (customize-body state t dialog)
     :export    (export-body t dialog)
     :import    (import-body t dialog)
     nil)])
