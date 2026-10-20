(ns radiolisp.backend.socket
  "`/api/ws`: titles of the station a browser is playing. Each socket
  follows at most one station; a new subscription replaces the old one."
  (:require [radiolisp.backend.limits :as limits]
            [radiolisp.backend.relay :as relay]
            [radiolisp.common.protocol :as protocol]
            [ring.websocket :as ws]))

(defn- sender
  "Sends a message without blocking the caller (the relay's reader)."
  [socket]
  (fn [message]
    (when (ws/open? socket)
      (ws/send socket (protocol/encode message) (fn []) (fn [_])))))

(defn- subscribe! [{:keys [relay]} state socket id]
  (let [previous (:id (first (swap-vals! state assoc :id id)))]
    (when (and previous (not= previous id))
      (relay/unsubscribe! relay previous state))
    ;; Resolving the station may take seconds; the socket keeps reading meanwhile.
    (Thread/startVirtualThread
     (fn []
       (relay/subscribe! relay id state (sender socket))
       (when-not (= id (:id @state))
         (relay/unsubscribe! relay id state))))))

(defn- unsubscribe! [{:keys [relay]} state]
  (when-let [id (:id (first (swap-vals! state assoc :id nil)))]
    (relay/unsubscribe! relay id state)))

(defn handle
  "The WebSocket response for a browser at `ip`, or 429 when it has too many open."
  [{:keys [limits config sockets] :as system} ip]
  (if (limits/acquire! limits :sockets ip config)
    {:status 429 :headers {"Content-Type" "text/plain"} :body "Too many connections"}
    ;; The state atom is also this socket's key in the relay.
    (let [state (atom {:id nil})]
      {::ws/listener
       {:on-open    (fn [socket]
                      (swap! sockets conj socket))
        :on-message (fn [socket text]
                      (let [message (protocol/decode (str text) protocol/client-message?)]
                        (case (first message)
                          :now-playing/subscribe   (subscribe! system state socket (:id (second message)))
                          :now-playing/unsubscribe (unsubscribe! system state)
                          ((sender socket) (protocol/rejected :malformed)))))
        :on-close   (fn [socket _ _]
                      (swap! sockets disj socket)
                      (unsubscribe! system state)
                      (limits/release! limits :sockets ip))
        :on-error   (fn [_ _] nil)}})))

(defn ping-all!
  "Pings every open socket, so proxies and Jetty's idle timeout keep them."
  [sockets]
  (doseq [socket @sockets]
    (try (ws/ping socket) (catch Exception _ nil))))
