(ns radiolisp.frontend.actions-search-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.fixtures :as f :refer [blues hls-fm jazz run-all]]))

(def open
  "The palette open with the top list loaded."
  (update f/ready :search assoc :open? true :status :done :results [jazz blues] :top [jazz blues]))

(deftest query-active-needs-two-characters
  (is (false? (a/query-active? "")))
  (is (false? (a/query-active? " j ")))
  (is (true? (a/query-active? "ja"))))

(deftest opening-the-first-time-loads-the-top-list
  (let [closed (assoc-in f/ready [:search :open?] false)
        {:keys [state effects]} (a/handle closed [:search/open])]
    (is (true? (get-in state [:search :open?])))
    (is (= :insert (get-in state [:search :mode])))
    (is (= [:dom/focus :search-input] (first effects)))
    (is (some #{[:api/fetch {:server "s1" :query nil :seq 1}]} effects)))
  (testing "later it shows what it had"
    (let [{:keys [effects]} (a/handle (assoc-in open [:search :open?] false) [:search/open])]
      (is (= [[:dom/focus :search-input]] effects)))))

(deftest opening-with-a-query-searches-it
  (let [{:keys [state effects]} (a/handle f/ready [:search/open "jazz"])]
    (is (= "jazz" (get-in state [:search :query])))
    (is (some #{[:timer/start :search 300 [:search/run]]} effects))))

(deftest servers-loaded-runs-the-pending-search
  (let [no-servers (assoc f/ready :api {:servers [] :current 0})
        {:keys [state]} (a/handle no-servers [:search/open])
        {:keys [effects]} (a/handle state [:api/servers-loaded ["s1"]])]
    (is (some #(= :api/fetch (first %)) effects))))

(deftest closing
  (let [{:keys [state effects]} (a/handle open [:search/close])]
    (is (false? (get-in state [:search :open?])))
    (is (= [[:timer/cancel :search]] effects))))

(deftest typing-debounces-the-search
  (let [{:keys [state effects]} (a/handle open [:search/typed "jaz"])]
    (is (= "jaz" (get-in state [:search :query])))
    (is (= :loading (get-in state [:search :status])))
    (is (= [[:timer/start :search 300 [:search/run]]] effects))))

(deftest run-fetches-the-trimmed-query
  (let [{:keys [state effects]} (run-all open [:search/typed " jazz "] [:search/run])]
    (is (= 1 (get-in state [:search :seq])))
    (is (some #{[:api/fetch {:server "s1" :query "jazz" :seq 1}]} effects))))

(deftest short-query-goes-back-to-the-cached-top-list
  (let [{:keys [state effects]} (run-all open [:search/typed "jazz"] [:search/run] [:search/typed "j"])]
    (is (= :done (get-in state [:search :status])))
    (is (= [jazz blues] (get-in state [:search :results])))
    (testing "the search in flight is invalidated"
      (is (= 2 (get-in state [:search :seq]))))
    (is (some #{[:timer/cancel :search]} effects))))

(deftest loaded-results-replace-the-list-and-are-remembered
  (let [{:keys [state]} (run-all (assoc open :stations {})
                                 [:search/typed "hls"] [:search/run] [:search/loaded 1 [hls-fm]])]
    (is (= :done (get-in state [:search :status])))
    (is (= [hls-fm] (get-in state [:search :results])))
    (is (= [jazz blues] (get-in state [:search :top])) "a search does not overwrite the top cache")
    (is (= hls-fm (get-in state [:stations "h1"])))))

(deftest stale-responses-are-ignored
  (let [{:keys [state]} (run-all open
                                 [:search/typed "ja"] [:search/run]
                                 [:search/typed "jazz"] [:search/run]
                                 [:search/loaded 1 [blues]])]
    (is (= :loading (get-in state [:search :status])))
    (is (= 2 (get-in state [:search :seq])))))

(deftest failure-retries-once-on-the-next-server
  (let [{:keys [state effects]} (run-all open [:search/typed "jazz"] [:search/run] [:search/failed 1 5000])]
    (is (= 1 (get-in state [:api :current])))
    (is (some #{[:api/fetch {:server "s2" :query "jazz" :seq 1}]} effects)))
  (let [{:keys [state]} (run-all open [:search/typed "jazz"] [:search/run]
                                 [:search/failed 1 5000] [:search/failed 1 6000])]
    (is (= :error (get-in state [:search :status])))
    (is (= [:error/network {:at 6000}] (get-in state [:search :error])))))

(deftest choosing-plays-and-keeps-the-palette-open
  (let [{:keys [state effects]} (a/handle open [:search/choose 1])]
    (is (= blues (get-in state [:player :station])))
    (is (= 1 (get-in state [:search :highlight])))
    (is (true? (get-in state [:search :open?])))
    (is (= [:audio/play "http://blues.example/stream" false] (first effects)))))

(deftest enter-chooses-the-highlight
  (let [{:keys [state]} (run-all open [:search/move 1] [:search/enter])]
    (is (= blues (get-in state [:player :station])))))

(deftest enter-runs-a-pending-search
  (let [{:keys [effects]} (run-all open [:search/typed "jazz"] [:search/enter])]
    (is (some #(= :api/fetch (first %)) effects))))

(deftest toggling-a-favourite-from-the-palette
  (let [state (update-in open [:favorites :items] pop)
        {:keys [state effects]} (a/handle state [:search/toggle-favorite 1])]
    (is (= ["j1" "b1"] (mapv :id (get-in state [:favorites :items]))))
    (is (f/find-effect effects :storage/save)))
  (testing "a favourite asks before it is removed"
    (let [{:keys [state]} (a/handle open [:search/toggle-favorite 0])]
      (is (= {:kind :remove :id "j1"} (:dialog state))))))

(deftest moving-the-highlight-stays-in-bounds
  (is (= 1 (get-in (:state (run-all open [:search/move 1] [:search/move 1])) [:search :highlight])))
  (is (= 0 (get-in (:state (a/handle open [:search/move -1])) [:search :highlight])))
  (is (= 1 (get-in (:state (a/handle open [:search/edge :last])) [:search :highlight])))
  (is (= 0 (get-in (:state (run-all open [:search/edge :last] [:search/edge :first])) [:search :highlight])))
  (is (= [[:dom/scroll-highlight]] (:effects (a/handle open [:search/move 1])))))

(deftest modes
  (let [{:keys [state effects]} (a/handle open [:search/mode :normal])]
    (is (= :normal (get-in state [:search :mode])))
    (is (= [[:dom/focus :search-list]] effects))))
