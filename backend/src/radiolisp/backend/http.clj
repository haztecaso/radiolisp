(ns radiolisp.backend.http
  "Routes: the API under `/api`, and the frontend build for everything else."
  (:require [clojure.string :as str]
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

(defn handler
  "The Ring handler for `system` (`:relay :limits :config :sockets`)."
  [{:keys [config] :as system}]
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
