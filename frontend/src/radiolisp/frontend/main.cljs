(ns radiolisp.frontend.main
  "Wiring: one dispatch function, the render loop, the keyboard, the media keys."
  (:require [clojure.walk :as walk]
            [radiolisp.common.favorites :as favs]
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
       :event.target/text-content    (.. dom-event -target -textContent)
       :event.target/natural-width   (.. dom-event -target -naturalWidth)
       :event.target/natural-height  (.. dom-event -target -naturalHeight)
       x))
   action))

(defn- dispatch-dom-event!
  "The innermost handler wins: a button inside a clickable row or tile does
  not also trigger it. Forms never navigate (submit). Drag and drop needs the
  browser defaults turned off (dragover, drop) and some data set (Firefox will not drag without it)."
  [event-data handler]
  (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
    (let [^js e (:replicant/dom-event event-data)]
      (.stopPropagation e)
      (case (.-type e)
        ("dragover" "drop" "submit") (.preventDefault e)
        "dragstart"         (let [^js data (.-dataTransfer e)]
                              (.setData data "text/plain" "")
                              (set! (.-effectAllowed data) "move"))
        nil)
      (doseq [action handler]
        (dispatch! (interpolate action e))))))

(defn- target-kind [^js el]
  (cond
    (= "search-input" (.-id el))                             :search-input
    (= "filter-input" (.-id el))                             :filter-input
    (and (= "INPUT" (.-tagName el)) (= "range" (.-type el))) :slider
    (= "BUTTON" (.-tagName el))                              :button
    :else                                                    :other))

(defn- on-keydown [^js e]
  (when-let [action (keymap/key->action @store/state
                                        {:key    (.-key e)
                                         :shift? (.-shiftKey e)
                                         :ctrl?  (.-ctrlKey e)
                                         :alt?   (.-altKey e)
                                         :meta?  (.-metaKey e)
                                         :target (target-kind (.-target e))
                                         :now    (js/Date.now)})]
    (.preventDefault e)
    (dispatch! action)))

(defonce ^:private root (atom nil))

(defn- render! [state]
  (r/render @root (shell/app state))
  (let [title (shell/page-title state)]
    (when (not= title js/document.title)
      (set! js/document.title title))))

(defn- media-keys!
  "System media keys: play and pause, and next or previous favourite."
  []
  (when-let [^js session (.-mediaSession js/navigator)]
    (doseq [[media-action action] [["play" [:player/toggle]]
                                   ["pause" [:player/toggle]]
                                   ["nexttrack" [:favorites/play-next 1]]
                                   ["previoustrack" [:favorites/play-next -1]]]]
      (try (.setActionHandler session media-action #(dispatch! action))
           (catch :default _ nil)))))

(defn- system-theme!
  "Follows the browser's light or dark preference while it changes."
  []
  (.addEventListener (js/matchMedia effects/dark-query) "change"
                     (fn [^js e] (dispatch! [:theme/system-changed (if (.-matches e) :dark :light)]))))

(defn- dragging-files? [^js e]
  (.includes (js/Array.from (.. e -dataTransfer -types)) "Files"))

(defn- file-drops!
  "A file dragged over the page shows where it can go; dropped anywhere, it
  is offered for import. The listeners capture, so tiles never take a file
  for a favourite being moved. Only a file with a known extension is read."
  []
  (.addEventListener js/window "dragover"
                     (fn [^js e]
                       (when (dragging-files? e)
                         (.preventDefault e)
                         (.stopPropagation e)
                         (set! (.. e -dataTransfer -dropEffect) "copy")
                         (when-not (:file-drag? @store/state)
                           (dispatch! [:file-drag/over]))
                         (effects/execute! dispatch! [:timer/start :file-drag actions/file-drag-ms [:file-drag/end]])))
                     true)
  (.addEventListener js/window "drop"
                     (fn [^js e]
                       (when (dragging-files? e)
                         (.preventDefault e)
                         (.stopPropagation e)
                         (dispatch! [:file-drag/end])
                         (when-let [^js file (aget (.. e -dataTransfer -files) 0)]
                           (if (favs/file-format (.-name file))
                             (effects/read-file! dispatch! file :import/offer)
                             (dispatch! [:import/offer nil (.-name file)])))))
                     true))

(defn ^:dev/after-load reload []
  (render! @store/state))

(defn init []
  (reset! root (js/document.getElementById "app"))
  (r/set-dispatch! dispatch-dom-event!)
  (add-watch store/state ::render (fn [_ _ _ state] (render! state)))
  (audio/init! dispatch!)
  (js/document.addEventListener "keydown" on-keydown)
  (media-keys!)
  (system-theme!)
  (file-drops!)
  (render! @store/state)
  (dispatch! [:app/init {:locale (i18n/detect-locale
                                  (js->clj (or js/navigator.languages
                                               #js [js/navigator.language])))
                         :theme  (effects/initial-theme)}]))
