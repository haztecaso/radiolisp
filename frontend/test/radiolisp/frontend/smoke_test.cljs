(ns radiolisp.frontend.smoke-test
  (:require [cljs.test :refer [deftest is]]))

(deftest the-toolchain-runs
  (is (= 2 (+ 1 1))))
