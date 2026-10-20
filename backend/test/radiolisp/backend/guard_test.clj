(ns radiolisp.backend.guard-test
  (:require [clojure.test :refer [deftest is testing]]
            [radiolisp.backend.guard :as guard])
  (:import (java.net InetAddress)))

(defn- public? [ip] (guard/public? (InetAddress/getByName ip)))

(deftest addresses
  (testing "private and special ranges"
    (doseq [ip ["127.0.0.1" "127.8.8.8" "::1" "10.1.2.3" "172.16.0.1" "172.31.255.255"
                "192.168.1.1" "169.254.169.254" "100.64.0.1" "100.127.255.255" "0.0.0.0"
                "0.1.2.3" "224.0.0.1" "255.255.255.255" "fe80::1" "fc00::1" "fd12:3456::1"
                "::ffff:127.0.0.1" "::"]]
      (is (not (public? ip)) ip)))
  (testing "public addresses"
    (doseq [ip ["8.8.8.8" "1.1.1.1" "172.32.0.1" "100.128.0.1" "2001:4860:4860::8888"]]
      (is (public? ip) ip))))

(defn- reason [url opts]
  (try (guard/check! url opts) nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest check
  (is (= :forbidden (reason "http://127.0.0.1:8000/stream" {})))
  (is (= :forbidden (reason "http://[::1]/" {})))
  (is (= :forbidden (reason "http://localhost/" {})))
  (is (= :forbidden (reason "ftp://8.8.8.8/x" {})))
  (is (= :forbidden (reason "file:///etc/passwd" {})))
  (is (= :forbidden (reason "not a url" {})))
  (is (nil? (reason "http://8.8.8.8:8000/live" {})))
  (testing "allow-private? lets loopback through, but not other schemes"
    (is (nil? (reason "http://127.0.0.1:8000/stream" {:allow-private? true})))
    (is (= :forbidden (reason "ftp://127.0.0.1/" {:allow-private? true})))))
