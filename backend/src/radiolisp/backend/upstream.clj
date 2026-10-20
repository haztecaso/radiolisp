(ns radiolisp.backend.upstream
  "Opens a station's stream. A small HTTP/1.0 client over a socket rather
  than `java.net.http`: SHOUTcast v1 servers answer `ICY 200 OK`, which
  that client refuses, and HTTP/1.0 rules out chunked bodies. It connects
  to the very address `guard` checked, so DNS cannot change in between,
  and follows redirects itself, checking each one. The socket's read
  timeout ends a stalled stream."
  (:require [clojure.string :as str]
            [radiolisp.backend.config :as config]
            [radiolisp.backend.guard :as guard])
  (:import (java.io BufferedInputStream InputStream)
           (java.net InetSocketAddress Socket URI)
           (java.nio.charset StandardCharsets)
           (javax.net.ssl SSLSocket SSLSocketFactory)))

(def max-redirects 5)
(def max-header-bytes 16384)

(defn- fail [message reason]
  (throw (ex-info message {:reason reason})))

(defn- read-line!
  "One header line as ISO-8859-1, without CRLF. Counts bytes into `budget`."
  [^InputStream in budget]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (when (neg? b) (fail "Connection closed in the headers" :unreachable))
        (when (neg? (vswap! budget dec)) (fail "Headers too long" :unreachable))
        (cond
          (= b 10) (str/replace (str sb) #"\r$" "")
          :else    (do (.append sb (char b)) (recur)))))))

(defn- read-head
  "`{:status n :headers {lower-case-name value}}`. Accepts `HTTP/1.x` and
  `ICY` status lines."
  [^InputStream in]
  (let [budget (volatile! max-header-bytes)
        line   (read-line! in budget)
        status (some-> (re-find #"^(?:HTTP/\d(?:\.\d)?|ICY)\s+(\d{3})" line) second parse-long)]
    (when-not status (fail "Not an HTTP response" :unreachable))
    (loop [headers {}]
      (let [line (read-line! in budget)]
        (if (str/blank? line)
          {:status status :headers headers}
          (let [[_ k v] (re-find #"^([^:]+):\s*(.*)$" line)]
            (recur (if k (assoc headers (str/lower-case (str/trim k)) (str/trim v)) headers))))))))

(defn- connect ^Socket [^URI uri address]
  (let [https? (= "https" (.toLowerCase (.getScheme uri)))
        port   (if (neg? (.getPort uri)) (if https? 443 80) (.getPort uri))
        raw    (doto (Socket.)
                 (.connect (InetSocketAddress. ^java.net.InetAddress address (int port))
                           (int config/upstream-connect-ms))
                 (.setSoTimeout (int config/upstream-connect-ms)))]
    (if https?
      (let [^SSLSocket tls (.createSocket ^SSLSocketFactory (SSLSocketFactory/getDefault)
                                          raw (.getHost uri) (int port) true)
            params (.getSSLParameters tls)]
        ;; Verify the certificate against the host name, as a browser would.
        (.setEndpointIdentificationAlgorithm params "HTTPS")
        (.setSSLParameters tls params)
        (.startHandshake tls)
        tls)
      raw)))

(defn- request-bytes ^bytes [^URI uri]
  (let [path (str (or (not-empty (.getRawPath uri)) "/")
                  (some->> (.getRawQuery uri) (str "?")))
        host (if (neg? (.getPort uri)) (.getHost uri) (str (.getHost uri) ":" (.getPort uri)))]
    (.getBytes (str "GET " path " HTTP/1.0\r\n"
                    "Host: " host "\r\n"
                    "User-Agent: " config/user-agent "\r\n"
                    "Accept: */*\r\n"
                    "Icy-MetaData: 1\r\n"
                    "Connection: close\r\n\r\n")
               StandardCharsets/ISO_8859_1)))

(defn- open-once [url opts]
  (let [address (guard/check! url opts)
        uri     (URI. url)
        socket  (try (connect uri address)
                     (catch java.io.IOException e
                       (fail (str "Cannot connect: " (.getMessage e)) :unreachable)))]
    (try
      (let [out (.getOutputStream socket)]
        (.write out (request-bytes uri))
        (.flush out))
      (let [in   (BufferedInputStream. (.getInputStream socket) config/chunk-bytes)
            head (read-head in)]
        (assoc head :socket socket :body in :url url))
      (catch Throwable e
        (.close socket)
        (if (instance? clojure.lang.ExceptionInfo e)
          (throw e)
          (fail (str "Cannot read: " (.getMessage e)) :unreachable))))))

(defn close! [{:keys [^Socket socket]}]
  (when socket
    (try (.close socket) (catch Exception _ nil))))

(defn open!
  "Connects to `url` and reads the response head. Returns `{:url :status
  :headers :content-type :metaint :body :socket}` for a 2xx answer, with the
  socket's read timeout set to `upstream-stall-ms`. Throws `ex-info` with
  `{:reason :forbidden|:unreachable}` otherwise. `opts` go to `guard/check!`."
  [url opts]
  (loop [url url, hops 0]
    (let [{:keys [status headers ^Socket socket] :as response} (open-once url opts)]
      (cond
        (and (#{301 302 303 307 308} status) (get headers "location"))
        (do (close! response)
            (when (>= hops max-redirects) (fail "Too many redirects" :unreachable))
            (recur (str (.resolve (URI. url) (str/trim (get headers "location")))) (inc hops)))

        (<= 200 status 299)
        (do (.setSoTimeout socket (int config/upstream-stall-ms))
            (assoc response
                   :content-type (get headers "content-type")
                   :metaint (some-> (get headers "icy-metaint") parse-long)))

        :else
        (do (close! response)
            (fail (str "Station answered " status) :unreachable))))))
