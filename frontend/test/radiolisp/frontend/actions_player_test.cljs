(ns radiolisp.frontend.actions-player-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.store :as store]))

(def jazz {:id "j1" :name "Jazz Radio" :url "http://jazz.example/stream" :hls? false})
(def blues {:id "b1" :name "Blues FM" :url "http://blues.example/stream" :hls? false})
(def hls-fm {:id "h1" :name "HLS FM" :url "https://hls.example/live.m3u8" :hls? true})

(def searching
  (-> store/initial-state
      (assoc :api {:servers ["s1"] :current 0})
      (update :search assoc :status :done :results [jazz blues hls-fm] :top [jazz blues hls-fm])))

(defn- run-all [state & actions]
  (reduce (fn [{:keys [state effects]} action]
            (let [r (a/handle state action)]
              {:state (:state r) :effects (into effects (:effects r))}))
          {:state state :effects []}
          actions))

(def playing-jazz
  (:state (run-all searching [:search/choose 0] [:audio/playing 1000])))

(deftest choosing-a-station-plays-it-and-closes-search
  (let [{:keys [state effects]} (a/handle searching [:search/choose 1])]
    (is (= blues (get-in state [:player :station])))
    (is (= :connecting (get-in state [:player :status])))
    (is (false? (get-in state [:search :open?])))
    (is (= [[:audio/play "http://blues.example/stream"]
            [:timer/start :connect 10000 [:audio/timeout]]
            [:dom/focus :toggle]]
           effects))))

(deftest choose-without-index-uses-the-highlight
  (let [{:keys [state]} (run-all searching [:search/move 1] [:search/choose])]
    (is (= blues (get-in state [:player :station])))))

(deftest choosing-the-station-that-plays-only-closes-search
  (let [state (assoc-in playing-jazz [:search :open?] true)
        {:keys [state effects]} (a/handle state [:search/choose 0])]
    (is (= :playing (get-in state [:player :status])))
    (is (false? (get-in state [:search :open?])))
    (is (= [[:dom/focus :toggle]] effects))))

(deftest hls-stations-are-refused
  (let [{:keys [state effects]} (a/handle searching [:search/choose 2])]
    (is (= :error (get-in state [:player :status])))
    (is (= [:error/unsupported {}] (get-in state [:player :error])))
    (is (some #{[:audio/stop]} effects))))

(deftest enter-chooses-when-results-are-shown
  (let [{:keys [state]} (a/handle searching [:search/enter])]
    (is (= jazz (get-in state [:player :station])))))

(deftest playing-event-starts-the-clock-once
  (is (= :playing (get-in playing-jazz [:player :status])))
  (is (= 1000 (get-in playing-jazz [:player :started-at])))
  (is (= 1000 (:now playing-jazz)))
  (let [{:keys [state]} (run-all playing-jazz [:player/toggle] [:player/toggle] [:audio/playing 9000])]
    (is (= 1000 (get-in state [:player :started-at])) "resuming keeps the clock")))

(deftest toggle-pauses-and-resumes
  (let [{:keys [state effects]} (a/handle playing-jazz [:player/toggle])]
    (is (= :paused (get-in state [:player :status])))
    (is (= [[:audio/pause] [:timer/cancel :connect]] effects))
    (let [{:keys [state effects]} (a/handle state [:player/toggle])]
      (is (= :connecting (get-in state [:player :status])))
      (is (= [:audio/play "http://jazz.example/stream"] (first effects))))))

(deftest toggle-does-nothing-without-a-station
  (is (= {:state searching :effects []} (a/handle searching [:player/toggle]))))

(deftest volume-is-clamped-and-unmutes
  (let [{:keys [state effects]} (a/handle (assoc-in searching [:player :muted?] true) [:player/volume 140])]
    (is (= 100 (get-in state [:player :volume])))
    (is (false? (get-in state [:player :muted?])))
    (is (= [[:audio/volume 1]] effects)))
  (is (= 0 (get-in (:state (a/handle searching [:player/volume -3])) [:player :volume])))
  (is (= 65 (get-in (:state (a/handle searching [:player/volume-step -5])) [:player :volume]))))

(deftest mute-toggles-the-audio-volume
  (let [{:keys [state effects]} (a/handle searching [:player/mute])]
    (is (true? (get-in state [:player :muted?])))
    (is (= [[:audio/volume 0]] effects))
    (is (= [[:audio/volume 0.7]] (:effects (a/handle state [:player/mute]))))))

(deftest audio-events
  (testing "an outside pause (media keys) is reflected"
    (is (= :paused (get-in (:state (a/handle playing-jazz [:audio/paused])) [:player :status]))))
  (testing "buffering goes back to connecting and arms the timeout"
    (let [{:keys [state effects]} (a/handle playing-jazz [:audio/waiting])]
      (is (= :connecting (get-in state [:player :status])))
      (is (= [[:timer/start :connect 10000 [:audio/timeout]]] effects))))
  (testing "a stream error is shown"
    (let [state (:state (a/handle playing-jazz [:audio/failed]))]
      (is (= :error (get-in state [:player :status])))
      (is (= [:error/stream {}] (get-in state [:player :error])))))
  (testing "the timeout only fires while connecting"
    (is (= :playing (get-in (:state (a/handle playing-jazz [:audio/timeout])) [:player :status])))
    (let [connecting (:state (a/handle searching [:search/choose 0]))]
      (is (= :error (get-in (:state (a/handle connecting [:audio/timeout])) [:player :status])))))
  (testing "errors never overwrite an unsupported station"
    (let [unsupported (:state (a/handle searching [:search/choose 2]))]
      (is (= [:error/unsupported {}] (get-in (:state (a/handle unsupported [:audio/failed])) [:player :error]))))))

(deftest misc-actions
  (is (= 42 (:now (:state (a/handle searching [:clock/tick 42])))))
  (let [{:keys [state effects]} (a/handle searching [:i18n/set :es])]
    (is (= :es (:locale state)))
    (is (= [[:dom/lang :es]] effects)))
  (let [{:keys [state effects]} (a/handle searching [:help/open])]
    (is (true? (:help? state)))
    (is (= [[:dom/show-help]] effects))
    (is (false? (:help? (:state (a/handle state [:help/close])))))))
