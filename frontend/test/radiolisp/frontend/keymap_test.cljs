(ns radiolisp.frontend.keymap-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.keymap :as km]))

(def station {:id "j1"})

(def insert-mode {:help? false :player {:station nil} :search {:open? true :mode :insert} :keys {:last-g 0}})
(def normal-mode (assoc-in insert-mode [:search :mode] :normal))
(def closed (-> insert-mode (assoc-in [:search :open?] false) (assoc-in [:player :station] station)))

(defn- press
  ([state k] (press state k {}))
  ([state k opts]
   (km/key->action state (merge {:key k :ctrl? false :alt? false :meta? false :target :other :now 10000}
                                opts))))

(deftest typing-in-the-search-input
  (let [in-input {:target :search-input}]
    (is (= [:search/move 1] (press insert-mode "ArrowDown" in-input)))
    (is (= [:search/move -1] (press insert-mode "ArrowUp" in-input)))
    (is (= [:search/enter] (press insert-mode "Enter" in-input)))
    (is (= [:search/mode :normal] (press insert-mode "Escape" in-input)))
    (testing "letters are typed, not interpreted"
      (is (nil? (press insert-mode "j" in-input)))
      (is (nil? (press insert-mode "/" in-input)))
      (is (nil? (press insert-mode " " in-input))))
    (testing "ctrl moves like vim completion"
      (is (= [:search/move 1] (press insert-mode "j" (assoc in-input :ctrl? true))))
      (is (= [:search/move 1] (press insert-mode "n" (assoc in-input :ctrl? true))))
      (is (= [:search/move -1] (press insert-mode "k" (assoc in-input :ctrl? true))))
      (is (= [:search/move -1] (press insert-mode "p" (assoc in-input :ctrl? true)))))))

(deftest browsing-the-list
  (is (= [:search/move 1] (press normal-mode "j")))
  (is (= [:search/move 1] (press normal-mode "ArrowDown")))
  (is (= [:search/move -1] (press normal-mode "k")))
  (is (= [:search/move -1] (press normal-mode "ArrowUp")))
  (is (= [:search/edge :last] (press normal-mode "G")))
  (is (= [:search/choose] (press normal-mode "Enter")))
  (is (= [:search/mode :insert] (press normal-mode "i")))
  (is (= [:search/mode :insert] (press normal-mode "/")))
  (is (= [:search/close] (press normal-mode "Escape"))))

(deftest gg-needs-two-presses-within-half-a-second
  (is (= [:keys/g 10000] (press normal-mode "g")))
  (is (= [:search/edge :first] (press (assoc-in normal-mode [:keys :last-g] 9700) "g")))
  (is (= [:keys/g 10000] (press (assoc-in normal-mode [:keys :last-g] 9000) "g"))))

(deftest global-keys
  (is (= [:search/open] (press closed "/")))
  (is (= [:player/toggle] (press closed " ")))
  (is (= [:player/volume-step -5] (press closed "h")))
  (is (= [:player/volume-step 5] (press closed "l")))
  (is (= [:player/mute] (press closed "m")))
  (is (= [:help/open] (press closed "?")))
  (testing "global keys also work while browsing the list"
    (is (= [:player/toggle] (press normal-mode " ")))
    (is (= [:player/mute] (press normal-mode "m"))))
  (testing "space on a focused button is left to the browser"
    (is (nil? (press closed " " {:target :button}))))
  (testing "escape closes an open search when a station exists"
    (is (= [:search/close] (press (assoc-in closed [:search :open?] true) "Escape" {:target :button})))))

(deftest keys-that-do-nothing
  (is (nil? (press (assoc closed :help? true) "/")) "the help dialog handles its own keys")
  (is (nil? (press closed "h" {:target :slider})) "the slider keeps its own keys")
  (is (nil? (press closed "m" {:meta? true})))
  (is (nil? (press closed "m" {:alt? true})))
  (is (nil? (press closed "x"))))
