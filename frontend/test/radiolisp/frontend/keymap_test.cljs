(ns radiolisp.frontend.keymap-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.keymap :as km]))

(def grid
  {:help?     false
   :dialog    nil
   :menu      nil
   :favorites {:items [{:id "j1" :name "Jazz Radio"}
                       {:id "b1" :name "Blues FM"}]}
   :search    {:open? false :mode :insert}
   :keys      {:last-g 0}})

(def typing (assoc grid :search {:open? true :mode :insert}))
(def browsing (assoc grid :search {:open? true :mode :normal}))

(defn- press
  ([state k] (press state k {}))
  ([state k opts]
   (km/key->action state (merge {:key k :shift? false :ctrl? false :alt? false :meta? false
                                 :target :other :now 10000}
                                opts))))

(deftest global-keys
  (is (= [:filter/focus] (press grid "/")))
  (is (= [:search/open] (press grid "a")))
  (is (= [:player/toggle] (press grid " ")))
  (is (= [:player/volume-step -5] (press grid "-")))
  (is (= [:player/volume-step 5] (press grid "+")))
  (is (= [:player/volume-step 5] (press grid "=")))
  (is (= [:panel/toggle] (press grid "i")))
  (is (= [:grid/random nil false] (press grid "r")))
  (is (= [:grid/random nil true] (press grid "R" {:shift? true})))
  (is (= [:help/open] (press grid "?")))
  (testing "space on a focused button is left to the browser"
    (is (nil? (press grid " " {:target :button}))))
  (testing "no more keys for favourites, nor for mute"
    (is (nil? (press grid "1")))
    (is (nil? (press grid "m")))))

(deftest moving-around-the-grid
  (let [on-j1 {:target :tile :tile "j1" :columns 4}]
    (testing "arrows and hjkl move the selection"
      (is (= [:grid/move "j1" :left 4] (press grid "h" on-j1)))
      (is (= [:grid/move "j1" :down 4] (press grid "j" on-j1)))
      (is (= [:grid/move "j1" :up 4] (press grid "k" on-j1)))
      (is (= [:grid/move "j1" :right 4] (press grid "l" on-j1)))
      (is (= [:grid/move "j1" :left 4] (press grid "ArrowLeft" on-j1)))
      (is (= [:grid/move "j1" :down 4] (press grid "ArrowDown" on-j1)))
      (is (= [:grid/move "j1" :up 4] (press grid "ArrowUp" on-j1)))
      (is (= [:grid/move "j1" :right 4] (press grid "ArrowRight" on-j1))))
    (testing "with nothing selected they start the selection"
      (is (= [:grid/move nil :down 4] (press grid "j" {:columns 4})))
      (is (= [:grid/move nil :right 4] (press grid "ArrowRight" {:target :button :columns 4}))))
    (testing "shift moves the tile"
      (is (= [:grid/shift "j1" :left 4] (press grid "H" (assoc on-j1 :shift? true))))
      (is (= [:grid/shift "j1" :down 4] (press grid "J" (assoc on-j1 :shift? true))))
      (is (= [:grid/shift "j1" :right 4] (press grid "ArrowRight" (assoc on-j1 :shift? true))))
      (is (nil? (press grid "L" {:shift? true})) "nothing selected, nothing to move")
      (is (nil? (press grid "H" {:target :tile :tile :add :shift? true})) "the add tile stays last"))
    (testing "enter and space click the tile"
      (is (nil? (press grid "Enter" on-j1)))
      (is (nil? (press grid " " on-j1))))
    (testing "s opens its options"
      (is (= [:grid/options "j1"] (press grid "s" on-j1)))
      (is (nil? (press grid "s" {:target :tile :tile :add}))))
    (testing "r picks another one at random"
      (is (= [:grid/random "j1" false] (press grid "r" on-j1))))
    (testing "global keys still work on a tile"
      (is (= [:search/open] (press grid "a" on-j1))))))

(deftest the-filter-input
  (let [in-filter {:target :filter-input}]
    (is (= [:filter/play-first] (press grid "Enter" in-filter)))
    (is (= [:filter/clear] (press grid "Escape" in-filter)))
    (is (= [:grid/move nil :first nil] (press grid "ArrowDown" in-filter)))
    (testing "everything else is typed"
      (is (nil? (press grid "a" in-filter)))
      (is (nil? (press grid "1" in-filter)))
      (is (nil? (press grid " " in-filter))))))

