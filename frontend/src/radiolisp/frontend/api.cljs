(ns radiolisp.frontend.api
  "Radio Browser over HTTP. Returns promises of plain Clojure data."
  (:require [clojure.string :as str]
            [radiolisp.common.station :as station]))

(def app-id "radiolisp/0.1.0")
(def servers-url "https://all.api.radio-browser.info/json/servers")
;; all.api resolves, by DNS, to every live Radio Browser server.
(def fallback-servers ["all.api.radio-browser.info"])
(def search-limit 50)
(def top-limit 20)
(def servers-timeout-ms 3000)
(def request-timeout-ms 15000)

(defonce ^:private in-flight (atom nil))

(defn- get-json [url ^js signal]
  ;; Radio Browser asks clients to identify themselves; its CORS policy only
  ;; lets browsers send X-Requested-With for that.
  (-> (js/fetch url #js {:headers #js {"X-Requested-With" app-id} :signal signal})
      (.then (fn [^js res]
               (if (.-ok res)
                 (.json res)
                 (throw (ex-info "Radio Browser answered with an error" {:status (.-status res)})))))))

(defn fetch-servers
  "Promise of a shuffled vector of server host names. Never rejects: on any
  failure, or after `servers-timeout-ms`, it resolves to `fallback-servers`."
  []
  (-> (get-json servers-url (js/AbortSignal.timeout servers-timeout-ms))
      (.then (fn [servers]
               (let [names (->> (js->clj servers) (map #(get % "name")) distinct vec)]
                 (if (seq names) (shuffle names) fallback-servers))))
      (.catch (fn [_] fallback-servers))))

(defn- base-url [server path]
  (str "https://" server "/json/" path))

(defn- normalize-all [stations]
  (mapv station/normalize (js->clj stations)))

(defn- timeout-signal []
  (js/AbortSignal.timeout request-timeout-ms))

(defn stations-url
  "Search by name when `query` is given, otherwise today's most played."
  [server query]
  (if query
    (str "https://" server "/json/stations/search?"
         (js/URLSearchParams. #js {:name query :hidebroken "true" :order "clickcount"
                                   :reverse "true" :limit search-limit}))
    (str "https://" server "/json/stations/topclick/" top-limit "?hidebroken=true")))

(defn fetch-stations
  "Promise of normalized stations. Aborts the previous request still in flight.
  A request that hangs fails after `request-timeout-ms`, so the caller can
  retry on another server instead of waiting forever."
  [server query]
  (some-> ^js @in-flight .abort)
  (let [controller (js/AbortController.)]
    (reset! in-flight controller)
    (-> (get-json (stations-url server query)
                  (js/AbortSignal.any #js [(.-signal controller)
                                           (js/AbortSignal.timeout request-timeout-ms)]))
        (.then normalize-all))))

(defn fetch-by-ids
  "Promise of the normalized stations with these uuids, in one request.
  Unknown uuids are simply absent from the answer."
  [server ids]
  (-> (get-json (base-url server (str "stations/byuuid?" (js/URLSearchParams. #js {:uuids (str/join "," ids)})))
                (timeout-signal))
      (.then normalize-all)))

(defn click!
  "Tells Radio Browser that the station is being played (counts a click).
  Fire and forget."
  [server id]
  (-> (get-json (base-url server (str "url/" (js/encodeURIComponent id))) (timeout-signal))
      (.catch (fn [_] nil))))

(defn vote
  "Promise of true when Radio Browser accepted the vote."
  [server id]
  (-> (get-json (base-url server (str "vote/" (js/encodeURIComponent id))) (timeout-signal))
      (.then (fn [^js answer] (true? (.-ok answer))))))

(defn lookup-url
  "Promise of the station that streams `url` (the most voted one when there
  are several), or nil."
  [server url]
  (-> (get-json (base-url server (str "stations/byurl?" (js/URLSearchParams. #js {:url url})))
                (timeout-signal))
      (.then (fn [stations]
               (let [found (normalize-all stations)]
                 (when (seq found) (apply max-key :votes found)))))))

(defn resolve-import
  "Promise of `{:stations [...] :missing n :url-ids {url id}}` for the uuids
  and stream URLs of an imported playlist; `:url-ids` says which station each
  URL that was found belongs to."
  [server ids urls]
  (-> (js/Promise.all
       #js [(if (seq ids) (fetch-by-ids server ids) (js/Promise.resolve []))
            (js/Promise.all (into-array (map #(-> (lookup-url server %) (.catch (fn [_] nil))) urls)))])
      (.then (fn [[by-id by-url]]
               (let [url-ids (into {} (keep (fn [[url station]] (when station [url (:id station)])))
                                   (map vector urls by-url))
                     by-url  (remove nil? by-url)]
                 {:stations (vec (concat by-id by-url))
                  :missing  (+ (- (count ids) (count by-id)) (- (count urls) (count by-url)))
                  :url-ids  url-ids})))))

(defn aborted?
  "True when the request was cancelled on purpose (a newer one replaced it).
  A timeout is a \"TimeoutError\" and counts as a failure."
  [^js error]
  (= "AbortError" (.-name error)))
