(ns radiolisp.backend.fake-station
  "A local stand-in for radio stations, over a plain server socket, so the
  tests can speak ICY status lines and cut connections at will.

  Paths:
  - `/icy`      ICY 200 with `icy-metaint`; titles `Song 0`, `Song 1`… change
                every `blocks-per-title` blocks; endless.
  - `/plain`    HTTP 200 without metadata; endless.
  - `/redirect` 302 to `/icy`; `/loop` redirects to itself.
  - `/dead`     500.
  - `/finite`   like `/icy`, but closes after three blocks.
  Audio bytes cycle 0..250, so a test can tell metadata from audio: a
  metadata block starts with `StreamTitle`, and its length byte is never
  followed by audio."
  (:require [clojure.string :as str])
  (:import (java.io InputStream OutputStream)
           (java.net InetAddress ServerSocket Socket SocketException)
           (java.nio.charset StandardCharsets)))

(def metaint 1024)
(def blocks-per-title 4)
(def block-pause-ms 2)

(defn- read-request-path [^InputStream in]
  (let [sb (StringBuilder.)]
    (loop [last4 ""]
      (let [b (.read in)]
        (when-not (neg? b)
          (.append sb (char b))
          (let [last4 (str (subs last4 (max 0 (- (count last4) 3))) (char b))]
            (when-not (= last4 "\r\n\r\n") (recur last4))))))
    {:path    (second (re-find #"^GET (\S+)" (str sb)))
     :headers (str sb)}))

(defn- write! [^OutputStream out ^String s]
  (.write out (.getBytes s StandardCharsets/ISO_8859_1)))

(defn- audio-block ^bytes [start]
  (byte-array (map #(unchecked-byte (mod (+ start %) 251)) (range metaint))))

(defn metadata-block ^bytes [title]
  (let [text  (.getBytes (str "StreamTitle='" title "';") StandardCharsets/UTF_8)
        units (long (Math/ceil (/ (alength text) 16.0)))
        out   (byte-array (inc (* 16 units)))]
    (aset-byte out 0 (unchecked-byte units))
    (System/arraycopy text 0 out 1 (alength text))
    out))

(defn- stream! [^OutputStream out metadata? max-blocks]
  (loop [block 0]
    (when (or (nil? max-blocks) (< block max-blocks))
      (.write out (audio-block (* block metaint)))
      (when metadata?
        (if (zero? (mod block blocks-per-title))
          (.write out (metadata-block (str "Song " (quot block blocks-per-title))))
          (.write out (byte-array [0]))))
      (.flush out)
      (Thread/sleep (long block-pause-ms))
      (recur (inc block)))))

(defn- serve [^Socket socket connections]
  (with-open [socket socket]
    (let [in  (.getInputStream socket)
          out (.getOutputStream socket)
          {:keys [path headers]} (read-request-path in)]
      (swap! connections update path (fnil inc 0))
      (swap! connections update :requests (fnil conj []) headers)
      (try
        (case path
          "/icy"      (do (write! out (str "ICY 200 OK\r\nicy-metaint: " metaint
                                           "\r\ncontent-type: audio/mpeg\r\n\r\n"))
                          (stream! out true nil))
          "/finite"   (do (write! out (str "HTTP/1.0 200 OK\r\nicy-metaint: " metaint
                                           "\r\ncontent-type: audio/mpeg\r\n\r\n"))
                          (stream! out true 3))
          "/plain"    (do (write! out "HTTP/1.0 200 OK\r\nContent-Type: audio/aac\r\n\r\n")
                          (stream! out false nil))
          "/redirect" (write! out "HTTP/1.1 302 Found\r\nLocation: /icy\r\n\r\n")
          "/loop"     (write! out "HTTP/1.1 302 Found\r\nLocation: /loop\r\n\r\n")
          (write! out "HTTP/1.1 500 Oops\r\n\r\n"))
        (.flush out)
        (catch SocketException _ nil)))))

(defn start!
  "Starts the fake on a free port. Returns `{:url base :connections atom
  :stop! fn}`; `connections` counts connections per path."
  []
  (let [server      (ServerSocket. 0 50 (InetAddress/getLoopbackAddress))
        connections (atom {})]
    (Thread/startVirtualThread
     (fn []
       (try
         (loop []
           (let [socket (.accept server)]
             (Thread/startVirtualThread #(try (serve socket connections) (catch Exception _ nil)))
             (recur)))
         (catch SocketException _ nil))))
    {:url         (str "http://127.0.0.1:" (.getLocalPort server))
     :connections connections
     :stop!       #(.close server)}))

(defn connection-count [fake path]
  (get @(:connections fake) path 0))

(defn audio-byte?
  "True when the bytes in `bs` are consecutive audio bytes of the fake."
  [bs]
  (every? (fn [[a b]] (= (mod (inc (bit-and 0xff a)) 251) (bit-and 0xff b)))
          (partition 2 1 bs)))

(defn requests [fake]
  (str/join "\n" (:requests @(:connections fake))))
