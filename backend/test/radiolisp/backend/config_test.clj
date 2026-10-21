(ns radiolisp.backend.config-test
  (:require [clojure.test :refer [deftest is]]
            [radiolisp.backend.config :as config]))

(deftest defaults
  (let [c (config/from-env {})]
    (is (= 8090 (:port c)))
    (is (= "127.0.0.1" (:host c)))
    (is (false? (:trust-proxy? c)))
    (is (false? (:allow-private? c)))
    (is (= 2 (:max-listeners-per-ip c)))))

(deftest from-env
  (let [c (config/from-env {"PORT" "9000" "TRUST_PROXY" "true" "MAX_SESSIONS" "5"
                            "PUBLIC_DIR" "/srv/www" "MAX_LISTENERS" "lots"})]
    (is (= 9000 (:port c)))
    (is (true? (:trust-proxy? c)))
    (is (= 5 (:max-sessions c)))
    (is (= "/srv/www" (:public-dir c)))
    (is (= 200 (:max-listeners c)) "an unparsable value keeps the default")))

(deftest public-dir-beside
  (is (= "/srv/radiolisp/public" (config/public-dir-beside "/srv/radiolisp/radiolisp-backend.jar"))
      "a jar looks for public/ in its own folder")
  (is (nil? (config/public-dir-beside "/home/me/radiolisp/backend/src/"))
      "classes from a folder (bb backend) keep the default")
  (is (nil? (config/public-dir-beside nil))))
