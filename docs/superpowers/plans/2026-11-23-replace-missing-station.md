# Replace a missing station Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `docs/specs/2026-11-23-replace-missing-station-design.md`: a
«Find replacement» action on favourites Radio Browser no longer knows, which swaps
a chosen station in place (position, key, colour, look and custom name kept).

**Architecture:** Same flow as everywhere: action → pure `actions/handle` → effects.
The swap is a pure function in `common/favorites.cljc`. The search palette gains a
`:replacing` mode (`[:search :replacing]` holds the old id) that changes what a
click/Enter on a result does. Views only read that state.

**Tech Stack:** ClojureScript, shadow-cljs, Replicant, SCSS; `bb test`, `bb lint`,
`bb fmt:check`, `bb e2e`.

Approved by the user on 2026-11-23 (the user asked for the spec and this plan to
be approved and put in a feature branch). Branch: `feat/replace-missing-station`.

Rules from `AGENTS.md` that bite here:
- Never pipe test output into `head`/`tail`/`grep`: redirect to a file, then read it.
- Edit only `es.edn` and `en.edn` for UI strings (the other locales are translated
  later, only when the user asks).
- `actions/handle` stays pure; no side effects in views.
- Commit messages are conventional (`feat(...)`, `docs(...)`), never on `main`, and end with
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

---

### Task 1: `favs/replace` (pure, shared)

**Files:**
- Modify: `common/src/radiolisp/common/favorites.cljc` (ns `:refer-clojure :exclude`; new fn after `remove`, around line 126)
- Test: `common/test/radiolisp/common/favorites_test.cljc` (after `remove-drops-the-entry`, around line 55)

- [ ] **Step 1: Write the failing test**

Add after `remove-drops-the-entry`:

```clojure
(deftest replace-swaps-the-station-in-place
  (let [dressed [{:id "j1" :name "Jazz Radio" :key "1"}
                 {:id "b1" :name "Blues FM" :key "7" :color :teal :display :pad :custom-name "Mine"}
                 {:id "f1" :name "France Inter" :key nil}]
        swapped (favs/replace dressed "b1" {:id "n1" :name "New FM"})]
    (is (= ["j1" "n1" "f1"] (ids swapped)) "same position")
    (is (= {:id "n1" :name "New FM" :key "7" :color :teal :display :pad :custom-name "Mine"}
           (second swapped))
        "key, colour, look and custom name stay")
    (is (vector? swapped)))
  (testing "an unknown id changes nothing"
    (is (= three (favs/replace three "zz" {:id "n1" :name "New FM"}))))
  (testing "a station that is already a favourite changes nothing"
    (is (= three (favs/replace three "b1" jazz)))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb test:backend > /tmp/radiolisp-test.log 2>&1; echo $?` then read `/tmp/radiolisp-test.log`.
Expected: non-zero exit, error about `favs/replace` (unresolved or `clojure.core/replace` arity mismatch).

- [ ] **Step 3: Implement**

In the ns form change `(:refer-clojure :exclude [remove])` to `(:refer-clojure :exclude [remove replace])`. After `remove` add:

```clojure
(defn replace
  "Swaps the favourite `old-id` for `station` where it stands, keeping its key,
  colour, look and custom name. Unchanged when `old-id` is not a favourite or
  `station` already is."
  [favorites old-id {:keys [id] station-name :name}]
  (if (or (not (member? favorites old-id)) (member? favorites id))
    favorites
    (mapv (fn [fav] (if (= old-id (:id fav)) (assoc fav :id id :name station-name) fav))
          favorites)))
```

- [ ] **Step 3b: Check nothing else in the ns used `replace` from core**

Run: `grep -n "(replace " common/src/radiolisp/common/favorites.cljc`
Expected: only the new definition (no calls to core `replace`). If there are, qualify them as `clojure.core/replace`.

- [ ] **Step 4: Run to verify it passes**

Run: `bb test > /tmp/radiolisp-test.log 2>&1; echo $?`
Expected: `0`.

- [ ] **Step 5: Commit**

```bash
git add common/src/radiolisp/common/favorites.cljc common/test/radiolisp/common/favorites_test.cljc
git commit -m "feat(favorites): swap a favourite for another station in place"
```
(with the Co-Authored-By trailer)

---

### Task 2: UI strings (es, en only)

