(ns radiolisp.frontend.audio
  "The one <audio> element. Lives outside the Replicant tree; its events come
  back into the app as actions.

  Many stations publish http:// streams. Those break on https pages (mixed
  content) and on networks whose port-80 proxies buffer endless responses, so
  `play!` tries the https:// form of the URL first and falls back to the
  original when that errors or does not start within `https-attempt-ms`.
  On an https page the original is pointless (the browser blocks or upgrades
  mixed content), so only the https form is tried, and when it fails the
  station is reported as http-only.

  When every URL has failed, the element does not say why, so a `fetch` of the
  last one reads the HTTP status for the message (a 451 means the station
  blocks the listener's region or VPN). Streams without CORS keep it hidden.

  HLS streams play natively where the browser can (Safari); elsewhere through
  hls.js, loaded on demand from the :hls module."
  (:require [clojure.string :as str]
            [shadow.lazy :as lazy]))

(def https-attempt-ms 8000)
(def status-probe-ms 4000)

;; Not required on purpose: requiring it would put hls.js in the main module.
(def ^:private hls-module
  #_{:clj-kondo/ignore [:unresolved-namespace]}
  (lazy/loadable radiolisp.frontend.hls/start!))

(defonce ^:private state
  (atom {:element   nil
         :dispatch  nil
         :url       nil      ; the station URL being played, before any https rewrite
         :current   nil      ; the candidate URL being tried
         :hls?      false
         :http-only? false   ; on an https page, an http:// station with no fallback
         :hls       nil      ; the hls.js instance, when one is attached
         :attempt   0        ; bumped on every load; stale async work checks it
         :fallbacks []
         :timer     nil}))

(defn- ^js element [] (:element @state))

(defn- https-page? []
  (= "https:" (.. js/window -location -protocol)))

(defn- http-url? [url]
  (str/starts-with? url "http://"))

(defn- candidates [url]
  (cond
    (not (http-url? url)) [url]
    (https-page?)         [(str "https://" (subs url (count "http://")))]
    :else                 [(str "https://" (subs url (count "http://"))) url]))

(defn- clear-attempt! []
  (js/clearTimeout (:timer @state))
  (swap! state assoc :timer nil))

(defn- destroy-hls! []
  (when-let [^js hls (:hls @state)]
    (.destroy hls))
  (swap! state assoc :hls nil))

(defn- native-hls? []
  (not= "" (.canPlayType (element) "application/vnd.apple.mpegurl")))

(declare fail-attempt!)

(defn- load-hls! [url attempt]
  (-> (lazy/load hls-module)
      (.then (fn [start!]
               (when (= attempt (:attempt @state))
                 (if-let [hls (start! (element) url fail-attempt!)]
                   (swap! state assoc :hls hls)
                   ((:dispatch @state) [:audio/unsupported])))))
      (.catch (fn [error]
                (js/console.warn "radiolisp: HLS playback failed to start" error)
                (when (= attempt (:attempt @state))
                  (fail-attempt!))))))

(defn- load! [url]
  (let [audio   (element)
        attempt (:attempt (swap! state update :attempt inc))]
    (destroy-hls!)
    (if (and (:hls? @state) (not (native-hls?)))
      (load-hls! url attempt)
      (do (set! (.-src audio) url)
          ;; Failures arrive through the "error" event; the rejection adds nothing.
          (-> (.play audio) (.catch (fn [_] nil)))))))

(defn- start-attempt! [url]
  (clear-attempt!)
  (swap! state assoc :current url)
  (load! url)
  ;; An https form that does not exist often hangs instead of erroring.
  (when (or (seq (:fallbacks @state)) (:http-only? @state))
    (swap! state assoc :timer
           (js/setTimeout #(when (< (.-readyState (element)) 3) (fail-attempt!))
                          https-attempt-ms))))

(defn- next-candidate!
  "Moves to the next URL to try. False when there is none left."
  []
  (if-let [url (first (:fallbacks @state))]
    (do (swap! state update :fallbacks rest)
        (start-attempt! url)
        true)
    (do (clear-attempt!) false)))

(defn- report-failure!
  "Dispatches :audio/failed with the HTTP status `url` answers with, or nil
  when it cannot be read (no CORS, mixed content, network error)."
  [url]
  (let [attempt (:attempt @state)]
    (-> (js/fetch url #js {:cache "no-store" :signal (js/AbortSignal.timeout status-probe-ms)})
        (.then (fn [^js res]
                 ;; Only the status matters; a live stream body never ends.
                 (some-> (.-body res) (.cancel) (.catch (fn [_] nil)))
                 (.-status res)))
        (.catch (fn [_] nil))
        (.then (fn [status]
                 (when (= attempt (:attempt @state))
                   ((:dispatch @state) [:audio/failed status])))))))

(defn- fail-attempt! []
  (cond
    (next-candidate!)    nil
    (:http-only? @state) ((:dispatch @state) [:audio/http-only])
    :else                (report-failure! (:current @state))))

(defn- playback-state! [s]
  (when-let [^js session (.-mediaSession js/navigator)]
    (set! (.-playbackState session) s)))

(defn init!
  "Creates the element and wires its events to `dispatch`."
  [dispatch]
  (let [audio (js/Audio.)]
    (set! (.-preload audio) "none")
    (.addEventListener audio "playing" (fn []
                                         (clear-attempt!)
                                         (swap! state assoc :fallbacks [])
                                         (playback-state! "playing")
                                         (dispatch [:audio/playing])))
    (.addEventListener audio "pause" (fn []
                                       (playback-state! "paused")
                                       (dispatch [:audio/paused])))
    (.addEventListener audio "waiting" #(dispatch [:audio/waiting]))
    ;; With hls.js attached, its own fatal errors decide; element errors are noise.
    (.addEventListener audio "error" #(when-not (:hls @state) (fail-attempt!)))
    (swap! state assoc :element audio :dispatch dispatch)))

(defn play!
  "Plays `url`; `hls?` says it is an HLS playlist."
  [url hls?]
  (let [audio (element)]
    (if (and (= url (:url @state))
             (nil? (.-error audio))
             (or (:hls @state) (not= "" (.-src audio))))
      ;; Resuming the station already loaded and healthy.
      (-> (.play audio) (.catch (fn [_] nil)))
      (let [[first-url & fallbacks] (candidates url)]
        (swap! state assoc :url url :hls? (boolean hls?) :fallbacks (vec fallbacks)
               :http-only? (and (http-url? url) (https-page?)))
        (start-attempt! first-url)))))

(defn pause! []
  (.pause (element)))

(defn stop! []
  (let [audio (element)]
    (clear-attempt!)
    (destroy-hls!)
    (swap! state assoc :fallbacks [] :url nil)
    (swap! state update :attempt inc)
    (.pause audio)
    (.removeAttribute audio "src")
    (.load audio)
    (playback-state! "none")))

(defn set-volume! [volume]
  (set! (.-volume (element)) volume))
