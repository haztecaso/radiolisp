(ns radiolisp.frontend.actions-player-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.fixtures :as f :refer [blues hls-fm jazz ready run-all]]))

(def playing-jazz
  (:state (run-all ready [:favorites/play "j1"] [:audio/playing])))

(deftest playing-a-favourite
  (let [{:keys [state effects]} (a/handle ready [:favorites/play "b1"])]
    (is (= blues (get-in state [:player :station])))
    (is (= :connecting (get-in state [:player :status])))
    (is (= [[:audio/play "http://blues.example/stream" false "b1"]
            [:timer/start :connect a/connect-timeout-ms [:audio/timeout]]
            [:api/click {:server "s1" :id "b1"}]
            [:media/update {:title "Blues FM" :artist "radiolisp" :artwork nil}]]
           effects))))

(deftest playing-the-station-that-plays-pauses-it
  (let [{:keys [state effects]} (a/handle playing-jazz [:favorites/play "j1"])]
    (is (= :paused (get-in state [:player :status])))
    (is (= [[:audio/pause] [:timer/cancel :connect] [:socket/unsubscribe]] effects))
    (testing "and again resumes it without counting another click"
      (let [{:keys [effects]} (a/handle state [:favorites/play "j1"])]
        (is (= [:audio/play "http://jazz.example/stream" false "j1"] (first effects)))
        (is (nil? (f/find-effect effects :api/click)))))))

(deftest unknown-favourites-do-nothing
  (is (= {:state ready :effects []} (a/handle ready [:favorites/play "zz"]))))

(deftest hls-goes-to-the-audio-layer
  (let [{:keys [effects]} (a/handle (assoc-in ready [:stations "h1"] hls-fm)
                                    [:favorites/play "h1"])]
    (is (= [:audio/play "https://hls.example/live.m3u8" true "h1"] (first effects)))))

(deftest assigned-keys-play-their-favourite
  (is (= jazz (get-in (:state (a/handle ready [:favorites/play-key "1"])) [:player :station])))
  (is (= ready (:state (a/handle ready [:favorites/play-key "9"])))))

(deftest next-and-previous-cycle-through-the-favourites
  (is (= blues (get-in (:state (a/handle playing-jazz [:favorites/play-next 1])) [:player :station])))
  (is (= blues (get-in (:state (a/handle playing-jazz [:favorites/play-next -1])) [:player :station])))
  (testing "with nothing playing it starts with the first"
    (is (= jazz (get-in (:state (a/handle ready [:favorites/play-next 1])) [:player :station])))))

(deftest toggle-pauses-and-resumes
  (let [{:keys [state effects]} (a/handle playing-jazz [:player/toggle])]
    (is (= :paused (get-in state [:player :status])))
    (is (= [[:audio/pause] [:timer/cancel :connect] [:socket/unsubscribe]] effects))
    (let [{:keys [state effects]} (a/handle state [:player/toggle])]
      (is (= :connecting (get-in state [:player :status])))
      (is (= [:audio/play "http://jazz.example/stream" false "j1"] (first effects))))))

(deftest toggle-does-nothing-without-a-station
  (is (= {:state ready :effects []} (a/handle ready [:player/toggle]))))

(deftest volume-is-clamped-and-unmutes
  (let [{:keys [state effects]} (a/handle (assoc-in ready [:player :muted?] true) [:player/volume 140])]
    (is (= 100 (get-in state [:player :volume])))
    (is (false? (get-in state [:player :muted?])))
    (is (= [[:audio/volume 1]] effects)))
  (is (= 0 (get-in (:state (a/handle ready [:player/volume -3])) [:player :volume])))
  (is (= 95 (get-in (:state (a/handle ready [:player/volume-step -5])) [:player :volume]))))

(deftest mute-toggles-the-audio-volume
  (let [{:keys [state effects]} (a/handle ready [:player/mute])]
    (is (true? (get-in state [:player :muted?])))
    (is (= [[:audio/volume 0]] effects))
    (is (= [[:audio/volume 1]] (:effects (a/handle state [:player/mute]))))))

(deftest audio-events
  (testing "an outside pause (media keys) is reflected"
    (is (= :paused (get-in (:state (a/handle playing-jazz [:audio/paused])) [:player :status]))))
  (testing "buffering goes back to connecting and arms the timeout"
    (let [{:keys [state effects]} (a/handle playing-jazz [:audio/waiting])]
      (is (= :connecting (get-in state [:player :status])))
      (is (= [[:timer/start :connect a/connect-timeout-ms [:audio/timeout]]] effects))))
  (testing "a stream error is shown"
    (let [state (:state (a/handle playing-jazz [:audio/failed]))]
      (is (= :error (get-in state [:player :status])))
      (is (= [:error/stream {}] (get-in state [:player :error])))))
  (testing "the HTTP status of the failed stream picks the message"
    (is (= [:error/blocked {}]
           (get-in (:state (a/handle playing-jazz [:audio/failed 451])) [:player :error])))
    (is (= [:error/refused {:status 403}]
           (get-in (:state (a/handle playing-jazz [:audio/failed 403])) [:player :error])))
    (is (= [:error/stream {}]
           (get-in (:state (a/handle playing-jazz [:audio/failed 200])) [:player :error]))
        "a stream that answers fine but does not play is not a refusal"))
  (testing "a stream that only exists over http, on an https page"
    (let [connecting (:state (a/handle ready [:favorites/play "j1"]))
          {:keys [state effects]} (a/handle connecting [:audio/http-only])]
      (is (= [:error/http-only {}] (get-in state [:player :error])))
      (is (some #{[:audio/stop]} effects)))
    (is (= :paused (get-in (:state (a/handle (assoc-in playing-jazz [:player :status] :paused)
                                             [:audio/http-only]))
                           [:player :status]))
        "ignored once the player is not trying to play"))
  (testing "a format the browser cannot play"
    (let [connecting (:state (a/handle ready [:favorites/play "j1"]))
          {:keys [state effects]} (a/handle connecting [:audio/unsupported])]
      (is (= [:error/unsupported {}] (get-in state [:player :error])))
      (is (some #{[:audio/stop]} effects))
      (is (= [:error/unsupported {}]
             (get-in (:state (a/handle state [:audio/failed])) [:player :error]))
          "later errors do not overwrite it")))
  (testing "the timeout only fires while connecting"
    (is (= :playing (get-in (:state (a/handle playing-jazz [:audio/timeout])) [:player :status])))
    (let [connecting (:state (a/handle ready [:favorites/play "j1"]))]
      (is (= :error (get-in (:state (a/handle connecting [:audio/timeout])) [:player :status]))))))

(deftest misc-actions
  (let [{:keys [state effects]} (a/handle ready [:i18n/set :es])]
    (is (= :es (:locale state)))
    (is (= [[:dom/lang :es]] effects)))
  (testing "the language picker sends the locale as a string"
    (is (= :eu (:locale (:state (a/handle ready [:i18n/set "eu"]))))))
  (let [{:keys [state effects]} (a/handle ready [:help/open])]
    (is (true? (:help? state)))
    (is (= [[:dom/show-dialog "help"]] effects))
    (is (false? (:help? (:state (a/handle state [:help/close])))))))
