(ns radiolisp.backend.integration-test
  "The whole backend on a free port, in front of the fake station."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [radiolisp.backend.config :as config]
            [radiolisp.backend.fake-station :as fake]
            [radiolisp.backend.main :as main]
            [radiolisp.backend.relay :as relay]
            [radiolisp.common.protocol :as protocol])
  (:import (java.io InputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Version HttpRequest HttpResponse HttpResponse$BodyHandlers
                          WebSocket WebSocket$Listener)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def icy-id "abcdef00-0000-0000-0000-000000000001")
(def plain-id "abcdef00-0000-0000-0000-000000000002")
(def dead-id "abcdef00-0000-0000-0000-000000000003")
(def hls-id "abcdef00-0000-0000-0000-000000000004")
(def finite-id "abcdef00-0000-0000-0000-000000000005")
(def unknown-id "abcdef00-0000-0000-0000-000000000009")

(def ^:dynamic *fake* nil)
(def ^:dynamic *system* nil)
(def ^:dynamic *base* nil)

(defn- resolver [fake]
  (let [stations {icy-id    "/icy"
                  plain-id  "/plain"
                  dead-id   "/dead"
                  hls-id    "/live.m3u8"
                  finite-id "/finite"}]
    (fn [id]
      (if-let [path (get stations id)]
        {:station {:id id :url (str (:url fake) path) :hls? (= id hls-id)}}
        {:error :unknown-station}))))

(defn- public-dir []
  (let [dir (.toFile (Files/createTempDirectory "radiolisp-public" (make-array FileAttribute 0)))]
    (spit (io/file dir "index.html") "<!doctype html><title>radiolisp</title>")
    (str dir)))

(def test-config
  (merge (config/from-env {})
         {:port 0 :allow-private? true :public-dir (public-dir)
          :max-listeners-per-ip 3 :stream-requests-per-minute 1000}))

(defn- with-backend [config t]
  (let [f (fake/start!)
        s (main/start! config :resolve-station (resolver f))]
    (try
      (binding [*fake* f *system* s *base* (str "http://127.0.0.1:" (main/port s))]
        (with-redefs [config/idle-session-ms 200]
          (t)))
      (finally
        (main/stop! s)
        ((:stop! f))))))

(use-fixtures :each (partial with-backend test-config))

;; ---------------------------------------------------------------- helpers

(def ^:private client
  (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) (.build)))

(defn- get-stream ^HttpResponse [path]
  (.send ^HttpClient client
         (.build (HttpRequest/newBuilder (URI. (str *base* path))))
         (HttpResponse$BodyHandlers/ofInputStream)))

(defn- get-text ^HttpResponse [path]
  (.send ^HttpClient client
         (.build (HttpRequest/newBuilder (URI. (str *base* path))))
         (HttpResponse$BodyHandlers/ofString)))

(defn- read-n ^bytes [^HttpResponse response n]
  (.readNBytes ^InputStream (.body response) (int n)))

(defn- close-body! [^HttpResponse response]
  (.close ^InputStream (.body response)))

(defn- eventually
  "Polls `f` until it is truthy or `ms` pass; returns its last value."
  [ms f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [v (f)]
        (if (or v (> (System/currentTimeMillis) deadline))
          v
          (do (Thread/sleep 20) (recur)))))))

(defn- ws-connect
  "Opens a WebSocket; returns `{:ws socket :messages queue}`."
  []
  (let [messages (LinkedBlockingQueue.)
        listener (reify WebSocket$Listener
                   (onText [_ ws data _last]
                     (.put messages (str data))
                     (.request ws 1)
                     nil))
        ws       (.join (.buildAsync (.newWebSocketBuilder ^HttpClient client)
                                     (URI. (str (str/replace *base* "http:" "ws:") "/api/ws"))
                                     listener))]
    {:ws ws :messages messages}))

(defn- ws-send! [{:keys [^WebSocket ws]} message]
  (.join (.sendText ws (protocol/encode message) true)))

(defn- ws-next [{:keys [^LinkedBlockingQueue messages]}]
  (some-> (.poll messages 10 TimeUnit/SECONDS) (protocol/decode protocol/server-message?)))

(defn- listeners-of [id]
  (some-> (get @(get-in *system* [:relay :sessions]) id) deref :listeners count))

;; ---------------------------------------------------------------- tests

