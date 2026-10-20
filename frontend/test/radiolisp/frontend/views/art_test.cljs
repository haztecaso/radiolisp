(ns radiolisp.frontend.views.art-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.views.art :as art]))

(deftest fit-covers-big-square-images
  (is (= :cover (art/fit 300 300)))
  (is (= :cover (art/fit 150 180)))
  (is (= :cover (art/fit 240 200))))

(deftest fit-contains-wide-or-small-images
  (testing "too wide or too tall"
    (is (= :contain (art/fit 400 120)))
    (is (= :contain (art/fit 200 300))))
  (testing "squarish but under 150 px"
    (is (= :contain (art/fit 120 120))))
  (testing "tiny images are not blown up"
    (is (= :small (art/fit 64 64)))
    (is (= :small (art/fit 95 40)))))

(deftest fit-of-nothing-is-broken
  (is (= :broken (art/fit 0 0)))
  (is (= :broken (art/fit nil nil))))

(deftest initials-take-two-words
  (is (= "FI" (art/initials "France Info")))
  (is (= "R" (art/initials "Radio")))
  (is (= "JR" (art/initials "jazz radio (24/7) best")))
  (is (= "ÉM" (art/initials "Émisora — Música")))
  (is (= "•" (art/initials "")))
  (is (= "•" (art/initials nil))))

(deftest tint-is-stable
  (is (= (art/tint "960e57c5-0601-11e8-ae97-52543be04c81")
         (art/tint "960e57c5-0601-11e8-ae97-52543be04c81")))
  (is (every? #{0 1 2 3} (map art/tint ["a" "b" "c" "d" "e" "f" "" nil]))))
