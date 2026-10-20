(ns radiolisp.frontend.fixtures
  "Stations and states shared by the action tests."
  (:require [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.store :as store]))

(def jazz {:id "j1" :name "Jazz Radio" :url "http://jazz.example/stream" :hls? false
           :favicon "https://jazz.example/logo.png" :votes 10 :tags ["jazz"]})
(def blues {:id "b1" :name "Blues FM" :url "http://blues.example/stream" :hls? false
            :favicon nil :votes 3 :tags ["blues"]})
(def hls-fm {:id "h1" :name "HLS FM" :url "https://hls.example/live.m3u8" :hls? true
             :favicon nil :votes 0 :tags []})

(def stations {"j1" jazz "b1" blues "h1" hls-fm})

(def ready
  "Servers known, two favourites loaded, the palette closed."
  (-> store/initial-state
      (assoc :api {:servers ["s1" "s2"] :current 0})
      (assoc :stations stations)
      (assoc :favorites {:items    [{:id "j1" :name "Jazz Radio" :key "1"}
                                    {:id "b1" :name "Blues FM" :key nil}]
                         :status   :done
                         :retried? false
                         :missing  #{}})))

(defn run-all
  "Applies `actions` in order and collects every effect."
  [state & actions]
  (reduce (fn [{:keys [state effects]} action]
            (let [r (a/handle state action)]
              {:state (:state r) :effects (into effects (:effects r))}))
          {:state state :effects []}
          actions))

(defn effect-types [effects]
  (mapv first effects))

(defn find-effect [effects effect-type]
  (first (filter #(= effect-type (first %)) effects)))