(deftest health-and-static
  (is (= "ok" (.body (get-text "/api/health"))))
  (let [index (get-text "/")]
    (is (= 200 (.statusCode index)))
    (is (str/includes? (.body index) "radiolisp"))
    (is (str/starts-with? (first (.allValues (.headers index) "Content-Type")) "text/html")))
  (is (= 404 (.statusCode (get-text "/nope.js"))))
  (is (#{400 404} (.statusCode (get-text "/../deps.edn"))) "nothing outside the public dir")
  (is (= 404 (.statusCode (get-text "/api/other"))))
  (is (= 426 (.statusCode (get-text "/api/ws")))))

(deftest stream-errors
  (is (= 400 (.statusCode (get-text "/api/stream/not-a-uuid"))))
  (is (= 400 (.statusCode (get-text (str "/api/stream/" (str/upper-case icy-id))))))
  (is (= 404 (.statusCode (get-text (str "/api/stream/" unknown-id)))))
  (is (= 422 (.statusCode (get-text (str "/api/stream/" hls-id)))))
  (is (= 502 (.statusCode (get-text (str "/api/stream/" dead-id)))))
  (testing "failed joins give their slot back"
    (is (= {} (:listeners @(:limits *system*))))))

(deftest listeners-share-one-connection
  (let [a (get-stream (str "/api/stream/" icy-id))
        b (get-stream (str "/api/stream/" icy-id))]
    (try
      (is (= 200 (.statusCode a)))
      (is (= "audio/mpeg" (first (.allValues (.headers a) "Content-Type"))))
      (is (= "no-store" (first (.allValues (.headers a) "Cache-Control"))))
      (let [bytes-a (read-n a 20000)
            bytes-b (read-n b 20000)]
        (is (= 20000 (alength bytes-a) (alength bytes-b)))
        (is (fake/audio-byte? bytes-a) "audio only: no metadata, nothing lost or repeated")
        (is (fake/audio-byte? bytes-b)))
      (is (= 1 (fake/connection-count *fake* "/icy")))
      (is (= 1 (relay/session-count (:relay *system*))))
      (finally (close-body! a) (close-body! b))))
  (testing "the session closes once everybody has left"
    (is (eventually 3000 #(zero? (relay/session-count (:relay *system*)))))))

(deftest burst-on-connect
  (let [a (get-stream (str "/api/stream/" icy-id))]
    (try
      (read-n a 100000)
      (let [b     (get-stream (str "/api/stream/" icy-id))
            start (System/nanoTime)
            bs    (read-n b config/burst-bytes)]
        (is (< (/ (- (System/nanoTime) start) 1e6) 1000) "the burst arrives at once")
        (is (fake/audio-byte? bs))
        (close-body! b))
      (finally (close-body! a)))))

(deftest titles-over-websocket
  (let [c (ws-connect)]
    (try
      (ws-send! c (protocol/subscribe icy-id))
      (let [[kind {:keys [id title]}] (ws-next c)]
        (is (= :now-playing/title kind))
        (is (= icy-id id))
        (is (re-matches #"Song \d+" title)))
      (testing "the next title follows"
        (let [[_ {:keys [title]}] (ws-next c)]
          (is (re-matches #"Song \d+" title))))
      (testing "a proxy listener joins the same session"
        (let [a (get-stream (str "/api/stream/" icy-id))]
          (read-n a 1000)
          (is (= 1 (fake/connection-count *fake* "/icy")))
          (close-body! a)))
      (testing "unsubscribing closes the session"
        (ws-send! c (protocol/unsubscribe))
        (is (eventually 3000 #(zero? (relay/session-count (:relay *system*))))))
      (finally (.abort ^WebSocket (:ws c))))))

(deftest websocket-unavailable
  (let [c (ws-connect)]
    (try
      (ws-send! c (protocol/subscribe plain-id))
      (is (= (protocol/unavailable plain-id :no-metadata) (ws-next c)))
      (ws-send! c (protocol/subscribe hls-id))
      (is (= (protocol/unavailable hls-id :hls) (ws-next c)))
      (ws-send! c (protocol/subscribe unknown-id))
      (is (= (protocol/unavailable unknown-id :unknown-station) (ws-next c)))
      (ws-send! c (protocol/subscribe dead-id))
      (is (= (protocol/unavailable dead-id :unreachable) (ws-next c)))
      (.join (.sendText ^WebSocket (:ws c) "[:app/init {}]" true))
      (is (= (protocol/rejected :malformed) (ws-next c)))
      (testing "a session without titles nor listeners does not stay open"
        (is (eventually 3000 #(zero? (relay/session-count (:relay *system*))))))
      (finally (.abort ^WebSocket (:ws c))))))

(deftest slow-listener-is-dropped
  (let [slow (get-stream (str "/api/stream/" icy-id))
        fast (get-stream (str "/api/stream/" icy-id))]
    (try
      (read-n slow 10)
      (is (eventually 30000 (fn []
                              (read-n fast 65536)
                              (= 1 (listeners-of icy-id))))
          "the slow listener leaves while the fast one keeps going")
      (is (fake/audio-byte? (read-n fast 50000)))
      (finally (close-body! slow) (close-body! fast)))))

(deftest reconnects-when-the-station-drops
  (let [a (get-stream (str "/api/stream/" finite-id))]
    (try
      (is (= 200 (.statusCode a)))
      (read-n a (* 3 fake/metaint))
      (is (eventually 5000 #(>= (fake/connection-count *fake* "/finite") 2)))
      (finally (close-body! a)))))

(deftest per-ip-limit
  (let [streams (doall (for [_ (range 3)] (get-stream (str "/api/stream/" icy-id))))]
    (try
      (is (every? #(= 200 (.statusCode ^HttpResponse %)) streams))
      (is (= 429 (.statusCode (get-text (str "/api/stream/" icy-id)))))
      (finally (run! close-body! streams))))
  (testing "slots come back when listeners leave"
    (is (eventually 3000 #(= {} (:listeners @(:limits *system*)))))))

(deftest request-rate-limit
  (with-backend (assoc test-config :stream-requests-per-minute 2)
    (fn []
      (is (= 404 (.statusCode (get-text (str "/api/stream/" unknown-id)))))
      (is (= 404 (.statusCode (get-text (str "/api/stream/" unknown-id)))))
      (is (= 429 (.statusCode (get-text (str "/api/stream/" unknown-id))))))))
