(ns radiolisp.backend.http
  "Routes: the API under `/api`, and the frontend build for everything else."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [radiolisp.backend.socket :as socket]
            [radiolisp.backend.stream :as stream]
            [ring.middleware.content-type :as content-type]
            [ring.util.response :as response]
            [ring.websocket :as ws]))

(defn client-ip
  "The client's IP: the socket's address, or the last `X-Forwarded-For` hop
  when the backend runs behind a proxy it trusts."
  [{:keys [remote-addr headers]} {:keys [trust-proxy?]}]
  (or (when trust-proxy?
        (some-> (get headers "x-forwarded-for") (str/split #",") last str/trim not-empty))
      remote-addr))

(defn- plain [status body]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body body})

(defn- static [{:keys [uri] :as request} public-dir]
  (some-> (response/file-response uri {:root public-dir :index-files? true :allow-symlinks? false})
          (content-type/content-type-response request)))

(defn- routes [{:keys [config] :as system}]
  (fn [{:keys [uri request-method] :as request}]
    (let [ip (client-ip request config)]
      (cond
        (not (#{:get :head} request-method))
        (plain 405 "Method not allowed")

        (= "/api/health" uri)
        (plain 200 "ok")

        (= "/api/ws" uri)
        (if (ws/upgrade-request? request)
          (socket/handle system ip)
          (plain 426 "WebSocket only"))

        (str/starts-with? uri "/api/stream/")
        (stream/handle system (subs uri (count "/api/stream/")) ip)

        (str/starts-with? uri "/api/")
        (plain 404 "Not found")

        :else
        (or (static request (:public-dir config))
            (plain 404 "Not found"))))))

(defn handler
  "The Ring handler for `system` (`:relay :limits :config :sockets`). API
  requests are logged at debug level (method, path, status and time, never
  the client's IP); an exception is logged and answered with 500."
  [system]
  (let [route (routes system)]
    (fn [{:keys [uri request-method] :as request}]
      (let [start (System/nanoTime)]
        (try
          (let [response (route request)]
            (when (str/starts-with? uri "/api/")
              (log/debugf "%s %s %s %.0f ms" (str/upper-case (name request-method)) uri
                          (or (:status response) "upgrade") (/ (- (System/nanoTime) start) 1e6)))
            response)
          (catch Throwable e
            (log/errorf e "%s %s failed" (str/upper-case (name request-method)) uri)
            (plain 500 "Internal error")))))))
