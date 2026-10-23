(ns radiolisp.frontend.stream-url-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.stream-url :as stream-url]))

(def id "960e57c5-0601-11e8-ae97-52543be04c81")

(deftest candidates
  (testing "https streams play as they are"
    (is (= ["https://a.example/live"] (stream-url/candidates "https://a.example/live" id false true true))))
  (testing "on an http page, the https form and then the original"
    (is (= ["https://a.example:8000/live" "http://a.example:8000/live"]
           (stream-url/candidates "http://a.example:8000/live" id false false true))))
  (testing "on an https page, the https form and then the proxy"
    (is (= ["https://a.example:8000/live" (str "/api/stream/" id)]
           (stream-url/candidates "http://a.example:8000/live" id false true true))))
  (testing "HLS never goes through the proxy"
    (is (= ["https://a.example/x.m3u8"] (stream-url/candidates "http://a.example/x.m3u8" id true true true))))
  (testing "without a backend, an https page has only the https form"
    (is (= ["https://a.example:8000/live"]
           (stream-url/candidates "http://a.example:8000/live" id false true false)))))
