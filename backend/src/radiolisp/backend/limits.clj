(ns radiolisp.backend.limits
  "How many proxy listeners and sockets each IP and the whole server may
  have, and how many stream requests an IP may make per minute. Pure
  functions over a map; `radiolisp.backend.http` keeps it in an atom.
  An IP with nothing open and no recent request is forgotten."
  (:refer-clojure :exclude [empty]))

(def empty {:listeners {} :sockets {} :requests {}})

(def window-ms 60000)

(defn- total [counts] (reduce + 0 (vals counts)))

(defn- dec-or-drop [m k]
  (let [n (dec (get m k 0))]
    (if (pos? n) (assoc m k n) (dissoc m k))))

(defn request
  "Records a stream request from `ip` at `now`. Returns the new state, or
  nil when the IP already made its requests for this minute."
  [state ip now {:keys [stream-requests-per-minute]}]
  (let [recent (filterv #(< (- now %) window-ms) (get-in state [:requests ip]))]
    (when (< (count recent) stream-requests-per-minute)
      (assoc-in state [:requests ip] (conj recent now)))))

(defn prune
  "Forgets request times older than the window, and IPs left with none."
  [state now]
  (update state :requests
          (fn [requests]
            (into {} (keep (fn [[ip times]]
                             (let [recent (filterv #(< (- now %) window-ms) times)]
                               (when (seq recent) [ip recent]))))
                  requests))))

(defn acquire
  "Takes one `kind` (:listeners or :sockets) slot for `ip`. Returns `{:state
  s}`, or `{:denied :per-ip}` / `{:denied :global}`."
  [state kind ip {:keys [max-listeners max-listeners-per-ip max-sockets-per-ip]}]
  (let [counts (get state kind)
        [per-ip global] (case kind
                          :listeners [max-listeners-per-ip max-listeners]
                          :sockets   [max-sockets-per-ip nil])]
    (cond
      (>= (get counts ip 0) per-ip)                 {:denied :per-ip}
      (and global (>= (total counts) global))       {:denied :global}
      :else {:state (update-in state [kind ip] (fnil inc 0))})))

(defn release [state kind ip]
  (update state kind dec-or-drop ip))

(defn acquire!
  "`acquire` on the atom `limits`: nil when granted, else the reason."
  [limits kind ip config]
  (let [denied (volatile! nil)]
    (swap! limits (fn [state]
                    (let [{s :state reason :denied} (acquire state kind ip config)]
                      (vreset! denied reason)
                      (or s state))))
    @denied))

(defn release! [limits kind ip]
  (swap! limits release kind ip))

(defn request!
  "`request` on the atom `limits`: true when allowed."
  [limits ip now config]
  (let [allowed (volatile! false)]
    (swap! limits (fn [state]
                    (if-let [s (request (prune state now) ip now config)]
                      (do (vreset! allowed true) s)
                      (do (vreset! allowed false) (prune state now)))))
    @allowed))
