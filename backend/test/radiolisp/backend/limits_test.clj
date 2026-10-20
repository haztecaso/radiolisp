(ns radiolisp.backend.limits-test
  (:require [clojure.test :refer [deftest is testing]]
            [radiolisp.backend.limits :as limits]))

(def config {:max-listeners 3 :max-listeners-per-ip 2 :max-sockets-per-ip 1
             :stream-requests-per-minute 2})

(defn- take-slot [state kind ip]
  (:state (limits/acquire state kind ip config)))

(deftest listeners
  (let [s (-> limits/empty (take-slot :listeners "a") (take-slot :listeners "a"))]
    (is (= {:denied :per-ip} (limits/acquire s :listeners "a" config)))
    (let [s (take-slot s :listeners "b")]
      (is (= {:denied :global} (limits/acquire s :listeners "c" config)))
      (testing "release frees the slot and forgets the IP at zero"
        (let [s (limits/release s :listeners "b")]
          (is (= {"a" 2} (:listeners s)))
          (is (:state (limits/acquire s :listeners "c" config))))))))

(deftest sockets
  (let [s (take-slot limits/empty :sockets "a")]
    (is (= {:denied :per-ip} (limits/acquire s :sockets "a" config)))
    (is (:state (limits/acquire s :sockets "b" config)) "no global cap on sockets")))

(deftest requests
  (let [s (-> limits/empty
              (limits/request "a" 0 config)
              (limits/request "a" 1000 config))]
    (is (nil? (limits/request s "a" 2000 config)))
    (is (some? (limits/request s "b" 2000 config)))
    (testing "the window slides"
      (is (some? (limits/request s "a" 60001 config))))
    (testing "prune forgets old requests"
      (is (= {} (:requests (limits/prune s 70000)))))))

(deftest atom-helpers
  (let [a (atom limits/empty)]
    (is (nil? (limits/acquire! a :sockets "a" config)))
    (is (= :per-ip (limits/acquire! a :sockets "a" config)))
    (limits/release! a :sockets "a")
    (is (= {} (:sockets @a)))
    (is (true? (limits/request! a "a" 0 config)))
    (is (true? (limits/request! a "a" 1 config)))
    (is (false? (limits/request! a "a" 2 config)))))
