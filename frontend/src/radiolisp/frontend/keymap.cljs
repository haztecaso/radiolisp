(ns radiolisp.frontend.keymap
  "Keyboard map, vim style. Pure: the state plus a description of the key
  press give the action to dispatch, or nil to let the browser handle it.

  Contexts, from the most specific: an open dialog, an open menu, the
  palette (typing in its input or browsing its list), the filter input, a
  selected tile of the grid, and global keys.")

(def gg-window-ms 500)

(def directions
  {"h" :left "ArrowLeft" :left
   "j" :down "ArrowDown" :down
   "k" :up   "ArrowUp"   :up
   "l" :right "ArrowRight" :right})

(def shifted-directions
  "Shift with a direction moves the selected tile instead of the selection.
  With shift, the letters come uppercase."
  {"H" :left "J" :down "K" :up "L" :right})

(defn- menu-action [menu {k :key}]
  (case k
    "Escape"          (if (:page menu) [:menu/page nil] [:menu/close])
    ("j" "ArrowDown") [:menu/move 1]
    ("k" "ArrowUp")   [:menu/move -1]
    nil))

(defn- grid-action
  "Moving the selection, or the selected tile, around the grid. Arrows and
  hjkl work from anywhere but a text field; with nothing selected yet, they
  select the tile that plays, else the first."
  [{k :key :keys [shift? tile columns]}]
  (let [shifted (or (shifted-directions k) (when shift? (directions k)))]
    (cond
      shifted        (when (and tile (not= :add tile)) [:grid/shift tile shifted columns])
      (directions k) [:grid/move tile (directions k) columns]
      :else          nil)))

(defn- tile-action
  "Keys for the selected tile, before the global ones."
  [{k :key :keys [tile]}]
  (case k
    ;; The tile is a button: the browser clicks it.
    (" " "Enter") :native
    "s"           (when-not (= :add tile) [:grid/options tile])
    nil))

(defn- global-action [{k :key :keys [target tile]}]
  (case k
    "/"       [:filter/focus]
    "a"       [:search/open]
    " "       (when-not (= target :button) [:player/toggle])
    "-"       [:player/volume-step -5]
    ("+" "=") [:player/volume-step 5]
    "i"       [:panel/toggle]
    "r"       [:grid/random tile false]
    "R"       [:grid/random tile true]
    "?"       [:help/open]
    nil))

(defn- filter-action [{k :key}]
  (case k
    "Enter"     [:filter/play-first]
    "Escape"    [:filter/clear]
    "ArrowDown" [:grid/move nil :first nil]
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

(defn- over-palette-action
  "The global keys that still work over the palette: play, pause, volume
  and help."
  [{k :key :as press}]
  (when (#{" " "-" "+" "=" "?"} k)
    (global-action press)))

(defn- grid-or-global [press]
  (let [on-tile (when (= :tile (:target press)) (tile-action press))]
    (cond
      (= :native on-tile) nil
      on-tile             on-tile
      :else               (or (grid-action press) (global-action press)))))

(defn key->action
  "`press` is `{:key :shift? :ctrl? :alt? :meta? :target :now}`, where
  `:target` is one of :search-input, :filter-input, :slider, :tile, :button
  or :other. On a tile, `:tile` is its favourite id (or :add for the tile
  that adds one); `:columns` is how many columns the grid has now."
  [{:keys [help? dialog menu search] :as state} {k :key :keys [ctrl? alt? meta? target] :as press}]
  (let [open? (:open? search)]
    (cond
      (or help? alt? meta?)            nil
      dialog                           nil
      menu                             (when-not ctrl? (menu-action menu press))
      (and open? ctrl? (#{"j" "n"} k)) [:search/move 1]
      (and open? ctrl? (#{"k" "p"} k)) [:search/move -1]
      ctrl?                            nil
      (and (:panel? state) (not open?) (= "Escape" k)) [:panel/close]
      (= target :search-input)         (input-action press)
      (= target :filter-input)         (filter-action press)
      (= target :slider)               nil
      open?                            (or (list-action state press) (over-palette-action press))
      :else                            (grid-or-global press))))
