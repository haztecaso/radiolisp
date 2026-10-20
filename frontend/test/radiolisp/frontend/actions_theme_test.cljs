(ns radiolisp.frontend.actions-theme-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.fixtures :refer [ready run-all]]))

(deftest the-button-switches-and-saves-the-theme
  (let [{:keys [state effects]} (a/handle ready [:theme/toggle])]
    (is (= {:current :dark :chosen? true} (:theme state)))
    (is (= [[:dom/theme :dark] [:theme/save :dark]] effects))
    (testing "and back"
      (let [{:keys [state effects]} (a/handle state [:theme/toggle])]
        (is (= :light (get-in state [:theme :current])))
        (is (= [[:dom/theme :light] [:theme/save :light]] effects))))))

(deftest the-browser-preference-applies-until-a-theme-is-chosen
  (let [{:keys [state effects]} (a/handle ready [:theme/system-changed :dark])]
    (is (= :dark (get-in state [:theme :current])))
    (is (= [[:dom/theme :dark]] effects)))
  (let [chosen                  (:state (run-all ready [:theme/toggle]))
        {:keys [state effects]} (a/handle chosen [:theme/system-changed :light])]
    (is (= :dark (get-in state [:theme :current])))
    (is (= [] effects))))
