(ns radiolisp.backend.main
  "Starts the backend: Jetty on virtual threads, the relay, the limits and
  the WebSocket pings."
  (:gen-class)
  (:require [radiolisp.backend.config :as config]
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
  (.stop server)
  (.shutdownNow pinger)
  (relay/stop! relay))

(defn -main [& _]
  (let [config (config/from-env (System/getenv))
        system (start! config)]
    (println (str "radiolisp backend on http://" (:host config) ":" (port system)
                  ", serving " (:public-dir config)))
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop! system)))
    (.join ^Server (:server system))))
