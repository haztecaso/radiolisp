(ns radiolisp.frontend.audio
  "The one <audio> element. Lives outside the Replicant tree; its events come
  back into the app as actions.")

(defonce ^:private element (atom nil))

(defn init!
  "Creates the element and wires its events to `dispatch`."
  [dispatch]
  (let [audio (js/Audio.)]
    (set! (.-preload audio) "none")
    (.addEventListener audio "playing" #(dispatch [:audio/playing (js/Date.now)]))
    (.addEventListener audio "pause" #(dispatch [:audio/paused]))
    (.addEventListener audio "waiting" #(dispatch [:audio/waiting]))
    (.addEventListener audio "error" #(dispatch [:audio/failed]))
    (reset! element audio)))

(defn play! [url]
  (let [^js audio @element]
    (when (not= (.-src audio) url)
      (set! (.-src audio) url))
    ;; Failures arrive through the "error" event; the rejection adds nothing.
    (-> (.play audio) (.catch (fn [_] nil)))))

(defn pause! []
  (.pause ^js @element))

(defn stop! []
  (let [^js audio @element]
    (.pause audio)
    (.removeAttribute audio "src")
    (.load audio)))

(defn set-volume! [volume]
  (set! (.-volume ^js @element) volume))
