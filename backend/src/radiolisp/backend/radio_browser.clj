(ns radiolisp.backend.radio-browser
  "Turns a station uuid into a normalized station, asking Radio Browser and
  keeping answers for `station-ttl-ms`. \"No such station\" is kept too, so
  a made-up uuid costs one request, not one per try; errors are not kept."
  (:require [clojure.data.json :as json]
            [radiolisp.backend.config :as config]
            [radiolisp.common.station :as station])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)))

(def api-base "https://all.api.radio-browser.info")
(def max-cached 2000)

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofMillis config/upstream-connect-ms))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(defn fetch-station
  "The normalized station with `id`, or nil when Radio Browser does not know
  it. Throws on network errors and non-2xx answers."
  [id]
  (let [request  (-> (HttpRequest/newBuilder (URI. (str api-base "/json/stations/byuuid/" id)))
                     (.header "User-Agent" config/user-agent)
                     (.timeout (Duration/ofMillis config/upstream-connect-ms))
                     (.GET)
                     (.build))
        response (.send ^HttpClient @client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode response) 299)
      (throw (ex-info "Radio Browser error" {:status (.statusCode response)})))
    (some-> (json/read-str (.body response)) first station/normalize)))

(defn resolver
  "A function from uuid to `{:station s}`, `{:error :unknown-station}` or
  `{:error :unreachable}`. `fetch` is `fetch-station` (tests pass their own)
  and `now` gives the time in ms."
  ([] (resolver fetch-station #(System/currentTimeMillis)))
  ([fetch now]
   (let [cache (atom {})]
     (fn [id]
       (let [t (now)]
         (if-let [{:keys [result]} (let [entry (get @cache id)]
                                     (when (and entry (< (- t (:at entry)) config/station-ttl-ms))
                                       entry))]
           result
           (let [result (try (if-let [s (fetch id)]
                               {:station s}
                               {:error :unknown-station})
                             (catch Exception _ ::failed))]
             (if (= ::failed result)
               {:error :unreachable}
               (do (swap! cache (fn [c]
                                  (cond-> c
                                    (>= (count c) max-cached)
                                    (->> (filter (fn [[_ e]] (< (- t (:at e)) config/station-ttl-ms)))
                                         (into {}))
                                    true (assoc id {:at t :result result}))))
                   result)))))))))
