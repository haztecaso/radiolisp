(ns radiolisp.common.protocol
  "The messages radiolisp's WebSocket carries, shared by the backend and the
  frontend. A message is an EDN vector `[type payload]` in a text frame.
  Server messages are frontend actions as they are: the frontend dispatches
  whatever `server-message?` accepts, and nothing else."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(def max-frame-chars
  "Frames longer than this are dropped without being read."
  1024)

(def reasons
  "Why a station has no titles."
  #{:no-metadata :hls :unknown-station :unreachable :busy})

(def ^:private station-id-re
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn station-id?
  "True for a Radio Browser station uuid (lower case, as the API gives it)."
  [x]
  ;; The length check also stops a trailing newline, which `$` lets through on the JVM.
  (and (string? x) (= 36 (count x)) (some? (re-find station-id-re x))))

;; client → server

(defn subscribe [id] [:now-playing/subscribe {:id id}])
(defn unsubscribe [] [:now-playing/unsubscribe {}])

;; server → client

(defn title [id text] [:now-playing/title {:id id :title text}])
(defn unavailable [id reason] [:now-playing/unavailable {:id id :reason reason}])
(defn rejected [reason] [:now-playing/rejected {:reason reason}])

(defn- message? [x]
  (and (vector? x) (= 2 (count x)) (keyword? (first x)) (map? (second x))))

(defn client-message? [x]
  (and (message? x)
       (let [[kind {:keys [id]}] x]
         (case kind
           :now-playing/subscribe   (station-id? id)
           :now-playing/unsubscribe true
           false))))

(defn server-message? [x]
  (and (message? x)
       (let [[kind {:keys [id reason] text :title}] x]
         (case kind
           :now-playing/title       (and (station-id? id) (or (nil? text) (string? text)))
           :now-playing/unavailable (and (station-id? id) (contains? reasons reason))
           :now-playing/rejected    (= :malformed reason)
           false))))

(defn encode [message]
  (pr-str message))

(defn decode
  "The message in `text` when `valid?` accepts it, else nil. Tagged literals
  are refused, so only plain data gets through."
  [text valid?]
  (when (and (string? text) (<= (count text) max-frame-chars))
    (let [x (try (edn/read-string {:readers {} :default (fn [_ _] (throw (ex-info "tag" {})))} text)
                 (catch #?(:clj Exception :cljs :default) _ nil))]
      (when (valid? x) x))))
