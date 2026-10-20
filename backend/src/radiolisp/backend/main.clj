(ns radiolisp.backend.main
  "Starts the backend: Jetty on virtual threads, the relay, the limits and
  the WebSocket pings."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [radiolisp.backend.config :as config]
            [radiolisp.backend.http :as http]
            [radiolisp.backend.limits :as limits]
            [radiolisp.backend.radio-browser :as radio-browser]
            [radiolisp.backend.relay :as relay]
            [radiolisp.backend.socket :as socket]
            [ring.adapter.jetty :as jetty])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
           (org.eclipse.jetty.server Server ServerConnector)
           (org.eclipse.jetty.util.thread QueuedThreadPool)))

(defn- thread-pool
  "Jetty runs requests on virtual threads: each proxy listener blocks one
  while it writes, and there may be hundreds."
  [{:keys [max-listeners]}]
  (doto (QueuedThreadPool. (int (+ 64 max-listeners)) 8)
    (.setVirtualThreadsExecutor (Executors/newVirtualThreadPerTaskExecutor))))

(defn start!
  "Starts a backend for `config` (see `config/from-env`). `:resolve-station`
  replaces the Radio Browser lookup (tests). Returns the system map."
  [{:keys [host port] :as config} & {:keys [resolve-station]}]
  (let [sockets (atom #{})
        system  {:config  config
                 :relay   (relay/create {:resolve-station (or resolve-station (radio-browser/resolver))
                                         :config          config})
                 :limits  (atom limits/empty)
                 :sockets sockets}
        pinger  (doto (Executors/newSingleThreadScheduledExecutor)
                  (.scheduleAtFixedRate #(socket/ping-all! sockets)
                                        (long config/ws-ping-ms) (long config/ws-ping-ms)
                                        TimeUnit/MILLISECONDS))
        server  (jetty/run-jetty (http/handler system)
                                 {:host            host
                                  :port            port
                                  :join?           false
                                  :thread-pool     (thread-pool config)
                                  :ws-idle-timeout config/ws-idle-timeout-ms
                                  :send-server-version? false})]
    (assoc system :server server :pinger pinger)))

(defn port
  "The port `system` listens on (useful with port 0)."
  [{:keys [^Server server]}]
  (.getLocalPort ^ServerConnector (first (.getConnectors server))))

(defn stop! [{:keys [^Server server ^ScheduledExecutorService pinger relay]}]
  (log/info "stopping")
  (.stop server)
  (.shutdownNow pinger)
  (relay/stop! relay))

(defn -main [& _]
  (let [config (config/from-env (System/getenv))
        system (start! config)]
    (log/infof "radiolisp backend %s on http://%s:%d, serving %s" config/version
               (:host config) (port system) (:public-dir config))
    (log/infof "limits: %d sessions, %d listeners (%d per client), %d sockets per client, %d stream requests per minute%s"
               (:max-sessions config) (:max-listeners config) (:max-listeners-per-ip config)
               (:max-sockets-per-ip config) (:stream-requests-per-minute config)
               (if (:trust-proxy? config) ", client address from X-Forwarded-For" ""))
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop! system)))
    (.join ^Server (:server system))))
