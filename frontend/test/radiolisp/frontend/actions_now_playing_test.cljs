(ns radiolisp.frontend.actions-now-playing-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.fixtures :as f :refer [hls-fm ready run-all]]))

(def playing-jazz
  (:state (run-all ready [:favorites/play "j1"] [:audio/playing])))

(def titled-jazz
  (:state (a/handle playing-jazz [:now-playing/title {:id "j1" :title "Artist - Song"}])))

(deftest follows-titles-once-the-station-plays
  (let [connecting (:state (a/handle ready [:favorites/play "j1"]))]
    (is (nil? (get-in connecting [:player :now-playing])) "not while connecting")
    (let [{:keys [state effects]} (a/handle connecting [:audio/playing])]
      (is (= {:id "j1" :title nil} (get-in state [:player :now-playing])))
      (is (some #{[:socket/subscribe "j1"]} effects))
      (testing "playing again after buffering does not subscribe twice"
        (is (nil? (f/find-effect (:effects (a/handle state [:audio/playing])) :socket/subscribe)))))))

(deftest hls-has-no-titles
  (let [{:keys [state effects]} (run-all (assoc-in ready [:stations "h1"] hls-fm)
                                         [:favorites/play "h1"] [:audio/playing])]
    (is (nil? (get-in state [:player :now-playing])))
    (is (nil? (f/find-effect effects :socket/subscribe)))))

(deftest a-title-is-shown-and-sent-to-the-media-session
  (is (= "Artist - Song" (get-in titled-jazz [:player :now-playing :title])))
  (is (= [[:media/update {:title "Artist - Song" :artist "Jazz Radio"
                          :artwork "https://jazz.example/logo.png"}]]
         (:effects (a/handle playing-jazz [:now-playing/title {:id "j1" :title "Artist - Song"}]))))
  (testing "the same title again changes nothing"
    (is (= [] (:effects (a/handle titled-jazz [:now-playing/title {:id "j1" :title "Artist - Song"}])))))
  (testing "no title now: back to the station name"
    (let [{:keys [state effects]} (a/handle titled-jazz [:now-playing/title {:id "j1" :title nil}])]
      (is (nil? (get-in state [:player :now-playing :title])))
      (is (= [[:media/update {:title "Jazz Radio" :artist "radiolisp"
                              :artwork "https://jazz.example/logo.png"}]]
             effects)))))

(deftest late-titles-are-ignored
  (is (= {:state playing-jazz :effects []}
         (a/handle playing-jazz [:now-playing/title {:id "b1" :title "Late"}])))
  (is (= {:state ready :effects []}
         (a/handle ready [:now-playing/title {:id "j1" :title "Late"}]))))

(deftest unavailable-is-not-an-error
  (is (= {:state playing-jazz :effects []}
         (a/handle playing-jazz [:now-playing/unavailable {:id "j1" :reason :no-metadata}])))
  (is (= {:state playing-jazz :effects []}
         (a/handle playing-jazz [:now-playing/rejected {:reason :malformed}]))))

(deftest stops-following-when-the-station-stops
  (testing "pause"
    (let [{:keys [state effects]} (a/handle titled-jazz [:player/toggle])]
      (is (nil? (get-in state [:player :now-playing])))
      (is (some #{[:socket/unsubscribe]} effects))
      (is (some #{[:media/update {:title "Jazz Radio" :artist "radiolisp"
                                  :artwork "https://jazz.example/logo.png"}]}
                effects)
          "the media session forgets the song")))
  (testing "outside pause"
    (is (nil? (get-in (:state (a/handle titled-jazz [:audio/paused])) [:player :now-playing]))))
  (testing "a cut keeps following while it reconnects"
    (is (= {:id "j1" :title "Artist - Song"}
           (get-in (:state (a/handle titled-jazz [:audio/failed nil])) [:player :now-playing]))))
  (testing "failure, once the reconnections are spent"
    (let [{:keys [state effects]} (a/handle (assoc-in titled-jazz [:player :retries] (count a/reconnect-delays-ms))
                                            [:audio/failed nil])]
      (is (nil? (get-in state [:player :now-playing])))
      (is (some #{[:socket/unsubscribe]} effects))))
  (testing "another station"
    (let [{:keys [state effects]} (a/handle titled-jazz [:favorites/play "b1"])]
      (is (nil? (get-in state [:player :now-playing])))
      (is (some #{[:socket/unsubscribe]} effects))
      (is (= [[:media/update {:title "Blues FM" :artist "radiolisp" :artwork nil}]]
             (filterv #(= :media/update (first %)) effects))
          "only the new station's entry"))))

(deftest proxy-failures
  (let [connecting (:state (a/handle ready [:favorites/play "j1"]))
        error-for  (fn [status]
                     (get-in (:state (a/handle connecting [:audio/failed status {:proxy? true}]))
                             [:player :error]))]
    (testing "no backend: the station stays http-only"
      (is (= [:error/http-only {}] (error-for nil)))
      (is (= [:error/http-only {}] (error-for 404)))
      (is (= [:error/http-only {}] (error-for 405))))
    (testing "the backend is full"
      (is (= [:error/busy {}] (error-for 429)))
      (is (= [:error/busy {}] (error-for 503))))
    (testing "the station did not answer the backend"
      (is (= [:error/stream {}] (error-for 502))))))
