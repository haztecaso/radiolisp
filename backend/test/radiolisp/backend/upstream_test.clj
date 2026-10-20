(ns radiolisp.backend.upstream-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [radiolisp.backend.fake-station :as fake]
            [radiolisp.backend.upstream :as upstream]))

(def ^:dynamic *fake* nil)

(use-fixtures :each (fn [t]
                      (let [f (fake/start!)]
                        (try (binding [*fake* f] (t)) (finally ((:stop! f)))))))

(def opts {:allow-private? true})

(defn- reason [path]
  (try (upstream/close! (upstream/open! (str (:url *fake*) path) opts)) nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest icy-status-line
  (let [conn (upstream/open! (str (:url *fake*) "/icy") opts)]
    (try
      (is (= 200 (:status conn)))
      (is (= fake/metaint (:metaint conn)))
      (is (= "audio/mpeg" (:content-type conn)))
      (let [bs (.readNBytes ^java.io.InputStream (:body conn) 100)]
        (is (= 100 (alength bs)))
        (is (fake/audio-byte? bs) "the body starts right after the headers"))
      (testing "the request asks for metadata and identifies radiolisp"
        (is (str/includes? (fake/requests *fake*) "Icy-MetaData: 1"))
        (is (str/includes? (fake/requests *fake*) "User-Agent: radiolisp/")))
      (finally (upstream/close! conn)))))

(deftest plain-http
  (let [conn (upstream/open! (str (:url *fake*) "/plain") opts)]
    (is (nil? (:metaint conn)))
    (is (= "audio/aac" (:content-type conn)))
    (upstream/close! conn)))

(deftest redirects
  (let [conn (upstream/open! (str (:url *fake*) "/redirect") opts)]
    (is (= fake/metaint (:metaint conn)))
    (is (str/ends-with? (:url conn) "/icy"))
    (upstream/close! conn))
  (is (= :unreachable (reason "/loop")))
  (is (= 6 (fake/connection-count *fake* "/loop")) "gives up after five redirects"))

(deftest failures
  (is (= :unreachable (reason "/dead")))
  (testing "private addresses are refused without allow-private?"
    (is (= :forbidden (try (upstream/open! (str (:url *fake*) "/icy") {}) nil
                           (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))
  (testing "nothing listening"
    (is (= :unreachable (try (upstream/open! "http://127.0.0.1:1/" opts) nil
                             (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))
