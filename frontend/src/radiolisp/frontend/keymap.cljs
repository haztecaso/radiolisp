(ns radiolisp.frontend.keymap
  "Keyboard map, vim style. Pure: the state plus a description of the key
  press give the action to dispatch, or nil to let the browser handle it.

  Modes: insert (typing in the search input) and normal (browsing the list).
  Global keys work outside the input.")

(def gg-window-ms 500)

(defn- global-action [{:keys [search player]} {k :key :keys [target]}]
  (let [open? (:open? search)]
    (case k
      "/"      (if open? [:search/mode :insert] [:search/open])
      " "      (when-not (= target :button) [:player/toggle])
      "h"      [:player/volume-step -5]
      "l"      [:player/volume-step 5]
      "m"      [:player/mute]
      "?"      [:help/open]
      "Escape" (when (and open? (:station player)) [:search/close])
      nil)))

(defn- input-action [{k :key}]
  (case k
    "ArrowDown" [:search/move 1]
    "ArrowUp"   [:search/move -1]
    "Enter"     [:search/enter]
    "Escape"    [:search/mode :normal]
    nil))

(defn- list-action [state {k :key :keys [now]}]
  (case k
    ("j" "ArrowDown") [:search/move 1]
    ("k" "ArrowUp")   [:search/move -1]
    "G"               [:search/edge :last]
    "g"               (if (< (- now (get-in state [:keys :last-g])) gg-window-ms)
                        [:search/edge :first]
                        [:keys/g now])
    "Enter"           [:search/choose]
    ("i" "/")         [:search/mode :insert]
    "Escape"          [:search/close]
    nil))

(defn key->action
  "`press` is `{:key :ctrl? :alt? :meta? :target :now}`, where `:target` is
  one of :search-input, :slider, :button or :other."
  [{:keys [help? search] :as state} {k :key :keys [ctrl? alt? meta? target] :as press}]
  (let [open?    (:open? search)
        browsing (and open? (= :normal (:mode search)))]
    (cond
      (or help? alt? meta?)              nil
      (and open? ctrl? (#{"j" "n"} k))   [:search/move 1]
      (and open? ctrl? (#{"k" "p"} k))   [:search/move -1]
      ctrl?                              nil
      (= target :search-input)           (input-action press)
      (= target :slider)                 nil
      browsing                           (or (list-action state press) (global-action state press))
      :else                              (global-action state press))))