**Files:**
- Modify: `frontend/resources/i18n/es.edn`, `frontend/resources/i18n/en.edn`
- Test: `frontend/test/radiolisp/frontend/i18n_test.cljs` already enforces that `es` and `en` have the same keys and that no locale has keys missing from `en`.

- [ ] **Step 1: Add the keys**

In `en.edn`, after `:menu/customize` (line 31):

```clojure
 :menu/find-replacement "Find replacement"
```

after `:search/empty` (line 91):

```clojure
 :search/replacing    "Replacing “{name}”"
 :search/already-favorite "Already a favourite"
```

after `:toast/removed` (line 132):

```clojure
 :toast/replaced      "“{old}” replaced by “{new}”"
```

In `es.edn`, same places:

```clojure
 :menu/find-replacement "Buscar reemplazo"
 :search/replacing    "Reemplazando «{name}»"
 :search/already-favorite "Ya es favorita"
 :toast/replaced      "«{old}» reemplazada por «{new}»"
```

- [ ] **Step 2: Run the tests**

Run: `bb test:frontend > /tmp/radiolisp-test.log 2>&1; echo $?`
Expected: `0` (`es-and-en-have-the-same-keys` passes).

- [ ] **Step 3: Commit**

```bash
git add frontend/resources/i18n/es.edn frontend/resources/i18n/en.edn
git commit -m "feat(i18n): strings for replacing a missing station (es, en)"
```
(with the Co-Authored-By trailer)

---

### Task 3: Actions

