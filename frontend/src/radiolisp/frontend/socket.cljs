(ns radiolisp.frontend.socket
  "The WebSocket to the backend, for the titles of the station that plays.
  It opens the first time there is something to follow, not on load, and
  sends the current subscription whenever it (re)opens. While a station is
  followed, a closed socket retries after 2, 4, 8… up to 60 s; with nothing
  followed it stays closed. Without a backend that is a few failed
  connections while a station plays, and nothing else.

  Messages from the server are dispatched as actions, but only the ones
  `protocol/server-message?` accepts."
  (:require [radiolisp.common.protocol :as protocol]))

(def path "/api/ws")
(def first-retry-ms 2000)
(def max-retry-ms 60000)

(defonce ^:private state
  (atom {:socket   nil
         :dispatch nil
         :id       nil            ; the station followed, or nil
         :retry-ms first-retry-ms
         :timer    nil}))

(defn- url []
  (let [location js/window.location]
    (str (if (= "https:" (.-protocol location)) "wss:" "ws:") "//" (.-host location) path)))

(defn- open? [^js socket]
  (and socket (= 1 (.-readyState socket))))

(defn- send! [message]
  (let [^js socket (:socket @state)]
    (when (open? socket)
      (.send socket (protocol/encode message)))))

(declare connect!)

(defn- schedule-retry! []
  (let [{:keys [retry-ms]} @state]
    (swap! state assoc
           :retry-ms (min max-retry-ms (* 2 retry-ms))
           :timer (js/setTimeout #(do (swap! state assoc :timer nil)
                                      (when (:id @state) (connect!)))
                                 retry-ms))))

(defn- connect! []
  (let [^js socket (js/WebSocket. (url))]
    (swap! state assoc :socket socket)
    (.addEventListener socket "open"
                       (fn []
                         (swap! state assoc :retry-ms first-retry-ms)
                         (when-let [id (:id @state)]
                           (send! (protocol/subscribe id)))))
    (.addEventListener socket "message"
                       (fn [^js e]
                         (when-let [message (protocol/decode (.-data e) protocol/server-message?)]
                           ((:dispatch @state) message))))
    (.addEventListener socket "close"
                       (fn []
                         (when (identical? socket (:socket @state))
                           (swap! state assoc :socket nil)
                           (when (and (:id @state) (nil? (:timer @state)))
                             (schedule-retry!)))))))

(defn subscribe!
  "Follows the titles of station `id`; they arrive through `dispatch`."
  [dispatch id]
  (swap! state assoc :dispatch dispatch :id id)
  (cond
    (open? (:socket @state)) (send! (protocol/subscribe id))
    (nil? (:socket @state))  (when-not (:timer @state) (connect!))
    ;; Still connecting: the open handler sends it.
    :else                    nil))

(defn unsubscribe!
  "Stops following. The socket stays open for the next station."
  []
  (when (:id @state)
    (swap! state assoc :id nil)
    (send! (protocol/unsubscribe))))
