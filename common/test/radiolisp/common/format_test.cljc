(ns radiolisp.common.format-test
  (:require [clojure.test :refer [deftest is]]
            [radiolisp.common.format :as fmt]))

(deftest clock-formats-seconds-as-hh-mm-ss
  (is (= "00:00:00" (fmt/clock 0)))
  (is (= "00:01:05" (fmt/clock 65)))
  (is (= "00:12:34" (fmt/clock 754)))
  (is (= "01:00:00" (fmt/clock 3600)))
  (is (= "00:00:00" (fmt/clock -5))))

(deftest elapsed-counts-whole-seconds-between-two-instants
  (is (= 754 (fmt/elapsed 1000 755999)))
  (is (= 0 (fmt/elapsed nil 5000)))
  (is (= 0 (fmt/elapsed 5000 1000))))