**Files:**
- Modify: `frontend/src/radiolisp/frontend/actions.cljs` (`:favorites/*` section after `:favorites/remove`, ~line 447; `:search/open`, `:search/close`, `:search/choose`, `:search/toggle-favorite`, ~lines 550-632)
- Modify: `frontend/src/radiolisp/frontend/store.cljs` (document `:replacing` in `:search`)
- Create: `frontend/test/radiolisp/frontend/actions_replace_test.cljs` (picked up by shadow's `-test$` regexp)

- [ ] **Step 1: Write the failing tests**

Create `frontend/test/radiolisp/frontend/actions_replace_test.cljs`:

```clojure
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
  (let [menu  {:kind :station :id "zz" :anchor "more-zz"}
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
  (let [open (-> f/ready (update :search assoc :open? true :status :done :results [blues] :top [blues]))
        {:keys [state]} (a/handle open [:search/choose 0])]
    (is (= "b1" (get-in state [:player :station :id])))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb test:frontend > /tmp/radiolisp-test.log 2>&1; echo $?` and read the log.
Expected: non-zero; failures from the unhandled `:favorites/find-replacement` (`:log/warn` effect, search stays closed).

- [ ] **Step 3: Implement**

3a. `store.cljs`: in `:search`, after `:error`, add the key and comment:

```clojure
                  :error     nil        ; [:error/network {:at ms}]
                  :replacing nil}       ; id of the missing favourite the palette is finding a replacement for
```
(remove the old trailing `}` placement accordingly so the map stays valid).

3b. `actions.cljs`: replace `:search/open` and `:search/close` and add the helper:

```clojure
(defn- close-search
  "The search map closed. Leaving a replacement drops its query and results,
  which were about the old station, and any search still in flight."
  [{:keys [replacing top] :as search}]
  (cond-> (assoc search :open? false :mode :insert :replacing nil)
    replacing (-> (assoc :query "" :results top :highlight 0 :error nil
                         :status (if (seq top) :done :idle))
                  (update :seq inc))))

(defmethod handle :search/open [state [_ query]]
  (let [opened (ok (update state :search assoc :open? true :mode :insert :replacing nil)
                   [[:dom/focus :search-input]])]
    (cond
      (some? query)                                (then opened [:search/typed query])
      (= :idle (get-in state [:search :status]))   (then opened [:search/run])
      :else                                        opened)))

(defmethod handle :search/close [state _]
  (ok (update state :search close-search)
      [[:timer/cancel :search]]))
```

3c. `:search/choose` and `:search/toggle-favorite`:

```clojure
(defmethod handle :search/choose [state [_ index]]
  (let [state   (cond-> state index (assoc-in [:search :highlight] index))
        station (chosen state index)
        old-id  (get-in state [:search :replacing])]
    (cond
      (nil? old-id)                                                 (play-station state station)
      (or (nil? station) (favs/member? (items state) (:id station))) (ok state)
      :else                                                         (handle state [:favorites/replace old-id (:id station)]))))

(defmethod handle :search/toggle-favorite [state [_ index]]
  (if-let [station (and (nil? (get-in state [:search :replacing])) (chosen state index))]
    (handle state [:favorites/toggle (:id station)])
    (ok state)))
```

3d. After `:favorites/remove` add (uses `save`, `toast`, `add-effects`, `favorite-name`, `station-by-id`, `items`, all defined above that point):

```clojure
(defn- replacement-query
  "What to search for the missing favourite `id`: the name Radio Browser last
  gave it, else the stored one, else the user's own (which may be a nickname)."
  [state id]
  (let [favorite (favs/find-favorite (items state) id)]
    (or (get-in state [:cache id :name]) (:name favorite) (:custom-name favorite) "")))

(defmethod handle :favorites/find-replacement [state [_ id]]
  (if (contains? (get-in state [:favorites :missing]) id)
    (-> (handle (assoc state :menu nil) [:search/open (replacement-query state id)])
        (assoc-in [:state :search :replacing] id))
    (ok state)))

(defmethod handle :favorites/replace [state [_ old-id new-id]]
  (let [station (station-by-id state new-id)
        before  (items state)
        after   (when station (favs/replace before old-id station))]
    (if (or (nil? station) (= before after))
      (ok state)
      (let [old-name (favorite-name state old-id)]
        (-> (ok (-> state
                    (assoc-in [:favorites :items] after)
                    (update-in [:favorites :missing] disj old-id)
                    (update :search close-search)))
            (add-effects [:timer/cancel :search])
            save
            (toast [:toast/replaced {:old old-name :new (:name station)}]))))))
```

`close-search` is defined in the `search` section further down the file; ClojureScript needs it declared first. Put `close-search` (3b) **above** `:favorites/replace`, i.e. in the "favourites: editing" section, or add `(declare close-search)` before `:favorites/replace`. Prefer moving the `defn-` up: it only depends on `assoc`/`update`.

- [ ] **Step 4: Run to verify it passes**

Run: `bb test > /tmp/radiolisp-test.log 2>&1; echo $?`
Expected: `0`. If `closing` (existing `closing` test in `actions_search_test.cljs`) fails, the effects must still be exactly `[[:timer/cancel :search]]`.

- [ ] **Step 5: Lint and format**

Run: `bb lint > /tmp/radiolisp-lint.log 2>&1; echo $?` then `bb fmt:check > /tmp/radiolisp-fmt.log 2>&1; echo $?`
Expected: both `0`. If fmt fails, run `bb fmt` and re-check.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/radiolisp/frontend/actions.cljs frontend/src/radiolisp/frontend/store.cljs frontend/test/radiolisp/frontend/actions_replace_test.cljs
git commit -m "feat(actions): find and apply a replacement for a missing favourite"
```
(with the Co-Authored-By trailer)

---

### Task 4: Views and styles

**Files:**
- Modify: `frontend/src/radiolisp/frontend/views/grid.cljs:48-50`
- Modify: `frontend/src/radiolisp/frontend/views/menu.cljs:32-50` (`station-items`)
- Modify: `frontend/src/radiolisp/frontend/views/search.cljs` (`station-row`, `view`)
- Modify: `frontend/resources/styles/_grid.scss` (after `.flag`, ~line 239), `frontend/resources/styles/_search.scss` (after `.row`, ~line 57)

Views have no unit tests in this repo beyond `frontend/test/radiolisp/frontend/views/`; they are covered by `bb e2e` in Task 5. Follow `.agents/skills/design-taste-frontend` (app-screen rules) for any visual choice not given here.

- [ ] **Step 1: Grid flag becomes a button**

In `grid.cljs` replace the `cond` at the end of `tile`:

```clojure
     (cond
       missing? [:button.flag {:aria-label (str (t :tile/gone) ". " (t :menu/find-replacement))
                               :title      (t :menu/find-replacement)
                               :on         {:click [[:favorites/find-replacement id]]}}
                 (t :tile/gone)]
       sick?    [:span.flag (t :tile/no-signal)])]))
