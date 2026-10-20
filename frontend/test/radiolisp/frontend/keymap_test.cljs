(ns radiolisp.frontend.keymap-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.keymap :as km]))

(def grid
  {:help?     false
   :dialog    nil
   :menu      nil
   :favorites {:items [{:id "j1" :name "Jazz Radio" :key "1"}
                       {:id "b1" :name "Blues FM" :key "x"}]}
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
  (is (= [:player/volume-step -5] (press grid "h")))
  (is (= [:player/volume-step 5] (press grid "l")))
  (is (= [:player/mute] (press grid "m")))
  (is (= [:help/open] (press grid "?")))
  (testing "space on a focused button is left to the browser"
    (is (nil? (press grid " " {:target :button}))))
  (testing "assigned keys play their favourite"
    (is (= [:favorites/play-key "1"] (press grid "1")))
    (is (= [:favorites/play-key "x"] (press grid "x")))
    (is (nil? (press grid "q")))))

(deftest the-filter-input
  (let [in-filter {:target :filter-input}]
    (is (= [:filter/play-first] (press grid "Enter" in-filter)))
    (is (= [:filter/clear] (press grid "Escape" in-filter)))
    (testing "everything else is typed"
      (is (nil? (press grid "a" in-filter)))
      (is (nil? (press grid "1" in-filter)))
      (is (nil? (press grid " " in-filter))))))

(deftest the-rename-field
  (let [renaming {:target :rename-input}]
    (is (= [:rename/commit] (press grid "Enter" renaming)))
    (is (= [:rename/cancel] (press grid "Escape" renaming)))
    (testing "everything else is typed"
      (is (nil? (press grid "a" renaming)))
      (is (nil? (press grid "/" renaming)))
      (is (nil? (press grid " " renaming))))))

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
    (is (= [:player/mute] (press browsing "m")))))

(deftest gg-needs-two-presses-within-half-a-second
  (is (= [:keys/g 10000] (press browsing "g")))
  (is (= [:search/edge :first] (press (assoc-in browsing [:keys :last-g] 9700) "g")))
  (is (= [:keys/g 10000] (press (assoc-in browsing [:keys :last-g] 9000) "g"))))

(deftest the-key-dialog-captures-keys
  (let [dialog (assoc grid :dialog {:kind :key :id "j1" :pending nil})]
    (is (= [:key-dialog/pressed "q"] (press dialog "q")))
    (is (= [:key-dialog/pressed "/"] (press dialog "/")))
    (is (= [:key-dialog/pressed "F2"] (press dialog "F2")))
    (is (nil? (press dialog "Escape")) "the native dialog cancels")
    (is (nil? (press dialog "Tab")))
    (is (nil? (press dialog "Shift")))
    (is (nil? (press dialog "Enter")) "nothing to save yet")
    (is (= [:key-dialog/save] (press (assoc-in dialog [:dialog :pending] "q") "Enter")))))

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
