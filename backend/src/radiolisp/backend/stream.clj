(ns radiolisp.backend.stream
  "`GET /api/stream/<uuid>`: the station's audio through the relay, for
  https pages that cannot play an `http://` stream themselves."
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [radiolisp.backend.limits :as limits]
            [radiolisp.backend.relay :as relay]
            [radiolisp.common.protocol :as protocol]
            [ring.core.protocols :as ring-protocols])
  (:import (java.io IOException OutputStream)))

(def ^:private error-status
  {:unknown-station 404
   :hls             422
   :busy            503
   :unreachable     502
   :forbidden       502})

(defn- text [status body]
  {:status  status
   :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "no-store"}
   :body    body})

(defn- audio-body
  "Writes the burst, then each chunk as it arrives, until the channel closes
  or the listener goes away. Leaves the relay and frees the slot at the end."
  [{:keys [chan burst]} on-end]
  (reify ring-protocols/StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (let [^OutputStream out out]
        (try
          (doseq [^bytes piece burst] (.write out piece))
          (.flush out)
          (loop []
            (when-let [^bytes piece (async/<!! chan)]
              (.write out piece)
              (.flush out)
              (recur)))
          (catch IOException _ nil)
          (finally
            (on-end)
            (try (.close out) (catch IOException _ nil))))))))

(defn handle
  "The response for a stream request from `ip` for station `id`."
  [{:keys [relay limits config]} id ip]
  (cond
    (not (protocol/station-id? id))
    (text 400 "Not a station uuid")

    (not (limits/request! limits ip (System/currentTimeMillis) config))
    (do (log/warnf "stream %s: 429, too many requests from one client" id)
        (text 429 "Too many requests"))

    :else
    (if-let [denied (limits/acquire! limits :listeners ip config)]
      (do (log/warnf "stream %s: listener limit (%s)" id (name denied))
          (text (if (= :per-ip denied) 429 503) "Too many listeners"))
      (let [joined (relay/join-listener! relay id)]
        (if-let [error (:error joined)]
          (do (log/infof "stream %s: %d %s" id (error-status error 502) (name error))
              (limits/release! limits :listeners ip)
              (text (error-status error 502) (name error)))
          {:status  200
           :headers {"Content-Type"           (or (:content-type joined) "audio/mpeg")
                     "Cache-Control"          "no-store"
                     "X-Content-Type-Options" "nosniff"}
           :body    (audio-body joined
                                (fn []
                                  (relay/leave-listener! relay id (:chan joined))
                                  (limits/release! limits :listeners ip)))})))))
