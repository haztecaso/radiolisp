(ns radiolisp.backend.radio-browser-test
  (:require [clojure.test :refer [deftest is]]
            [radiolisp.backend.config :as config]
            [radiolisp.backend.radio-browser :as rb]))

(deftest resolver-caches
  (let [calls  (atom [])
        clock  (atom 0)
        fetch  (fn [id]
                 (swap! calls conj id)
                 (case id
                   "known"   {:id "known" :url "http://x"}
                   "missing" nil
                   "broken"  (throw (ex-info "down" {}))))
        lookup (rb/resolver fetch #(deref clock))]
    (is (= {:station {:id "known" :url "http://x"}} (lookup "known")))
    (is (= {:station {:id "known" :url "http://x"}} (lookup "known")))
    (is (= {:error :unknown-station} (lookup "missing")))
    (is (= {:error :unknown-station} (lookup "missing")))
    (is (= {:error :unreachable} (lookup "broken")))
    (is (= {:error :unreachable} (lookup "broken")))
    (is (= ["known" "missing" "broken" "broken"] @calls) "errors are not cached")
    (reset! clock (inc config/station-ttl-ms))
    (lookup "known")
    (is (= 5 (count @calls)) "entries expire")))
