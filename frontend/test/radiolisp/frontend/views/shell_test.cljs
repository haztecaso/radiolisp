(ns radiolisp.frontend.views.shell-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.fixtures :refer [jazz ready]]
            [radiolisp.frontend.views.shell :as shell]))

(deftest the-tab-title-names-the-station-on-air
  (let [on-air (fn [status] (assoc ready :locale :es :player {:station jazz :status status}))]
    (is (= "radiolisp" (shell/page-title (assoc ready :locale :es))) "nothing plays")
    (is (= "Jazz Radio - radiolisp" (shell/page-title (on-air :playing))))
    (testing "also while it connects or reconnects, so a cut does not flicker"
      (is (= "Jazz Radio - radiolisp" (shell/page-title (on-air :connecting))))
      (is (= "Jazz Radio - radiolisp" (shell/page-title (on-air :reconnecting)))))
    (is (= "radiolisp" (shell/page-title (on-air :paused))))
    (is (= "radiolisp" (shell/page-title (on-air :error))))
    (is (= "Jazz - radiolisp"
           (shell/page-title (assoc-in (on-air :playing) [:favorites :items 0 :custom-name] "Jazz")))
        "the name the grid shows")))
