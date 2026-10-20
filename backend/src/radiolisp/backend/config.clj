(ns radiolisp.backend.config
  "Settings from the environment, with defaults, and the fixed constants.")

(def version "0.1.0")
(def user-agent (str "radiolisp/" version))

(def burst-bytes
  "Audio a new proxy listener gets at once: about 4 s at 128 kbps."
  (* 64 1024))
(def listener-queue-chunks
  "Chunks a proxy listener may fall behind before it is dropped."
  64)
(def chunk-bytes 8192)
(def upstream-connect-ms 10000)
(def upstream-stall-ms
  "A station that sends nothing for this long is taken as gone."
  30000)
(def station-ttl-ms (* 10 60 1000))
(def idle-session-ms
  "How long a session with nobody left stays open, so a reload reuses it."
  5000)
(def retry-delays-ms [1000 2000 4000])
(def ws-ping-ms 20000)
(def ws-idle-timeout-ms 60000)

(def ^:private defaults
  {:port                       8090
   :host                       "127.0.0.1"
   :public-dir                 "../frontend/resources/public"
   :trust-proxy?               false
   :max-sessions               32
   :max-listeners              200
   :max-listeners-per-ip       2
   :max-sockets-per-ip         4
   :stream-requests-per-minute 20
   :allow-private?             false})

(def ^:private variables
  {"PORT"                       [:port :int]
   "HOST"                       [:host :string]
   "PUBLIC_DIR"                 [:public-dir :string]
   "TRUST_PROXY"                [:trust-proxy? :bool]
   "MAX_SESSIONS"               [:max-sessions :int]
   "MAX_LISTENERS"              [:max-listeners :int]
   "MAX_LISTENERS_PER_IP"       [:max-listeners-per-ip :int]
   "MAX_SOCKETS_PER_IP"         [:max-sockets-per-ip :int]
   "STREAM_REQUESTS_PER_MINUTE" [:stream-requests-per-minute :int]})

(defn- parse [kind ^String s]
  (case kind
    :int    (parse-long s)
    :bool   (= "true" s)
    :string s))

(defn from-env
  "The configuration for the environment map `env` (string keys). A value that
  does not parse keeps its default."
  [env]
  (reduce-kv (fn [config variable [k kind]]
               (if-some [v (some->> (get env variable) (parse kind))]
                 (assoc config k v)
                 config))
             defaults
             variables))
