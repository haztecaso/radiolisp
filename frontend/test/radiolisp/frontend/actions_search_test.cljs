(ns radiolisp.frontend.actions-search-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.store :as store]))

(def jazz {:id "j1" :name "Jazz Radio" :url "http://jazz.example/stream" :hls? false})
(def blues {:id "b1" :name "Blues FM" :url "http://blues.example/stream" :hls? false})

(def ready
  "Servers known, the top list loaded and shown."
  (-> store/initial-state
      (assoc :api {:servers ["s1" "s2"] :current 0})
      (update :search assoc :status :done :results [jazz blues] :top [jazz blues])))

(defn- run-all
  "Applies `actions` in order and collects every effect."
  [state & actions]
  (reduce (fn [{:keys [state effects]} action]
            (let [r (a/handle state action)]
              {:state (:state r) :effects (into effects (:effects r))}))
          {:state state :effects []}
          actions))

(deftest query-active-needs-two-characters
  (is (false? (a/query-active? "")))
  (is (false? (a/query-active? " j ")))
  (is (true? (a/query-active? "ja"))))

(deftest init-sets-locale-and-asks-for-servers
  (let [{:keys [state effects]} (a/handle store/initial-state [:app/init {:locale :es}])]
    (is (= :es (:locale state)))
    (is (= [[:dom/lang :es] [:audio/volume 0.7] [:api/servers] [:dom/focus :search-input]] effects))))

(deftest servers-loaded-runs-the-first-fetch
  (let [{:keys [state effects]} (a/handle store/initial-state [:api/servers-loaded ["s1" "s2"]])]
    (is (= {:servers ["s1" "s2"] :current 0} (:api state)))
    (is (= 1 (get-in state [:search :seq])))
    (is (some #{[:api/fetch {:server "s1" :query nil :seq 1}]} effects))))

(deftest run-without-servers-waits
  (let [{:keys [effects]} (a/handle store/initial-state [:search/run])]
    (is (= [] effects))))

(deftest typing-debounces-the-search
  (let [{:keys [state effects]} (a/handle ready [:search/typed "jaz"])]
    (is (= "jaz" (get-in state [:search :query])))
    (is (= :loading (get-in state [:search :status])))
    (is (= [[:timer/start :search 300 [:search/run]]] effects))))

(deftest run-fetches-the-trimmed-query
  (let [{:keys [state effects]} (run-all ready [:search/typed " jazz "] [:search/run])]
    (is (= 1 (get-in state [:search :seq])))
    (is (some #{[:api/fetch {:server "s1" :query "jazz" :seq 1}]} effects))))

(deftest short-query-goes-back-to-the-cached-top-list
  (let [{:keys [state effects]} (run-all ready [:search/typed "jazz"] [:search/run] [:search/typed "j"])]
    (is (= :done (get-in state [:search :status])))
    (is (= [jazz blues] (get-in state [:search :results])))
    (testing "the search in flight is invalidated"
      (is (= 2 (get-in state [:search :seq]))))
    (is (some #{[:timer/cancel :search]} effects))))

(deftest loaded-results-replace-the-list
  (let [{:keys [state]} (run-all ready [:search/typed "blues"] [:search/run] [:search/loaded 1 [blues]])]
    (is (= :done (get-in state [:search :status])))
    (is (= [blues] (get-in state [:search :results])))
    (is (= [jazz blues] (get-in state [:search :top])) "a search does not overwrite the top cache")))

(deftest stale-responses-are-ignored
  (let [{:keys [state]} (run-all ready
                                 [:search/typed "ja"] [:search/run]
                                 [:search/typed "jazz"] [:search/run]
                                 [:search/loaded 1 [blues]])]
    (is (= :loading (get-in state [:search :status])))
    (is (= 2 (get-in state [:search :seq])))))

(deftest loaded-top-list-is-cached
  (let [state (assoc-in store/initial-state [:api] {:servers ["s1"] :current 0})
        {:keys [state]} (run-all state [:search/run] [:search/loaded 1 [jazz]])]
    (is (= [jazz] (get-in state [:search :top])))))

(deftest failure-retries-once-on-the-next-server
  (let [{:keys [state effects]} (run-all ready [:search/typed "jazz"] [:search/run] [:search/failed 1 5000])]
    (is (= 1 (get-in state [:api :current])))
    (is (true? (get-in state [:search :retried?])))
    (is (some #{[:api/fetch {:server "s2" :query "jazz" :seq 1}]} effects))
    (is (= :loading (get-in state [:search :status])))))

(deftest second-failure-shows-the-error
  (let [{:keys [state]} (run-all ready [:search/typed "jazz"] [:search/run]
                                 [:search/failed 1 5000] [:search/failed 1 6000])]
    (is (= :error (get-in state [:search :status])))
    (is (= [:error/network {:at 6000}] (get-in state [:search :error])))))

(deftest open-close-and-modes
  (let [{:keys [state effects]} (a/handle (assoc-in ready [:search :open?] false) [:search/open])]
    (is (true? (get-in state [:search :open?])))
    (is (= [[:dom/focus :search-input]] effects)))
  (testing "close is refused while nothing has been chosen"
    (is (true? (get-in (:state (a/handle ready [:search/close])) [:search :open?]))))
  (testing "close works once a station exists"
    (let [{:keys [state effects]} (a/handle (assoc-in ready [:player :station] jazz) [:search/close])]
      (is (false? (get-in state [:search :open?])))
      (is (= [[:dom/focus :opener]] effects))))
  (let [{:keys [state effects]} (a/handle ready [:search/mode :normal])]
    (is (= :normal (get-in state [:search :mode])))
    (is (= [[:dom/focus :search-list]] effects))))

(deftest moving-the-highlight-stays-in-bounds
  (is (= 1 (get-in (:state (run-all ready [:search/move 1] [:search/move 1] [:search/move 1])) [:search :highlight])))
  (is (= 0 (get-in (:state (a/handle ready [:search/move -1])) [:search :highlight])))
  (is (= 1 (get-in (:state (a/handle ready [:search/edge :last])) [:search :highlight])))
  (is (= 0 (get-in (:state (run-all ready [:search/edge :last] [:search/edge :first])) [:search :highlight])))
  (is (= [[:dom/scroll-highlight]] (:effects (a/handle ready [:search/move 1])))))

(deftest enter-runs-a-pending-search
  (let [{:keys [effects]} (run-all ready [:search/typed "jazz"] [:search/enter])]
    (is (some #(= :api/fetch (first %)) effects))))