(deftest typing-in-the-palette
  (let [in-input {:target :search-input}]
    (is (= [:search/move 1] (press typing "ArrowDown" in-input)))
    (is (= [:search/move -1] (press typing "ArrowUp" in-input)))
    (is (= [:search/enter] (press typing "Enter" in-input)))
    (is (= [:search/toggle-favorite] (press typing "Enter" (assoc in-input :shift? true))))
    (is (= [:search/mode :normal] (press typing "Escape" in-input)))
    (testing "letters are typed, not interpreted"
      (is (nil? (press typing "j" in-input)))
      (is (nil? (press typing "1" in-input)))
      (is (nil? (press typing " " in-input))))
    (testing "ctrl moves like vim completion"
      (is (= [:search/move 1] (press typing "j" (assoc in-input :ctrl? true))))
      (is (= [:search/move 1] (press typing "n" (assoc in-input :ctrl? true))))
      (is (= [:search/move -1] (press typing "k" (assoc in-input :ctrl? true))))
      (is (= [:search/move -1] (press typing "p" (assoc in-input :ctrl? true)))))))

(deftest browsing-the-palette
  (is (= [:search/move 1] (press browsing "j")))
  (is (= [:search/move 1] (press browsing "ArrowDown")))
  (is (= [:search/move -1] (press browsing "k")))
  (is (= [:search/edge :last] (press browsing "G")))
  (is (= [:search/choose] (press browsing "Enter")))
  (is (= [:search/toggle-favorite] (press browsing "f")))
  (is (= [:search/mode :insert] (press browsing "i")))
  (is (= [:search/mode :insert] (press browsing "/")))
  (is (= [:search/close] (press browsing "Escape")))
  (testing "player keys still work"
    (is (= [:player/toggle] (press browsing " ")))
    (is (= [:player/volume-step 5] (press browsing "+"))))
  (testing "but not the grid"
    (is (nil? (press browsing "h")))
    (is (nil? (press browsing "r")))))

(deftest gg-needs-two-presses-within-half-a-second
  (is (= [:keys/g 10000] (press browsing "g")))
  (is (= [:search/edge :first] (press (assoc-in browsing [:keys :last-g] 9700) "g")))
  (is (= [:keys/g 10000] (press (assoc-in browsing [:keys :last-g] 9000) "g"))))

(deftest other-dialogs-and-menus
  (is (nil? (press (assoc grid :dialog {:kind :remove :id "j1"}) "1")))
  (let [menu (assoc grid :menu {:kind :list :anchor "list-menu"})]
    (is (= [:menu/close] (press menu "Escape")))
    (is (= [:menu/move 1] (press menu "ArrowDown")))
    (is (= [:menu/move -1] (press menu "k")))
    (is (nil? (press menu "1"))))
  (testing "esc on the language page goes back to the list menu"
    (is (= [:menu/page nil] (press (assoc grid :menu {:kind :list :anchor "list-menu" :page :language}) "Escape")))))

(deftest keys-that-do-nothing
  (is (nil? (press (assoc grid :help? true) "/")) "the help dialog handles its own keys")
  (is (nil? (press grid "h" {:target :slider})) "the slider keeps its own keys")
  (is (nil? (press grid "m" {:meta? true})))
  (is (nil? (press grid "m" {:alt? true})))
  (is (nil? (press grid "1" {:ctrl? true}))))

(deftest the-customize-dialog-takes-every-key
  (let [customizing (assoc grid :dialog {:kind :customize :id "j1"})]
    (is (nil? (press customizing "a" {:target :other})))
    (is (nil? (press customizing "/" {:target :other})))
    (is (nil? (press customizing " " {:target :other})))))

(deftest esc-closes-the-panel
  (is (= [:panel/close] (press (assoc grid :panel? true) "Escape" {:target :other})))
  (is (not= [:panel/close] (press (assoc grid :panel? true :search {:open? true}) "Escape" {:target :other}))
      "the palette goes first"))