```

In `_grid.scss`, after the `.tile:not(:has(.name)) .flag { bottom: 0; }` rule, add:

```scss
// The flag of a missing station is a button: it starts the search for a replacement.
button.flag {
  border: 0;
  pointer-events: auto;
  cursor: pointer;

  &:hover { text-decoration: underline; }
  &:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
}
```

- [ ] **Step 2: Menu item**

In `menu.cljs` `station-items`, add `missing?` to the `let` and the item as the first entry of the `list`:

```clojure
(defn- station-items [{:keys [stations favorites locale] :as state} t id]
  (let [station  (get stations id)
        missing? (contains? (:missing favorites) id)
        {shortcut :key} (favs/find-favorite (:items favorites) id)]
    (list
     (when missing?
       (item icons/search (t :menu/find-replacement) [[:favorites/find-replacement id]]))
     (when-let [homepage (:homepage station)]
```
(the rest unchanged). `icons/search` already exists (used by the palette).

- [ ] **Step 3: Palette in replace mode**

In `search.cljs` `station-row`:

```clojure
(defn- station-row [{:keys [player favorites] :as state} t index {:keys [id] station-name :name :as station}]
  (let [highlight?  (= index (get-in state [:search :highlight]))
        current?    (= id (get-in player [:station :id]))
        active?     (and current? (#{:playing :connecting} (:status player)))
        favorite?   (favs/member? (:items favorites) id)
        replacing?  (some? (get-in state [:search :replacing]))
        taken?      (and replacing? favorite?)]
    [:li.row {:id            (str "st-" index)
              :replicant/key id
              :role          "option"
              :aria-selected (str highlight?)
              :aria-disabled (str taken?)
              :class         [(when highlight? "hl") (when current? "playing") (when taken? "taken")]
              :on            {:click [[:search/choose index]]}}
     (art/view state station "thumb")
     [:div.info
      [:b {:title station-name} station-name]
      [:div.meta-line (sv/quality station) [:span.tags (sv/tags-line station true)]]]
     (cond
       taken?     [:span.tag (t :search/already-favorite)]
       replacing? nil
       :else      [:div.acts
                   ...the two existing buttons, unchanged...])]))
```
Keep the two existing `icon-btn` buttons exactly as they are inside the `:else` branch's `[:div.acts ...]`.

In `view`, between the `.field` div and `(body state t)` add:

```clojure
      (when-let [old (:replacing search)]
        [:div.replacing (t :search/replacing {:name (actions/favorite-name state old)})])
```

In `_search.scss`, after `.palette.normal .row.hl { ... }`:

```scss
// Finding a replacement: a banner, and rows that are already favourites cannot be picked.
.replacing {
  flex: none; padding: 10px 20px; border-bottom: 1px solid var(--rule);
  background: var(--raised); color: var(--muted); font-size: 13px;
}
.row.taken {
  cursor: default; opacity: .5;
  &:hover { background: none; }
}
.row .tag { color: var(--muted); font-size: 12px; white-space: nowrap; }
```

- [ ] **Step 4: Build, lint, format**

Run: `bb test > /tmp/radiolisp-test.log 2>&1; echo $?`, `bb lint > /tmp/radiolisp-lint.log 2>&1; echo $?`, `bb fmt:check > /tmp/radiolisp-fmt.log 2>&1; echo $?`
Expected: all `0`.

- [ ] **Step 5: Look at it**

Run `bb dev` (http://localhost:8080) with a seeded missing favourite, e.g. in the browser console:
`localStorage.setItem('radiolisp/favorites', '{:version 1 :favorites [{:id "00000000-dead-beef-0000-000000000000" :name "Gone FM" :key "7"}]}')`, reload. Check by eye, light and dark theme and phone width:
the flag reads as a button and is clickable above the artwork (z-order), the menu item
shows first, the palette banner and dimmed rows look right, focus rings are visible.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/radiolisp/frontend/views frontend/resources/styles
git commit -m "feat(frontend): flag button, menu item and palette mode to replace a missing station"
```
(with the Co-Authored-By trailer)

---

### Task 5: End-to-end check

**Files:**
- Modify: `frontend/e2e/check.mjs` (new block after the `drag order persisted` check, ~line 322)

- [ ] **Step 1: Add the block**

```js
  // ---------------------------------------------------------------- replacing a station Radio Browser dropped
  const GONE = '00000000-dead-beef-0000-000000000000';
  const rep = await newApp(browser, { width: 1440, height: 900, seed: edn([{ id: GONE, name: 'Gone FM', key: '7' }, STATIONS.bbc]) });
  await waitFor(rep, `!!document.querySelector('.flag')`);
  await click(rep, '.tile:nth-child(1) button.flag');
  check('the flag opens the palette in replace mode', (await exists(rep, '.palette .replacing')) && (await rep.eval(`document.getElementById('search-input').value`)) === 'Gone FM', await text(rep, '.replacing'));
  await rep.eval(`document.getElementById('search-input').select()`);
  await type(rep, 'BBC World');
  await waitFor(rep, `!!document.querySelector('#search-list .row.taken')`);
  check('a station that is already a favourite is dimmed', await exists(rep, '#search-list .row.taken[aria-disabled=true]'));
  await click(rep, '#search-list .row.taken');
  check('picking it does nothing', (await exists(rep, '.palette .replacing')) && (await storedIds(rep))[0] === GONE);
  await rep.eval(`document.getElementById('search-input').select()`);
  await type(rep, 'France Inter');
  await waitFor(rep, `[...document.querySelectorAll('#search-list .row b')].some(b => /France Inter/.test(b.textContent))`);
  await rep.eval(`[...document.querySelectorAll('#search-list .row:not(.taken)')][0].click()`);
  await sleep(300);
  check('picking another station closes the palette', !(await exists(rep, '.palette')));
  const swapped = await storedIds(rep);
  check('the new station took the place of the old one', swapped.length === 2 && swapped[0] !== GONE && swapped[1] === STATIONS.bbc.id, JSON.stringify(swapped));
  check('it kept the key', (await text(rep, '.tile:nth-child(1) .keycap')) === '7');
  check('the toast says so', /reemplazada/.test((await toast(rep)) || ''), await toast(rep));
  check('nothing is flagged any more', !(await exists(rep, 'button.flag')));
```

- [ ] **Step 2: Run it**

With `bb dev` running in another shell: `bb e2e > /tmp/radiolisp-e2e.log 2>&1; echo $?`, then read the log.
Expected: every new check `PASS`; no existing check regressed (`FAIL` lines are none). The run needs network (real Radio Browser). If `France Inter` is not found at the top, any non-taken first row is fine.

- [ ] **Step 3: Commit**

```bash
git add frontend/e2e/check.mjs
git commit -m "test(e2e): replace a missing station from its flag"
```
(with the Co-Authored-By trailer)

---

### Task 6: Docs and final verification

**Files:**
- Modify: `AGENTS.md:4-10`

- [ ] **Step 1: List the spec in AGENTS.md**

In the sentence that lists the design specs, change `and `docs/specs/2026-10-24-pwa-design.md` (manifest, service worker, install); read them before changing behaviour.` to:

```
`docs/specs/2026-10-24-pwa-design.md` (manifest, service worker,
install) and `docs/specs/2026-11-23-replace-missing-station-design.md` (finding a
replacement for a favourite Radio Browser dropped); read them before changing behaviour.
```
(and keep the `and` before the pwa spec grammatical: `... and `docs/specs/2026-10-20-backend-design.md` (...), `docs/specs/2026-10-24-pwa-design.md` ...`).

- [ ] **Step 2: Full verification**

Run, each redirected to a file, reading the logs after:
`bb lint`, `bb fmt:check`, `bb test`.
Expected: all exit `0`. Do not claim done otherwise (superpowers:verification-before-completion).

- [ ] **Step 3: Commit**

```bash
git add AGENTS.md
git commit -m "docs(agents): list the replace-missing-station spec"
```
(with the Co-Authored-By trailer)

- [ ] **Step 4: Hand off**

Do **not** push or open a PR unless the user asks (`AGENTS.md`: push only on explicit request). Report the branch, the commits and the verification results.

---

## Self-review

- **Spec coverage:** §1 behaviour → Tasks 3-4 (swap, entry points, banner, blocked duplicates, toast); §2 `favs/replace` → Task 1; §3 actions (`:replacing`, find-replacement, choose, replace, toggle, close/open) → Task 3; §4 views → Task 4; §4b es/en strings → Task 2; §5 tests → Tasks 1, 3, 5; §6 out of scope → not implemented.
- **Placeholder scan:** none; the one elided block in Task 4 Step 3 ("two existing buttons, unchanged") refers to code that already exists verbatim in `search.cljs`.
- **Consistency:** actions are `:favorites/find-replacement id` and `:favorites/replace old-id new-id` everywhere (views, tests, e2e); `close-search` is used by `:search/close` and `:favorites/replace`; the state key is `[:search :replacing]` throughout.
