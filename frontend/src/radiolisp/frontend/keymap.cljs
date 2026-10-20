(ns radiolisp.frontend.keymap
  "Keyboard map, vim style. Pure: the state plus a description of the key
  press give the action to dispatch, or nil to let the browser handle it.

  Contexts, from the most specific: an open dialog, an open menu, the
  palette (typing in its input or browsing its list), the filter input, and
  global keys. Keys assigned to favourites come after the reserved ones."
  (:require [radiolisp.common.favorites :as favs]))

(def gg-window-ms 500)

(def modifier-keys
  #{"Shift" "Control" "Alt" "AltGraph" "Meta" "CapsLock" "Dead" "Fn" "OS"})

(defn- key-dialog-action [{:keys [dialog]} {k :key}]
  (cond
    (#{"Escape" "Tab"} k) nil
    (modifier-keys k)     nil
    (= "Enter" k)         (when (:pending dialog) [:key-dialog/save])
    :else                 [:key-dialog/pressed k]))

(defn- menu-action [menu {k :key}]
  (case k
    "Escape"          (if (:page menu) [:menu/page nil] [:menu/close])
    ("j" "ArrowDown") [:menu/move 1]
    ("k" "ArrowUp")   [:menu/move -1]
    nil))

(defn- global-action [{:keys [favorites]} {k :key :keys [target]}]
  (case k
    "/" [:filter/focus]
    "a" [:search/open]
    " " (when-not (= target :button) [:player/toggle])
    "h" [:player/volume-step -5]
    "l" [:player/volume-step 5]
    "m" [:player/mute]
    "?" [:help/open]
    (when (favs/key-owner (:items favorites) k)
      [:favorites/play-key k])))

(defn- filter-action [{k :key}]
  (case k
    "Enter"  [:filter/play-first]
    "Escape" [:filter/clear]
    nil))

(defn- input-action [{k :key :keys [shift?]}]
  (case k
    "ArrowDown" [:search/move 1]
    "ArrowUp"   [:search/move -1]
    "Enter"     (if shift? [:search/toggle-favorite] [:search/enter])
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
    "f"               [:search/toggle-favorite]
    ("i" "/")         [:search/mode :insert]
    "Escape"          [:search/close]
    "a"               nil
    nil))

(defn key->action
  "`press` is `{:key :shift? :ctrl? :alt? :meta? :target :now}`, where
  `:target` is one of :search-input, :filter-input, :slider, :button or
  :other."
  [{:keys [help? dialog menu search] :as state} {k :key :keys [ctrl? alt? meta? target] :as press}]
  (let [open? (:open? search)]
    (cond
      (or help? alt? meta?)            nil
      (= :key (:kind dialog))          (when-not ctrl? (key-dialog-action state press))
      dialog                           nil
      menu                             (when-not ctrl? (menu-action menu press))
      (and open? ctrl? (#{"j" "n"} k)) [:search/move 1]
      (and open? ctrl? (#{"k" "p"} k)) [:search/move -1]
      ctrl?                            nil
      (= target :search-input)         (input-action press)
      (= target :filter-input)         (filter-action press)
      (= target :slider)               nil
      open?                            (or (list-action state press) (global-action state press))
      :else                            (global-action state press))))
