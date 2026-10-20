(ns radiolisp.backend.relay
  "One session per station uuid: a single connection to the station shared
  by every proxy listener (audio) and every WebSocket subscriber (titles).

  A session lives in an atom. Its reader, on a virtual thread, appends the
  audio to the burst and reads the listener set in one `swap-vals!`; a new
  listener joins and takes the burst in another. Either the chunk is in the
  burst the listener gets, or the listener is in the set the chunk goes to:
  nothing is lost or sent twice.

  Each listener is a core.async channel with room for
  `listener-queue-chunks`. A listener whose channel is full is closed, so a
  slow one never holds up the others. A session with nobody left closes
  after `idle-session-ms`."
  (:require [clojure.core.async :as async]
            [radiolisp.backend.config :as config]
            [radiolisp.backend.icy :as icy]
            [radiolisp.backend.upstream :as upstream]
            [radiolisp.common.protocol :as protocol])
  (:import (java.util Arrays)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

(def ^:private reset-retries-after-bytes
  "A connection that delivered this much counts as healthy: the next drop
  starts the retries afresh. A station that closes at once runs out of them."
  config/burst-bytes)

(defn create
  "A relay. `resolve-station` maps a uuid to `{:station s}` or `{:error reason}`;
  `open!` is `upstream/open!`; `config` gives `:max-sessions` and the
  options for `open!`."
  [{:keys [resolve-station open! config]
    :or   {open! upstream/open!}}]
  {:sessions  (atom {})
   :resolve-station resolve-station
   :open!     open!
   :config    config
   :scheduler (Executors/newSingleThreadScheduledExecutor)})

(defn stop!
  "Closes every session and the scheduler."
  [relay]
  (doseq [[_ session] @(:sessions relay)]
    (let [{:keys [conn listeners]} (swap! session assoc :status :closed)]
      (upstream/close! conn)
      (run! async/close! listeners)))
  (reset! (:sessions relay) {})
  (.shutdownNow ^ScheduledExecutorService (:scheduler relay)))

(defn session-count [relay]
  (count @(:sessions relay)))

(defn- members [{:keys [listeners subscribers pending]}]
  (+ (count listeners) (count subscribers) pending))

;; ---------------------------------------------------------------- burst

(defn- append-burst
  "Keeps the last `burst-bytes` of audio as a queue of chunks."
  [{:keys [chunks size]} ^bytes piece]
  (loop [chunks (conj chunks piece)
         size   (+ size (alength piece))]
    (let [^bytes oldest (peek chunks)]
      (if (and (> size config/burst-bytes) (> (count chunks) 1))
        (recur (pop chunks) (- size (alength oldest)))
        {:chunks chunks :size size}))))

(def ^:private empty-burst {:chunks clojure.lang.PersistentQueue/EMPTY :size 0})

;; ---------------------------------------------------------------- closing

(defn- close-session!
  "Closes `session` once: drops it from the relay, ends every listener's
  response and closes the connection, which also ends the reader."
  [relay id session]
  (let [[old _] (swap-vals! session assoc :status :closed)]
    (when-not (= :closed (:status old))
      (swap! (:sessions relay) (fn [sessions]
                                 (if (identical? session (get sessions id))
                                   (dissoc sessions id)
                                   sessions)))
      (run! async/close! (:listeners old))
      (upstream/close! (:conn old)))))

(defn- close-if-idle!
  "Schedules a check: if nobody is in `session` after `idle-session-ms`, it closes."
  [relay id session]
  (when (zero? (members @session))
    (.schedule ^ScheduledExecutorService (:scheduler relay)
               ^Runnable (fn []
                           (when (zero? (members @session))
                             (close-session! relay id session)))
               (long config/idle-session-ms)
               TimeUnit/MILLISECONDS)))

;; ---------------------------------------------------------------- publishing

(defn- send-all! [subscribers message]
  (doseq [[_ send!] subscribers]
    (send! message)))

(defn- publish-audio! [session ^bytes audio]
  (let [[_ s] (swap-vals! session update :burst append-burst audio)
        slow  (remove #(async/offer! % audio) (:listeners s))]
    (when (seq slow)
      (swap! session update :listeners #(reduce disj % slow))
      (run! async/close! slow))))

(defn- publish-title! [id session raw]
  (when-some [title (icy/stream-title raw)]
    (let [title     (not-empty title)
          [before after] (swap-vals! session assoc :title title :titled? true)]
      (when (or (not= title (:title before)) (not (:titled? before)))
        (send-all! (:subscribers after) (protocol/title id title))))))

;; ---------------------------------------------------------------- reading

(defn- fail-session!
  "The station cannot be reached: waiting joiners get `reason`, subscribers
  are told, listeners' responses end."
  [relay id session reason]
  (deliver (:ready @session) {:error reason})
  (send-all! (:subscribers @session) (protocol/unavailable id :unreachable))
  (close-session! relay id session))

(defn- connected! [relay id session {:keys [metaint content-type] :as conn}]
  (let [[before after] (swap-vals! session assoc :conn conn :status :live :metaint metaint)]
    (deliver (:ready after) {:content-type content-type :metaint metaint})
    (when (= :closed (:status before))
      (upstream/close! conn))
    (when-not metaint
      (send-all! (:subscribers after) (protocol/unavailable id :no-metadata))
      ;; Reading audio nobody hears only to find no titles in it is waste.
      (when (and (empty? (:listeners after)) (zero? (:pending after)))
        (close-session! relay id session)))))

(defn- read-stream!
  "Reads `conn` until it ends. Returns the number of bytes read."
  [id session {:keys [metaint ^java.io.InputStream body]}]
  (let [buffer (byte-array config/chunk-bytes)]
    (loop [icy-state (some-> metaint icy/initial-state)
           total     0]
      (let [n (try (.read body buffer) (catch java.io.IOException _ -1))]
        (if (or (neg? n) (= :closed (:status @session)))
          total
          (let [piece (Arrays/copyOf buffer n)]
            (if icy-state
              (let [{:keys [state audio metadata]} (icy/feed icy-state piece)]
                (doseq [a audio] (publish-audio! session a))
                (doseq [m metadata] (publish-title! id session m))
                (recur state (+ total n)))
              (do (publish-audio! session piece)
                  (recur nil (+ total n))))))))))

(defn- run-session!
  "The reader: connects, reads, and reconnects after `retry-delays-ms` when
  the station drops. Runs until the session closes or the retries run out."
  [relay id session url]
  (loop [retries config/retry-delays-ms]
    (when-not (= :closed (:status @session))
      (let [result (try {:conn ((:open! relay) url (:config relay))}
                        (catch clojure.lang.ExceptionInfo e {:error (:reason (ex-data e) :unreachable)})
                        (catch Exception _ {:error :unreachable}))]
        (cond
          (= :closed (:status @session))
          (some-> (:conn result) upstream/close!)

          (:error result)
          (if-let [[delay-ms & more] (and (realized? (:ready @session)) (seq retries))]
            (do (swap! session assoc :status :retrying)
                (Thread/sleep (long delay-ms))
                (recur more))
            (fail-session! relay id session (:error result)))

          :else
          (let [conn (:conn result)]
            (connected! relay id session conn)
            (let [total (read-stream! id session conn)]
              (upstream/close! conn)
              (recur (if (>= total reset-retries-after-bytes) config/retry-delays-ms retries)))))))))

;; ---------------------------------------------------------------- joining

(defn- new-session []
  (atom {:status      :connecting
         :ready       (promise)
         :conn        nil
         :metaint     nil
         :listeners   #{}
         :subscribers {}
         :pending     0
         :title       nil
         :titled?     false
         :burst       empty-burst}))

(defn- enter!
  "The session for `id` with `f` applied to it, creating and starting the
  session when there is none. Returns the session, or :busy when a new one
  would pass `:max-sessions`."
  [relay id url f]
  (let [created (volatile! nil)
        result  (swap! (:sessions relay)
                       (fn [sessions]
                         (vreset! created nil)
                         (cond
                           (contains? sessions id) sessions
                           (>= (count sessions) (get-in relay [:config :max-sessions])) sessions
                           :else (let [s (new-session)]
                                   (vreset! created s)
                                   (assoc sessions id s)))))]
    (if-let [session (get result id)]
      (let [entered (swap! session f)]
        (when-let [s @created]
          (Thread/startVirtualThread #(run-session! relay id s url)))
        (if (= :closed (:status entered))
          ;; It closed (idle) between the lookup and the entry; closed
          ;; sessions leave the map, so the next try starts a new one.
          (recur relay id url f)
          session))
      :busy)))

(defn- station-url
  "`{:url u}` for a station the relay can read, else `{:error reason}`."
  [relay id]
  (let [{:keys [station error]} ((:resolve-station relay) id)]
    (cond
      error                 {:error error}
      (:hls? station)       {:error :hls}
      (nil? (:url station)) {:error :unknown-station}
      :else                 {:url (:url station)})))

(defn join-listener!
  "Joins a proxy listener to station `id`, waiting for the station to answer.
  Returns `{:chan c :burst [bytes …] :content-type t}`; take audio from `c`
  until it closes, and call `leave-listener!` at the end. Or returns
  `{:error reason}`, reason being :unknown-station, :hls, :busy,
  :unreachable or :forbidden."
  [relay id]
  (let [{:keys [url error]} (station-url relay id)]
    (if error
      {:error error}
      (let [session (enter! relay id url #(update % :pending inc))]
        (if (= :busy session)
          {:error :busy}
          (let [ready (deref (:ready @session) (+ config/upstream-connect-ms 2000) {:error :unreachable})]
            (if (:error ready)
              (do (swap! session update :pending dec)
                  (close-if-idle! relay id session)
                  ready)
              (let [ch    (async/chan config/listener-queue-chunks)
                    [_ s] (swap-vals! session (fn [s]
                                                (-> s
                                                    (update :pending dec)
                                                    (update :listeners conj ch))))]
                (when (= :closed (:status s)) (async/close! ch))
                {:chan         ch
                 :burst        (vec (get-in s [:burst :chunks]))
                 :content-type (:content-type ready)}))))))))

(defn leave-listener! [relay id ch]
  (async/close! ch)
  (when-let [session (get @(:sessions relay) id)]
    (swap! session update :listeners disj ch)
    (close-if-idle! relay id session)))

(defn subscribe!
  "Sends the titles of station `id` to `send!` (a function of one message)
  under `sub-key`, until `unsubscribe!`. The current title, if any, goes at once.
  When the station has no titles, `send!` gets `unavailable` instead."
  [relay id sub-key send!]
  (let [{:keys [url error]} (station-url relay id)]
    (if error
      (send! (protocol/unavailable id (case error
                                        (:unknown-station :hls) error
                                        :unreachable)))
      (let [session (enter! relay id url #(assoc-in % [:subscribers sub-key] send!))]
        (if (= :busy session)
          (send! (protocol/unavailable id :busy))
          (let [{:keys [status metaint title titled?]} @session]
            (cond
              (and (= :live status) (nil? metaint)) (send! (protocol/unavailable id :no-metadata))
              titled?                               (send! (protocol/title id title)))))))))

(defn unsubscribe! [relay id sub-key]
  (when-let [session (get @(:sessions relay) id)]
    (swap! session update :subscribers dissoc sub-key)
    (close-if-idle! relay id session)))
