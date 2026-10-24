(ns radiolisp.frontend.actions-install-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.fixtures :refer [ready run-all]]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.menu :as menu]))

(defn- menu-text [state]
  (pr-str (menu/view (assoc state :menu {:kind :list}) (partial i18n/t :en))))

(deftest the-menu-offers-to-install-only-while-the-browser-does
  (is (not (:installable? ready)))
  (is (not (re-find #"Install the app" (menu-text ready))))
  (let [offered (:state (run-all ready [:install/available true]))]
    (is (re-find #"Install the app" (menu-text offered)))
    (testing "installed elsewhere (the browser's own button)"
      (is (not (:installable? (:state (a/handle offered [:install/available false]))))))))

(deftest installing-uses-the-offer-once
  (let [offered                 (:state (run-all ready [:install/available true]))
        {:keys [state effects]} (a/handle offered [:install/prompt])]
    (is (= [[:install/prompt]] effects))
    (is (not (:installable? state)))))
