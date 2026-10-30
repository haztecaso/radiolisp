(ns radiolisp.frontend.actions-grid-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.fixtures :as f :refer [ready]]))

(def seven
  "Seven favourites (a1 … a7) and the add tile: two rows of three, and the
  last row with a7 and the add tile."
  (assoc-in ready [:favorites :items] (mapv (fn [i] {:id (str "a" i) :name (str "A" i)}) (range 1 8))))

(defn- ids [state]
  (mapv :id (get-in state [:favorites :items])))

(defn- focused [{:keys [effects]}]
  (second (f/find-effect effects :dom/focus)))

(defn- move [state from dir]
  (focused (a/handle state [:grid/move from dir 3])))

(deftest the-cells-of-the-grid
  (is (= ["j1" "b1" :add] (a/grid-cells ready)))
  (is (= ["b1"] (a/grid-cells (assoc ready :filter "blu"))) "no add tile under a filter")
  (is (= [] (a/grid-cells (assoc ready :filter "zzz"))))
  (is (= [] (a/grid-cells (assoc-in ready [:favorites :status] :idle))))
  (is (= "tile-j1" (a/tile-dom-id "j1")))
  (is (= "tile-add" (a/tile-dom-id :add))))

(deftest moving-the-selection
  (testing "the four directions"
    (is (= "tile-a4" (move seven "a5" :left)))
    (is (= "tile-a6" (move seven "a5" :right)))
    (is (= "tile-a2" (move seven "a5" :up)))
    (is (= "tile-add" (move seven "a5" :down))))
  (testing "edges stop, without wrapping"
    (is (nil? (move seven "a4" :left)))
    (is (nil? (move seven "a3" :right)))
    (is (nil? (move seven "a2" :up)))
    (is (nil? (move seven "a7" :down))))
  (testing "down over the incomplete last row goes to its last cell"
    (is (= "tile-add" (move seven "a6" :down))))
  (testing "with nothing selected: the tile that plays, else the first"
    (is (= "tile-a1" (move seven nil :down)))
    (is (= "tile-b1" (move (:state (a/handle ready [:favorites/play "b1"])) nil :right))))
  (is (= "tile-a1" (move seven nil :first)) "from the filter")
  (is (nil? (move (assoc ready :filter "zzz") nil :down)) "an empty grid"))

(deftest moving-a-tile
  (let [shift (fn [id dir] (a/handle seven [:grid/shift id dir 3]))]
    (testing "left and right swap it with its neighbour"
      (is (= ["a1" "a2" "a3" "a5" "a4" "a6" "a7"] (ids (:state (shift "a5" :left)))))
      (is (= ["a1" "a2" "a3" "a4" "a6" "a5" "a7"] (ids (:state (shift "a5" :right))))))
    (testing "up and down take the place of the tile above or below"
      (is (= ["a1" "a5" "a2" "a3" "a4" "a6" "a7"] (ids (:state (shift "a5" :up)))))
      (is (= ["a1" "a3" "a4" "a5" "a2" "a6" "a7"] (ids (:state (shift "a2" :down))))))
    (testing "towards the add tile it goes last"
      (is (= ["a1" "a2" "a3" "a4" "a6" "a7" "a5"] (ids (:state (shift "a5" :down))))))
    (testing "it is saved and keeps the selection"
      (let [{:keys [effects] :as result} (shift "a5" :left)]
        (is (f/find-effect effects :storage/save))
        (is (= "tile-a5" (focused result)))))
    (testing "at an edge nothing changes"
      (is (= {:state seven :effects []} (shift "a1" :up))))))

(deftest options-open-the-station-menu
  (let [{:keys [state]} (a/handle ready [:grid/options "j1"])]
    (is (= {:kind :station :id "j1" :anchor "more-j1"} (:menu state)))))

(deftest random-stations
  (testing "r picks among the others"
    (is (= [[:random/dispatch ["b1"] :grid/select]] (:effects (a/handle ready [:grid/random "j1" false])))))
  (testing "R picks among those that can play, but the one playing"
    (let [playing (:state (a/handle ready [:favorites/play "j1"]))]
      (is (= [[:random/dispatch ["b1"] :grid/play]] (:effects (a/handle playing [:grid/random nil true]))))
      (is (= [] (:effects (a/handle (update playing :stations dissoc "b1") [:grid/random nil true])))
          "favourites Radio Browser has not answered for are left out")))
  (testing "the picked one is selected, or played and selected"
    (is (= [[:dom/focus "tile-b1"]] (:effects (a/handle ready [:grid/select "b1"]))))
    (let [{:keys [state] :as result} (a/handle ready [:grid/play "b1"])]
      (is (= "b1" (get-in state [:player :station :id])))
      (is (= "tile-b1" (focused result))))))
