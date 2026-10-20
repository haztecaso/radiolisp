(ns radiolisp.common.protocol-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [radiolisp.common.protocol :as p]))

(def id "960e57c5-0601-11e8-ae97-52543be04c81")

(deftest station-id
  (is (p/station-id? id))
  (is (not (p/station-id? (str/upper-case id))) "Radio Browser uuids are lower case")
  (is (not (p/station-id? "960e57c5")))
  (is (not (p/station-id? (str id "\n"))))
  (is (not (p/station-id? nil)))
  (is (not (p/station-id? 42))))

(deftest round-trip
  (testing "client messages"
    (doseq [message [(p/subscribe id) (p/unsubscribe)]]
      (is (= message (p/decode (p/encode message) p/client-message?)))))
  (testing "server messages"
    (doseq [message [(p/title id "Artist - Song")
                     (p/title id nil)
                     (p/unavailable id :no-metadata)
                     (p/unavailable id :busy)
                     (p/rejected :malformed)]]
      (is (= message (p/decode (p/encode message) p/server-message?))))))

(deftest shapes
  (is (= [:now-playing/subscribe {:id id}] (p/subscribe id)))
  (is (= [:now-playing/unsubscribe {}] (p/unsubscribe)))
  (is (= [:now-playing/title {:id id :title "x"}] (p/title id "x")))
  (is (= [:now-playing/unavailable {:id id :reason :hls}] (p/unavailable id :hls)))
  (is (= [:now-playing/rejected {:reason :malformed}] (p/rejected :malformed))))

(deftest decode-rejects
  (testing "unreadable text"
    (is (nil? (p/decode "[:now-playing/subscribe {" p/client-message?)))
    (is (nil? (p/decode "" p/client-message?)))
    (is (nil? (p/decode nil p/client-message?))))
  (testing "tagged literals and other non-plain data"
    (is (nil? (p/decode "#inst \"2026-10-20\"" p/client-message?))))
  (testing "unknown types and wrong direction"
    (is (nil? (p/decode "[:app/init {}]" p/server-message?)))
    (is (nil? (p/decode (p/encode (p/subscribe id)) p/server-message?)))
    (is (nil? (p/decode (p/encode (p/title id "x")) p/client-message?))))
  (testing "bad payloads"
    (is (nil? (p/decode (pr-str [:now-playing/subscribe {:id "nope"}]) p/client-message?)))
    (is (nil? (p/decode (pr-str [:now-playing/subscribe {:id id} :extra]) p/client-message?)))
    (is (nil? (p/decode (pr-str [:now-playing/subscribe]) p/client-message?)))
    (is (nil? (p/decode (pr-str [:now-playing/title {:id id :title 42}]) p/server-message?)))
    (is (nil? (p/decode (pr-str [:now-playing/unavailable {:id id :reason :why}]) p/server-message?)))
    (is (nil? (p/decode (pr-str [:now-playing/rejected {:reason :other}]) p/server-message?)))
    (is (nil? (p/decode (pr-str {:id id}) p/client-message?))))
  (testing "frames over the size limit are not even read"
    (is (nil? (p/decode (pr-str [:now-playing/title {:id id :title (apply str (repeat 2000 "a"))}])
                        p/server-message?)))))
