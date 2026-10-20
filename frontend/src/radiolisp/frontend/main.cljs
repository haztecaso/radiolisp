(ns radiolisp.frontend.main
  "Wiring: one dispatch function, the render loop, the keyboard, the clock."
  (:require [clojure.walk :as walk]
            [radiolisp.frontend.actions :as actions]
            [radiolisp.frontend.audio :as audio]
            [radiolisp.frontend.effects :as effects]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.keymap :as keymap]
            [radiolisp.frontend.store :as store]
            [radiolisp.frontend.views.shell :as shell]
            [replicant.dom :as r]))

(defn dispatch!
  "Runs `action` through the pure core, stores the new state (which renders)
  and then executes the effects."
  [action]
  (let [{:keys [state effects]} (actions/handle @store/state action)]
    (reset! store/state state)
    (doseq [effect effects]
      (effects/execute! dispatch! effect))))

(defn- interpolate
  "Replaces event placeholders inside an action with values from the DOM event."
  [action ^js dom-event]
  (walk/postwalk
   (fn [x]
     (case x
       :event.target/value           (.. dom-event -target -value)
       :event.target/value-as-number (.. dom-event -target -valueAsNumber)
       x))
   action))

(defn- dispatch-dom-event! [event-data handler]
  (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
    (doseq [action handler]
      (dispatch! (interpolate action (:replicant/dom-event event-data))))))

(defn- target-kind [^js el]
  (cond
    (= "search-input" (.-id el))                             :search-input
    (and (= "INPUT" (.-tagName el)) (= "range" (.-type el))) :slider
    (= "BUTTON" (.-tagName el))                              :button
    :else                                                    :other))

(defn- on-keydown [^js e]
  (when-let [action (keymap/key->action @store/state
                                        {:key    (.-key e)
                                         :ctrl?  (.-ctrlKey e)
                                         :alt?   (.-altKey e)
                                         :meta?  (.-metaKey e)
                                         :target (target-kind (.-target e))
                                         :now    (js/Date.now)})]
    (.preventDefault e)
    (dispatch! action)))

(defonce ^:private root (atom nil))

(defn- render! [state]
  (r/render @root (shell/app state)))

(defn ^:dev/after-load reload []
  (render! @store/state))

(defn init []
  (reset! root (js/document.getElementById "app"))
  (r/set-dispatch! dispatch-dom-event!)
  (add-watch store/state ::render (fn [_ _ _ state] (render! state)))
  (audio/init! dispatch!)
  (js/document.addEventListener "keydown" on-keydown)
  (js/setInterval #(when (= :playing (get-in @store/state [:player :status]))
                     (dispatch! [:clock/tick (js/Date.now)]))
                  1000)
  (render! @store/state)
  (dispatch! [:app/init {:locale (i18n/detect-locale
                                  (js->clj (or js/navigator.languages
                                               #js [js/navigator.language])))}]))
