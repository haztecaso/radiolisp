(ns radiolisp.frontend.views.dialogs
  "The one native <dialog> for confirmations: removing a favourite and
  choosing its key. It always exists; `effects` opens and closes it and its
  content follows `:dialog` in the state.")

(defn- favorite [{:keys [stations favorites]} id]
  (let [stored (first (filter #(= id (:id %)) (:items favorites)))]
    (assoc stored :name (or (get-in stations [id :name]) (:name stored)))))

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

(defn view [{:keys [dialog] :as state} t]
  [:dialog#dialog {:aria-labelledby "dialog-title"
                   :on              {:close [[:dialog/close]]}}
   (case (:kind dialog)
     :remove (remove-body state t (:id dialog))
     :key    (key-body state t dialog)
     nil)])
