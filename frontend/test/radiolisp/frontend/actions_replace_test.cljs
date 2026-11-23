(ns radiolisp.frontend.actions-replace-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.fixtures :as f :refer [blues jazz]]))

(def gone {:id "zz" :name "Gone FM" :key "7" :color :teal :display :pad :custom-name "Mi radio"})

(def lost
  "Jazz and a favourite Radio Browser no longer knows; the cache remembers its name."
  (-> f/ready
      (assoc-in [:favorites :items] [{:id "j1" :name "Jazz Radio" :key "1"} gone])
      (assoc-in [:favorites :missing] #{"zz"})
      (assoc :cache {"zz" {:name "Gone FM Radio"}})))

(def replacing
  "The palette open to replace «zz», the search for its name answered."
  (-> (:state (a/handle lost [:favorites/find-replacement "zz"]))
      (update :search assoc :status :done :results [jazz blues] :top [jazz blues] :highlight 0)))

(deftest finding-a-replacement-opens-the-palette-on-the-old-name
  (let [menu {:kind :station :id "zz" :anchor "more-zz"}
        {:keys [state effects]} (a/handle (assoc lost :menu menu) [:favorites/find-replacement "zz"])]
    (is (true? (get-in state [:search :open?])))
    (is (= "zz" (get-in state [:search :replacing])))
    (is (= "Gone FM Radio" (get-in state [:search :query])) "the name Radio Browser had")
    (is (nil? (:menu state)))
    (is (some #{[:dom/focus :search-input]} effects))
    (is (not-any? #{[:dom/focus "more-zz"]} effects) "the focus goes to the search, not back to the tile"))
  (testing "without a cached name, the stored one"
    (let [{:keys [state]} (a/handle (assoc lost :cache {}) [:favorites/find-replacement "zz"])]
      (is (= "Gone FM" (get-in state [:search :query])))))
  (testing "only a missing favourite can be replaced"
    (let [{:keys [state effects]} (a/handle lost [:favorites/find-replacement "j1"])]
      (is (false? (get-in state [:search :open?])))
      (is (nil? (get-in state [:search :replacing])))
      (is (= [] effects)))))

(deftest choosing-a-result-replaces-in-place
  (let [{:keys [state effects]} (a/handle replacing [:search/choose 1])
        [kept swapped]          (get-in state [:favorites :items])]
    (is (= "j1" (:id kept)))
    (is (= {:id "b1" :name "Blues FM" :key "7" :color :teal :display :pad :custom-name "Mi radio"} swapped))
    (is (= #{} (get-in state [:favorites :missing])))
    (is (false? (get-in state [:search :open?])))
    (is (nil? (get-in state [:search :replacing])))
    (is (= :stopped (get-in state [:player :status])) "nothing starts playing")
    (is (= [[:toast/replaced {:old "Mi radio" :new "Blues FM"}]] (:toast state)))
    (is (= ["j1" "b1"] (map :id (second (f/find-effect effects :storage/save)))))
    (is (= #{"j1" "b1"} (set (keys (:cache state)))) "the cache drops the old id")
    (is (some #{[:timer/cancel :search]} effects))))

(deftest a-favourite-cannot-be-the-replacement
  (let [{:keys [state effects]} (a/handle replacing [:search/choose 0])]
    (is (= (:favorites replacing) (:favorites state)))
    (is (= "zz" (get-in state [:search :replacing])) "still replacing")
    (is (true? (get-in state [:search :open?])))
    (is (= [] effects))))

(deftest the-heart-does-nothing-while-replacing
  (let [{:keys [state effects]} (a/handle replacing [:search/toggle-favorite 1])]
    (is (= (:favorites replacing) (:favorites state)))
    (is (= [] effects))))

(deftest closing-cancels-the-replacement
  (let [{:keys [state]} (a/handle replacing [:search/close])]
    (is (false? (get-in state [:search :open?])))
    (is (nil? (get-in state [:search :replacing])))
    (is (= "" (get-in state [:search :query])) "the old name is not left in the search")
    (is (= [jazz blues] (get-in state [:search :results])) "back to the top list")
    (is (= (:favorites lost) (:favorites state)))
    (is (> (get-in state [:search :seq]) (get-in replacing [:search :seq])) "a search in flight is dropped"))
  (testing "with no top list cached, the next opening searches again"
    (let [{:keys [state]} (a/handle (assoc-in replacing [:search :top] []) [:search/close])]
      (is (= :idle (get-in state [:search :status]))))))

(deftest opening-the-palette-normally-leaves-replace-mode
  (let [{:keys [state]} (a/handle replacing [:search/open])]
    (is (nil? (get-in state [:search :replacing])))))

(deftest choosing-still-plays-outside-replace-mode
  (let [open (update f/ready :search assoc :open? true :status :done :results [blues] :top [blues])
        {:keys [state]} (a/handle open [:search/choose 0])]
    (is (= "b1" (get-in state [:player :station :id])))))
