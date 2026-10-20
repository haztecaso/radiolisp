(ns radiolisp.frontend.audio
  "The one <audio> element. Lives outside the Replicant tree; its events come
  back into the app as actions.

  Many stations publish http:// streams. Those break on https pages (mixed
  content) and on networks whose port-80 proxies buffer endless responses, so
  `play!` tries the https:// form of the URL first and falls back to the
  original when that errors or does not start within `https-attempt-ms`."
  (:require [clojure.string :as str]))

(def https-attempt-ms 4000)

(defonce ^:private state (atom {:element nil :fallbacks [] :timer nil}))

(defn- ^js element [] (:element @state))

(defn- candidates [url]
  (if (str/starts-with? url "http://")
    [(str "https://" (subs url (count "http://"))) url]
    [url]))

(defn- clear-attempt! []
  (js/clearTimeout (:timer @state))
  (swap! state assoc :timer nil))

(defn- load! [url]
  (let [audio (element)]
    (set! (.-src audio) url)
    ;; Failures arrive through the "error" event; the rejection adds nothing.
    (-> (.play audio) (.catch (fn [_] nil)))))

(declare next-candidate!)

(defn- start-attempt! [url]
  (clear-attempt!)
  (load! url)
  (when (seq (:fallbacks @state))
    (swap! state assoc :timer
           (js/setTimeout #(when (< (.-readyState (element)) 3) (next-candidate!))
                          https-attempt-ms))))

(defn- next-candidate!
  "Moves to the next URL to try. False when there is none left."
  []
  (if-let [url (first (:fallbacks @state))]
    (do (swap! state update :fallbacks rest)
        (start-attempt! url)
        true)
    (do (clear-attempt!) false)))

(defn init!
  "Creates the element and wires its events to `dispatch`."
  [dispatch]
  (let [audio (js/Audio.)]
    (set! (.-preload audio) "none")
    (.addEventListener audio "playing" (fn []
                                         (clear-attempt!)
                                         (swap! state assoc :fallbacks [])
                                         (dispatch [:audio/playing (js/Date.now)])))
    (.addEventListener audio "pause" #(dispatch [:audio/paused]))
    (.addEventListener audio "waiting" #(dispatch [:audio/waiting]))
    (.addEventListener audio "error" #(when-not (next-candidate!) (dispatch [:audio/failed])))
    (swap! state assoc :element audio)))

(defn play! [url]
  (let [audio (element)
        [first-url & fallbacks] (candidates url)]
    (if (some #{(.-src audio)} (candidates url))
      ;; Resuming the station already loaded.
      (-> (.play audio) (.catch (fn [_] nil)))
      (do (swap! state assoc :fallbacks (vec fallbacks))
          (start-attempt! first-url)))))

(defn pause! []
  (.pause (element)))

(defn stop! []
  (let [audio (element)]
    (clear-attempt!)
    (swap! state assoc :fallbacks [])
    (.pause audio)
    (.removeAttribute audio "src")
    (.load audio)))

(defn set-volume! [volume]
  (set! (.-volume (element)) volume))
