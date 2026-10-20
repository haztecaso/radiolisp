(ns radiolisp.frontend.api
  "Radio Browser over HTTP. Returns promises of plain Clojure data."
  (:require [radiolisp.common.station :as station]))

(def app-id "radiolisp/0.1.0")
(def servers-url "https://all.api.radio-browser.info/json/servers")
(def fallback-servers ["de1.api.radio-browser.info" "de2.api.radio-browser.info"])
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
        (.then (fn [stations] (mapv station/normalize (js->clj stations)))))))

(defn aborted?
  "True when the request was cancelled on purpose (a newer one replaced it).
  A timeout is a \"TimeoutError\" and counts as a failure."
  [^js error]
  (= "AbortError" (.-name error)))
