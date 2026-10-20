# radiolisp MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the radiolisp MVP: a frontend-only web radio player with incremental Radio Browser search, a centred player, vim-style keyboard navigation and es/en i18n, as specified in `docs/specs/2026-10-20-mvp-design.md`.

**Architecture:** ClojureScript SPA rendered with Replicant (pure hiccup, no React). One `atom` holds the state. Every change goes through a pure `actions/handle` (state × action → `{:state :effects}`), and an `effects/execute!` multimethod is the only impure layer (fetch, `<audio>`, timers, focus). Pure domain code shared with a future backend lives in `common/` as `.cljc`.

**Tech Stack:** Clojure CLI + `deps.edn`, shadow-cljs 3.5.7, Replicant 2026.07.1, dart-sass, babashka tasks, Nix flake devShell, `cljs.test` on Node, clj-kondo, cljfmt.

**Conventions for every task:**
- Work from the repo root `radiolisp`, inside `nix develop` (Task 1 creates it). Every command below assumes that shell.
- The user writes Spanish; code, comments, commit messages and identifiers are English. UI strings live only in `frontend/resources/i18n/*.edn`.
- Never push. Commit at the end of every task with the message given, plus a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Never pipe test output into `head`/`tail`/`grep`. Redirect to a file and read the file.

---

## File map

| File | Responsibility |
|---|---|
| `flake.nix` | devShell with JDK 21, clojure, babashka, node 22, dart-sass, clj-kondo, cljfmt, clojure-lsp |
| `bb.edn` | `setup`, `dev`, `css`, `build`, `test`, `lint`, `fmt`, `fmt:check` |
| `deps.edn` | groups `common` + `frontend` for clojure-lsp only |
| `.clj-kondo/config.edn`, `.cljfmt.edn` | lint and format configuration |
| `AGENTS.md`, `CLAUDE.md` | instructions for AI agents |
| `common/deps.edn` | `:paths ["src"]` |
| `common/src/radiolisp/common/format.cljc` | `clock`, `elapsed` |
| `common/src/radiolisp/common/station.cljc` | `normalize`: Radio Browser JSON → station map |
| `frontend/deps.edn`, `frontend/shadow-cljs.edn`, `frontend/package.json` | build config |
| `frontend/resources/public/index.html` | HTML shell |
| `frontend/resources/i18n/{es,en}.edn` | dictionaries |
| `frontend/resources/styles/*.scss` | styles, compiled to `resources/public/css/main.css` |
| `frontend/src/radiolisp/frontend/i18n.cljs` | `t`, `detect-locale`, `format-time` |
| `frontend/src/radiolisp/frontend/store.cljs` | `initial-state`, `state` atom |
| `frontend/src/radiolisp/frontend/actions.cljs` | `handle` (pure), `visible-stations`, `query-active?` |
| `frontend/src/radiolisp/frontend/keymap.cljs` | `key->action` (pure) |
| `frontend/src/radiolisp/frontend/api.cljs` | Radio Browser HTTP |
| `frontend/src/radiolisp/frontend/audio.cljs` | the `<audio>` element |
| `frontend/src/radiolisp/frontend/effects.cljs` | `execute!` |
| `frontend/src/radiolisp/frontend/views/{icons,station,player,search,help,shell}.cljs` | hiccup views |
| `frontend/src/radiolisp/frontend/main.cljs` | wiring: dispatch, render loop, keydown, clock |
| `common/test/...`, `frontend/test/...` | tests (`*-test` namespaces) |

---

### Task 1: Nix devShell

**Files:**
- Create: `flake.nix`
- Create: `.envrc`

- [ ] **Step 1: Write `flake.nix`**

```nix
{
  description = "radiolisp: a minimal web radio player written in ClojureScript";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            jdk21
            clojure
            babashka
            nodejs_22
            dart-sass
            clj-kondo
            cljfmt
            clojure-lsp
          ];
        };
      });
    };
}
```

- [ ] **Step 2: Write `.envrc`** (optional for direnv users)

```
use flake
```

- [ ] **Step 3: Verify the shell**

Run: `git add flake.nix && nix develop -c sh -c 'java -version 2>&1 | head -1; clojure --version; bb --version; node --version; sass --version; clj-kondo --version; cljfmt --version'`

(`git add` first: flakes only see tracked files.)

Expected: one line per tool, e.g. `openjdk version "21…"`, `Clojure CLI version 1.12…`, `babashka v1.12…`, `v22…`, `1.100.0`, `clj-kondo v2026…`, `0.16.4`. No "command not found".

- [ ] **Step 4: Commit**

```bash
git add flake.nix flake.lock .envrc
git commit -m "build: add Nix devShell"
```

---

### Task 2: Repository skeleton, tooling and a green smoke test

**Files:**
- Create: `deps.edn`, `bb.edn`, `.clj-kondo/config.edn`, `.cljfmt.edn`, `AGENTS.md`, `CLAUDE.md`
- Create: `common/deps.edn`
- Create: `frontend/deps.edn`, `frontend/shadow-cljs.edn`, `frontend/package.json`, `frontend/resources/public/index.html`
- Create: `frontend/src/radiolisp/frontend/main.cljs` (temporary stub)
- Test: `frontend/test/radiolisp/frontend/smoke_test.cljs` (deleted in Task 4)

- [ ] **Step 1: Root `deps.edn`**

```clojure
;; This file only exists so clojure-lsp sees every module of the repository.
{:deps
 {radiolisp/common   {:local/root "common"}
  radiolisp/frontend {:local/root "frontend"}}}
```

- [ ] **Step 2: `common/deps.edn`**

```clojure
{:paths ["src"]
 :deps  {}}
```

- [ ] **Step 3: `frontend/deps.edn`**

```clojure
{:paths ["src" "resources"]
 :deps
 {no.cjohansen/replicant {:mvn/version "2026.07.1"}
  radiolisp/common       {:local/root "../common"}}

 :aliases
 {:dev  {:extra-deps {thheller/shadow-cljs {:mvn/version "3.5.7"}}}
  :test {:extra-paths ["test" "../common/test"]}}}
```

- [ ] **Step 4: `frontend/shadow-cljs.edn`**

```clojure
{:deps     {:aliases [:dev :test]}
 :dev-http {8080 "resources/public"}

 :builds
 {:app  {:target     :browser
         :output-dir "resources/public/js"
         :asset-path "/js"
         :modules    {:main {:init-fn radiolisp.frontend.main/init}}
         :devtools   {:watch-dir "resources/public"}}

  :test {:target    :node-test
         :output-to "target/test/node-tests.js"
         :ns-regexp "-test$"}}}
```

- [ ] **Step 5: `frontend/package.json`**

```json
{
  "name": "radiolisp-frontend",
  "private": true,
  "devDependencies": {
    "shadow-cljs": "3.5.7"
  }
}
```

- [ ] **Step 6: `frontend/resources/public/index.html`**

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>radiolisp</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=DM+Sans:opsz,wght@9..40,400;9..40,500;9..40,600;9..40,700&display=swap">
<link rel="stylesheet" href="/css/main.css">
</head>
<body>
<div id="app"></div>
<script src="/js/main.js"></script>
</body>
</html>
```

- [ ] **Step 7: Temporary `frontend/src/radiolisp/frontend/main.cljs`**

```clojure
(ns radiolisp.frontend.main)

(defn init []
  (set! (.-textContent (js/document.getElementById "app")) "radiolisp"))
```

- [ ] **Step 8: `bb.edn`**

```clojure
{:tasks
 {:init      (def frontend {:dir "frontend"})

  setup      {:doc  "Install the npm dependencies of the frontend"
              :task (shell frontend "npm install")}

  dev        {:doc  "Watch ClojureScript and SCSS; serves http://localhost:8080"
              :task (run '-dev {:parallel true})}
  -dev       {:depends [dev:cljs dev:css]}
  dev:cljs   (shell frontend "npx shadow-cljs watch app")
  dev:css    (shell frontend "sass --watch --no-source-map resources/styles/main.scss:resources/public/css/main.css")

  css        {:doc  "Compile the SCSS once, compressed"
              :task (shell frontend "sass --no-source-map --style=compressed resources/styles/main.scss resources/public/css/main.css")}

  build      {:doc     "Release build into frontend/resources/public"
              :depends [css]
              :task    (shell frontend "npx shadow-cljs release app")}

  test       {:doc  "Run every test (common and frontend) on Node"
              :task (do (shell frontend "npx shadow-cljs compile test")
                        (shell frontend "node target/test/node-tests.js"))}

  lint       {:doc  "Lint with clj-kondo"
              :task (shell "clj-kondo --lint common/src common/test frontend/src frontend/test")}

  fmt        {:doc  "Format every Clojure file"
              :task (shell "cljfmt fix common/src common/test frontend/src frontend/test")}

  fmt:check  {:doc  "Fail if any Clojure file is not formatted"
              :task (shell "cljfmt check common/src common/test frontend/src frontend/test")}}}
```

- [ ] **Step 9: `.clj-kondo/config.edn` and `.cljfmt.edn`**

`.clj-kondo/config.edn`:

```clojure
{:linters {:shadowed-var {:level :warning}}}
```

`.cljfmt.edn`:

```clojure
{:sort-ns-references? true}
```

- [ ] **Step 10: `AGENTS.md` and `CLAUDE.md`**

`AGENTS.md`:

```markdown
# radiolisp: guide for AI agents

radiolisp is a minimal web radio player written in ClojureScript, built to learn
Clojure. The design spec is `docs/specs/2026-10-20-mvp-design.md`; read it before
changing behaviour.

## Hard rules

- Never `git push` or touch remotes. The user pushes.
- Run `bb lint`, `bb fmt:check` and `bb test` before you call a change done.
- Never pipe test output into `head`, `tail` or `grep`; redirect to a file first.
- UI strings live only in `frontend/resources/i18n/{es,en}.edn`, with the same
  keys in both. State holds keys and data, never translated text.
- `actions/handle` and `keymap/key->action` stay pure. Side effects go in
  `effects/execute!`, `api`, `audio`.
- Design changes follow `.agents/skills/design-taste-frontend` (app-screen rules).

## Modules

- `common/`: pure `.cljc` shared with a future backend (`radiolisp.common.*`).
- `frontend/`: shadow-cljs + Replicant SPA (`radiolisp.frontend.*`), SCSS in
  `resources/styles/`.

## Tasks (inside `nix develop`)

`bb setup` · `bb dev` (http://localhost:8080) · `bb test` · `bb lint` · `bb fmt` ·
`bb fmt:check` · `bb build`
```

`CLAUDE.md`:

```markdown
Read and follow the instructions in `AGENTS.md`.
```

- [ ] **Step 11: Smoke test `frontend/test/radiolisp/frontend/smoke_test.cljs`**

```clojure
(ns radiolisp.frontend.smoke-test
  (:require [cljs.test :refer [deftest is]]))

(deftest the-toolchain-runs
  (is (= 2 (+ 1 1))))
```

- [ ] **Step 12: Install and run the tests**

Run: `bb setup > /tmp/radiolisp-setup.txt 2>&1; bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read `/tmp/radiolisp-test.txt`.

Expected: `exit=0`, and the file ends with `Ran 1 tests containing 1 assertions.` and `0 failures, 0 errors.` (The first run downloads dependencies and takes a few minutes.)

- [ ] **Step 13: Run lint and format check**

Run: `bb lint; bb fmt:check`
Expected: `linting took …ms, errors: 0, warnings: 0` and no output from cljfmt.

- [ ] **Step 14: Commit**

```bash
git add deps.edn bb.edn .clj-kondo/config.edn .cljfmt.edn AGENTS.md CLAUDE.md common frontend .gitignore
git commit -m "build: add repository skeleton, bb tasks and a smoke test"
```

---

### Task 3: `common.format`

**Files:**
- Create: `common/src/radiolisp/common/format.cljc`
- Test: `common/test/radiolisp/common/format_test.cljc`

- [ ] **Step 1: Write the failing test**

```clojure
(ns radiolisp.common.format-test
  (:require [clojure.test :refer [deftest is]]
            [radiolisp.common.format :as fmt]))

(deftest clock-formats-seconds-as-hh-mm-ss
  (is (= "00:00:00" (fmt/clock 0)))
  (is (= "00:01:05" (fmt/clock 65)))
  (is (= "00:12:34" (fmt/clock 754)))
  (is (= "01:00:00" (fmt/clock 3600)))
  (is (= "00:00:00" (fmt/clock -5))))

(deftest elapsed-counts-whole-seconds-between-two-instants
  (is (= 754 (fmt/elapsed 1000 755999)))
  (is (= 0 (fmt/elapsed nil 5000)))
  (is (= 0 (fmt/elapsed 5000 1000))))
```

- [ ] **Step 2: Run it to see it fail**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: non-zero exit; the compile fails with `The required namespace "radiolisp.common.format" is not available`.

- [ ] **Step 3: Implement**

```clojure
(ns radiolisp.common.format
  "Pure formatting helpers shared by every platform.")

(defn- pad2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn clock
  "Formats a number of seconds as hh:mm:ss. Negative input counts as zero."
  [seconds]
  (let [s (max 0 (int seconds))]
    (str (pad2 (quot s 3600)) ":" (pad2 (mod (quot s 60) 60)) ":" (pad2 (mod s 60)))))

(defn elapsed
  "Whole seconds from `started-at` to `now`, both in milliseconds.
  Zero when playback has not started or the clock went backwards."
  [started-at now]
  (if started-at
    (max 0 (quot (- now started-at) 1000))
    0))
```

- [ ] **Step 4: Run the tests**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: `exit=0`, `0 failures, 0 errors.`

- [ ] **Step 5: Commit**

```bash
git add common/src/radiolisp/common/format.cljc common/test/radiolisp/common/format_test.cljc
git commit -m "feat(common): add clock and elapsed formatting"
```

---

### Task 4: `common.station`

**Files:**
- Create: `common/src/radiolisp/common/station.cljc`
- Test: `common/test/radiolisp/common/station_test.cljc`
- Delete: `frontend/test/radiolisp/frontend/smoke_test.cljs`

- [ ] **Step 1: Write the failing test**

The fixtures are real Radio Browser rows (2026-10-20), trimmed to the keys used.

```clojure
(ns radiolisp.common.station-test
  (:require [clojure.test :refer [deftest is testing]]
            [radiolisp.common.station :as station]))

(def adroit
  {"stationuuid"  "a1b2"
   "name"         "  Adroit Jazz Underground "
   "url"          "https://icecast.walmradio.com:8443/jazz.m3u"
   "url_resolved" "https://icecast.walmradio.com:8443/jazz"
   "countrycode"  "US"
   "codec"        "MP3"
   "bitrate"      320
   "clickcount"   445
   "tags"         "avant-garde,bebop,big band,bop,combos"
   "hls"          0})

(def france-inter
  {"stationuuid"  "f1"
   "name"         "France Inter"
   "url"          "http://icecast.radiofrance.fr/franceinter-midfi.mp3"
   "url_resolved" ""
   "countrycode"  ""
   "codec"        "UNKNOWN"
   "bitrate"      0
   "clickcount"   734
   "tags"         ""
   "hls"          0})

(deftest normalize-keeps-what-the-app-shows
  (is (= {:id      "a1b2"
          :name    "Adroit Jazz Underground"
          :url     "https://icecast.walmradio.com:8443/jazz"
          :country "US"
          :codec   "MP3"
          :bitrate 320
          :clicks  445
          :tags    ["avant-garde" "bebop" "big band"]
          :hls?    false}
         (station/normalize adroit))))

(deftest normalize-turns-unknown-values-into-nil
  (let [s (station/normalize france-inter)]
    (testing "unknown codec and zero bitrate"
      (is (nil? (:codec s)))
      (is (nil? (:bitrate s))))
    (testing "empty country and tags"
      (is (nil? (:country s)))
      (is (= [] (:tags s))))
    (testing "falls back to url when url_resolved is empty"
      (is (= "http://icecast.radiofrance.fr/franceinter-midfi.mp3" (:url s))))))

(deftest normalize-detects-hls
  (is (true? (:hls? (station/normalize (assoc adroit "hls" 1)))))
  (is (true? (:hls? (station/normalize (assoc adroit "url_resolved" "https://x.example/live/index.m3u8?token=1")))))
  (is (false? (:hls? (station/normalize adroit)))))

(deftest normalize-cleans-tags
  (is (= ["jazz" "soul"] (:tags (station/normalize (assoc adroit "tags" " jazz, ,soul,jazz"))))))
```

- [ ] **Step 2: Run it to see it fail**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: non-zero exit; `The required namespace "radiolisp.common.station" is not available`.

- [ ] **Step 3: Implement**

```clojure
(ns radiolisp.common.station
  "Turns a station from the Radio Browser JSON API into the map radiolisp
  works with. Pure and shared with the future backend."
  (:require [clojure.string :as str]))

(def max-tags 3)

(defn- blank->nil [s]
  (let [s (some-> s str str/trim)]
    (when-not (str/blank? s) s)))

(defn- parse-tags [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       distinct
       (take max-tags)
       vec))

(defn- hls-url? [url]
  (boolean (some->> url (re-find #"(?i)\.m3u8(\?|$)"))))

(defn normalize
  "Takes one station as decoded from Radio Browser JSON (string keys) and
  returns `{:id :name :url :country :codec :bitrate :clicks :tags :hls?}`.
  Values Radio Browser does not know (codec \"UNKNOWN\", bitrate 0, empty
  strings) become nil."
  [{:strs [stationuuid name url url_resolved countrycode codec bitrate clickcount tags hls]}]
  (let [stream (or (blank->nil url_resolved) (blank->nil url))
        codec  (blank->nil codec)]
    {:id      stationuuid
     :name    (or (blank->nil name) "")
     :url     stream
     :country (blank->nil countrycode)
     :codec   (when-not (= "UNKNOWN" (some-> codec str/upper-case)) codec)
     :bitrate (when (and (number? bitrate) (pos? bitrate)) bitrate)
     :clicks  (or clickcount 0)
     :tags    (parse-tags tags)
     :hls?    (or (= 1 hls) (hls-url? stream))}))
```

- [ ] **Step 4: Delete the smoke test and run the tests**

Run: `git rm -q frontend/test/radiolisp/frontend/smoke_test.cljs; bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: `exit=0`, `0 failures, 0 errors.`

- [ ] **Step 5: Commit**

```bash
git add common/src/radiolisp/common/station.cljc common/test/radiolisp/common/station_test.cljc
git commit -m "feat(common): normalize Radio Browser stations"
```

---

### Task 5: i18n dictionaries and `i18n.cljs`

**Files:**
- Create: `frontend/resources/i18n/es.edn`, `frontend/resources/i18n/en.edn`
- Create: `frontend/src/radiolisp/frontend/i18n.cljs`
- Test: `frontend/test/radiolisp/frontend/i18n_test.cljs`

- [ ] **Step 1: `frontend/resources/i18n/es.edn`**

```clojure
{:brand/name          "radiolisp"
 :lang/label          "Idioma"

 :player/idle-title   "Nada sonando"
 :player/idle-hint    "Busca una emisora para empezar"
 :player/live         "En directo"
 :player/paused       "En pausa"
 :player/connecting   "Conectando…"
 :player/play         "Reproducir {name}"
 :player/pause        "Pausar {name}"
 :player/mute         "Silenciar"
 :player/unmute       "Activar sonido"
 :player/volume       "Volumen"

 :search/open         "Buscar emisora"
 :search/close        "Cerrar la búsqueda"
 :search/label        "Buscar emisoras por nombre"
 :search/placeholder  "Nombre de la emisora"
 :search/top          "Más escuchadas hoy"
 :search/results      {:one "{count} emisora" :other "{count} emisoras"}
 :search/order        "por escuchas en 24 h"
 :search/mode-insert  "escribiendo"
 :search/mode-normal  "navegando"
 :search/loading      "Buscando…"
 :search/empty        "Ninguna emisora se llama «{query}». Prueba con una parte del nombre."

 :station/no-tags     "sin etiquetas"
 :station/listens     {:one "{count} escucha" :other "{count} escuchas"}

 :error/network       "No se pudo conectar con Radio Browser a las {time}."
 :error/stream        "No se pudo reproducir: el stream no responde."
 :error/unsupported   "Esta emisora usa un formato que aún no se puede reproducir."
 :error/retry         "Reintentar"

 :help/open           "Atajos"
 :help/title          "Atajos de teclado"
 :help/close          "Cerrar"
 :help/global         "En cualquier momento"
 :help/search         "En la búsqueda"
 :help/k-search       "Abrir la búsqueda"
 :help/k-toggle       "Reproducir o pausar"
 :help/k-volume       "Bajar o subir el volumen"
 :help/k-mute         "Silenciar o activar el sonido"
 :help/k-help         "Mostrar esta ayuda"
 :help/k-move         "Emisora siguiente o anterior"
 :help/k-edges        "Primera o última emisora"
 :help/k-choose       "Escuchar la emisora marcada"
 :help/k-normal       "Dejar de escribir y navegar; otra vez, cerrar"
 :help/k-insert       "Volver a escribir"}
```

- [ ] **Step 2: `frontend/resources/i18n/en.edn`**

```clojure
{:brand/name          "radiolisp"
 :lang/label          "Language"

 :player/idle-title   "Nothing playing"
 :player/idle-hint    "Search for a station to start"
 :player/live         "Live"
 :player/paused       "Paused"
 :player/connecting   "Connecting…"
 :player/play         "Play {name}"
 :player/pause        "Pause {name}"
 :player/mute         "Mute"
 :player/unmute       "Unmute"
 :player/volume       "Volume"

 :search/open         "Search stations"
 :search/close        "Close search"
 :search/label        "Search stations by name"
 :search/placeholder  "Station name"
 :search/top          "Most played today"
 :search/results      {:one "{count} station" :other "{count} stations"}
 :search/order        "by plays in 24 h"
 :search/mode-insert  "typing"
 :search/mode-normal  "browsing"
 :search/loading      "Searching…"
 :search/empty        "No station is called “{query}”. Try part of the name."

 :station/no-tags     "no tags"
 :station/listens     {:one "{count} play" :other "{count} plays"}

 :error/network       "Could not reach Radio Browser at {time}."
 :error/stream        "Could not play: the stream is not responding."
 :error/unsupported   "This station uses a format that cannot be played yet."
 :error/retry         "Retry"

 :help/open           "Shortcuts"
 :help/title          "Keyboard shortcuts"
 :help/close          "Close"
 :help/global         "Anywhere"
 :help/search         "In search"
 :help/k-search       "Open search"
 :help/k-toggle       "Play or pause"
 :help/k-volume       "Volume down or up"
 :help/k-mute         "Mute or unmute"
 :help/k-help         "Show this help"
 :help/k-move         "Next or previous station"
 :help/k-edges        "First or last station"
 :help/k-choose       "Play the marked station"
 :help/k-normal       "Stop typing and browse; again, close"
 :help/k-insert       "Back to typing"}
```

- [ ] **Step 3: Write the failing test `frontend/test/radiolisp/frontend/i18n_test.cljs`**

```clojure
(ns radiolisp.frontend.i18n-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.i18n :as i18n]))

(deftest t-interpolates-variables
  (is (= "Pausar Jazz Radio" (i18n/t :es :player/pause {:name "Jazz Radio"})))
  (is (= "Ninguna emisora se llama «xyzzy». Prueba con una parte del nombre."
         (i18n/t :es :search/empty {:query "xyzzy"}))))

(deftest t-picks-the-plural-form
  (is (= "1 emisora" (i18n/t :es :search/results {:count 1})))
  (is (= "12 emisoras" (i18n/t :es :search/results {:count 12})))
  (is (= "1 station" (i18n/t :en :search/results {:count 1})))
  (is (= "12 stations" (i18n/t :en :search/results {:count 12}))))

(deftest t-formats-numbers-for-the-locale
  (is (= "1,678 plays" (i18n/t :en :station/listens {:count 1678})))
  (is (= "12.345 escuchas" (i18n/t :es :station/listens {:count 12345}))))

(deftest t-shows-missing-keys
  (is (= ":nope/missing" (i18n/t :es :nope/missing))))

(deftest dictionaries-have-the-same-keys
  (let [{:keys [es en]} i18n/dictionaries]
    (is (= (set (keys es)) (set (keys en))))
    (doseq [[k v] es :when (map? v)]
      (testing (str k)
        (is (= (set (keys v)) (set (keys (get en k)))))))))

(deftest detect-locale-takes-the-first-supported-language
  (is (= :es (i18n/detect-locale ["es-ES" "en"])))
  (is (= :es (i18n/detect-locale ["ES"])))
  (is (= :en (i18n/detect-locale ["fr-FR" "en-GB"])))
  (is (= :en (i18n/detect-locale ["fr"])))
  (is (= :en (i18n/detect-locale []))))
```

- [ ] **Step 4: Run it to see it fail**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: non-zero exit; `The required namespace "radiolisp.frontend.i18n" is not available`.

- [ ] **Step 5: Implement `frontend/src/radiolisp/frontend/i18n.cljs`**

```clojure
(ns radiolisp.frontend.i18n
  "Translations. Dictionaries are EDN files inlined at compile time; the
  state only ever holds keys and data, the views call `t`."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [shadow.resource :as rc]))

(def dictionaries
  {:es (reader/read-string (rc/inline "i18n/es.edn"))
   :en (reader/read-string (rc/inline "i18n/en.edn"))})

(def locales [:es :en])

(def default-locale :en)

(defn detect-locale
  "First supported locale in a browser preference list such as
  [\"es-ES\" \"en\"]; `default-locale` when none is supported."
  [languages]
  (or (some (fn [lang]
              (let [base (keyword (first (str/split (str/lower-case lang) #"-")))]
                (some #{base} locales)))
            languages)
      default-locale))

(defn- format-number [locale n]
  (.format (js/Intl.NumberFormat. (name locale)) n))

(defn- plural-form [locale n]
  (keyword (.select (js/Intl.PluralRules. (name locale)) n)))

(defn t
  "Translates `k` into `locale`, replacing `{var}` with values from `vars`.
  A message that is a map is a set of plural forms chosen by `(:count vars)`.
  Numbers are formatted for the locale. A missing key renders as itself."
  ([locale k] (t locale k {}))
  ([locale k vars]
   (let [message (get-in dictionaries [locale k] (str k))
         message (if (map? message)
                   (get message (plural-form locale (:count vars)) (:other message))
                   message)]
     (str/replace message #"\{(\w+)\}"
                  (fn [[_ var-name]]
                    (let [v (get vars (keyword var-name))]
                      (if (number? v) (format-number locale v) (str v))))))))

(defn format-time
  "Hours and minutes of `ms` (epoch milliseconds) in the locale's style."
  [locale ms]
  (.format (js/Intl.DateTimeFormat. (name locale) #js {:hour "2-digit" :minute "2-digit"})
           (js/Date. ms)))
```

- [ ] **Step 6: Run the tests**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: `exit=0`, `0 failures, 0 errors.`

- [ ] **Step 7: Commit**

```bash
git add frontend/resources/i18n frontend/src/radiolisp/frontend/i18n.cljs frontend/test/radiolisp/frontend/i18n_test.cljs
git commit -m "feat(frontend): add es/en dictionaries and translation function"
```

---

### Task 6: Store and the action core (app and search)

**Files:**
- Create: `frontend/src/radiolisp/frontend/store.cljs`
- Create: `frontend/src/radiolisp/frontend/actions.cljs`
- Test: `frontend/test/radiolisp/frontend/actions_search_test.cljs`

- [ ] **Step 1: `frontend/src/radiolisp/frontend/store.cljs`**

```clojure
(ns radiolisp.frontend.store
  "The single source of truth: one atom with the whole app state.")

(def initial-state
  {:locale :en
   :help?  false
   :player {:station    nil
            :status     :stopped   ; :stopped :connecting :playing :paused :error
            :error      nil        ; [:error/stream {}] | [:error/unsupported {}]
            :started-at nil
            :volume     70
            :muted?     false}
   :search {:open?     true
            :query     ""
            :mode      :insert     ; :insert (typing) | :normal (browsing the list)
            :status    :loading    ; :loading :done :error
            :seq       0
            :retried?  false
            :results   []
            :top       []
            :highlight 0
            :error     nil}        ; [:error/network {:at ms}]
   :api    {:servers [] :current 0}
   :keys   {:last-g 0}
   :now    0})

(defonce state (atom initial-state))
```

- [ ] **Step 2: Write the failing test `frontend/test/radiolisp/frontend/actions_search_test.cljs`**

```clojure
(ns radiolisp.frontend.actions-search-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.store :as store]))

(def jazz {:id "j1" :name "Jazz Radio" :url "http://jazz.example/stream" :hls? false})
(def blues {:id "b1" :name "Blues FM" :url "http://blues.example/stream" :hls? false})

(def ready
  "Servers known, the top list loaded and shown."
  (-> store/initial-state
      (assoc :api {:servers ["s1" "s2"] :current 0})
      (update :search assoc :status :done :results [jazz blues] :top [jazz blues])))

(defn- run-all
  "Applies `actions` in order and collects every effect."
  [state & actions]
  (reduce (fn [{:keys [state effects]} action]
            (let [r (a/handle state action)]
              {:state (:state r) :effects (into effects (:effects r))}))
          {:state state :effects []}
          actions))

(deftest query-active-needs-two-characters
  (is (false? (a/query-active? "")))
  (is (false? (a/query-active? " j ")))
  (is (true? (a/query-active? "ja"))))

(deftest init-sets-locale-and-asks-for-servers
  (let [{:keys [state effects]} (a/handle store/initial-state [:app/init {:locale :es}])]
    (is (= :es (:locale state)))
    (is (= [[:dom/lang :es] [:audio/volume 0.7] [:api/servers] [:dom/focus :search-input]] effects))))

(deftest servers-loaded-runs-the-first-fetch
  (let [{:keys [state effects]} (a/handle store/initial-state [:api/servers-loaded ["s1" "s2"]])]
    (is (= {:servers ["s1" "s2"] :current 0} (:api state)))
    (is (= 1 (get-in state [:search :seq])))
    (is (some #{[:api/fetch {:server "s1" :query nil :seq 1}]} effects))))

(deftest run-without-servers-waits
  (let [{:keys [effects]} (a/handle store/initial-state [:search/run])]
    (is (= [] effects))))

(deftest typing-debounces-the-search
  (let [{:keys [state effects]} (a/handle ready [:search/typed "jaz"])]
    (is (= "jaz" (get-in state [:search :query])))
    (is (= :loading (get-in state [:search :status])))
    (is (= [[:timer/start :search 300 [:search/run]]] effects))))

(deftest run-fetches-the-trimmed-query
  (let [{:keys [state effects]} (run-all ready [:search/typed " jazz "] [:search/run])]
    (is (= 1 (get-in state [:search :seq])))
    (is (some #{[:api/fetch {:server "s1" :query "jazz" :seq 1}]} effects))))

(deftest short-query-goes-back-to-the-cached-top-list
  (let [{:keys [state effects]} (run-all ready [:search/typed "jazz"] [:search/run] [:search/typed "j"])]
    (is (= :done (get-in state [:search :status])))
    (is (= [jazz blues] (get-in state [:search :results])))
    (testing "the search in flight is invalidated"
      (is (= 2 (get-in state [:search :seq]))))
    (is (some #{[:timer/cancel :search]} effects))))

(deftest loaded-results-replace-the-list
  (let [{:keys [state]} (run-all ready [:search/typed "blues"] [:search/run] [:search/loaded 1 [blues]])]
    (is (= :done (get-in state [:search :status])))
    (is (= [blues] (get-in state [:search :results])))
    (is (= [jazz blues] (get-in state [:search :top])) "a search does not overwrite the top cache")))

(deftest stale-responses-are-ignored
  (let [{:keys [state]} (run-all ready
                                 [:search/typed "ja"] [:search/run]
                                 [:search/typed "jazz"] [:search/run]
                                 [:search/loaded 1 [blues]])]
    (is (= :loading (get-in state [:search :status])))
    (is (= 2 (get-in state [:search :seq])))))

(deftest loaded-top-list-is-cached
  (let [state (assoc-in store/initial-state [:api] {:servers ["s1"] :current 0})
        {:keys [state]} (run-all state [:search/run] [:search/loaded 1 [jazz]])]
    (is (= [jazz] (get-in state [:search :top])))))

(deftest failure-retries-once-on-the-next-server
  (let [{:keys [state effects]} (run-all ready [:search/typed "jazz"] [:search/run] [:search/failed 1 5000])]
    (is (= 1 (get-in state [:api :current])))
    (is (true? (get-in state [:search :retried?])))
    (is (some #{[:api/fetch {:server "s2" :query "jazz" :seq 1}]} effects))
    (is (= :loading (get-in state [:search :status])))))

(deftest second-failure-shows-the-error
  (let [{:keys [state]} (run-all ready [:search/typed "jazz"] [:search/run]
                                 [:search/failed 1 5000] [:search/failed 1 6000])]
    (is (= :error (get-in state [:search :status])))
    (is (= [:error/network {:at 6000}] (get-in state [:search :error])))))

(deftest open-close-and-modes
  (let [{:keys [state effects]} (a/handle (assoc-in ready [:search :open?] false) [:search/open])]
    (is (true? (get-in state [:search :open?])))
    (is (= [[:dom/focus :search-input]] effects)))
  (testing "close is refused while nothing has been chosen"
    (is (true? (get-in (:state (a/handle ready [:search/close])) [:search :open?]))))
  (testing "close works once a station exists"
    (let [{:keys [state effects]} (a/handle (assoc-in ready [:player :station] jazz) [:search/close])]
      (is (false? (get-in state [:search :open?])))
      (is (= [[:dom/focus :opener]] effects))))
  (let [{:keys [state effects]} (a/handle ready [:search/mode :normal])]
    (is (= :normal (get-in state [:search :mode])))
    (is (= [[:dom/focus :search-list]] effects))))

(deftest moving-the-highlight-stays-in-bounds
  (is (= 1 (get-in (:state (run-all ready [:search/move 1] [:search/move 1] [:search/move 1])) [:search :highlight])))
  (is (= 0 (get-in (:state (a/handle ready [:search/move -1])) [:search :highlight])))
  (is (= 1 (get-in (:state (a/handle ready [:search/edge :last])) [:search :highlight])))
  (is (= 0 (get-in (:state (run-all ready [:search/edge :last] [:search/edge :first])) [:search :highlight])))
  (is (= [[:dom/scroll-highlight]] (:effects (a/handle ready [:search/move 1])))))

(deftest enter-runs-a-pending-search
  (let [{:keys [effects]} (run-all ready [:search/typed "jazz"] [:search/enter])]
    (is (some #(= :api/fetch (first %)) effects))))
```

- [ ] **Step 3: Run it to see it fail**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: non-zero exit; `The required namespace "radiolisp.frontend.actions" is not available`.

- [ ] **Step 4: Implement `frontend/src/radiolisp/frontend/actions.cljs`**

This task writes the app and search actions. Task 7 appends the player and audio actions to the same file.

```clojure
(ns radiolisp.frontend.actions
  "Every state change in radiolisp. `handle` is pure: it takes the state and
  an action vector and returns `{:state next-state :effects [effect ...]}`.
  Effects are data; `radiolisp.frontend.effects` runs them."
  (:require [clojure.string :as str]))

(def min-query-length 2)
(def debounce-ms 300)
(def connect-timeout-ms 10000)

(defn query-active?
  "True when `query` is long enough to search for."
  [query]
  (>= (count (str/trim (or query ""))) min-query-length))

(defn visible-stations
  "The stations the list shows right now."
  [{:keys [status results]}]
  (if (= :done status) results []))

(defn- clamp [lo hi n]
  (max lo (min hi n)))

(defmulti handle (fn [_state [action-type]] action-type))

(defn- ok
  ([state] {:state state :effects []})
  ([state effects] {:state state :effects (vec effects)}))

(defn- then
  "Runs `action` on the state of `result`, appending its effects."
  [{:keys [state effects]} action]
  (let [next-result (handle state action)]
    {:state   (:state next-result)
     :effects (into effects (:effects next-result))}))

(defmethod handle :default [state action]
  (ok state [[:log/warn "Unhandled action" action]]))

;; ---------------------------------------------------------------- app

(defn- volume-effect [{:keys [volume muted?]}]
  [:audio/volume (if muted? 0 (/ volume 100))])

(defmethod handle :app/init [state [_ {:keys [locale]}]]
  (ok (assoc state :locale locale)
      [[:dom/lang locale]
       (volume-effect (:player state))
       [:api/servers]
       [:dom/focus :search-input]]))

(defmethod handle :api/servers-loaded [state [_ servers]]
  (-> (ok (assoc state :api {:servers (vec servers) :current 0}))
      (then [:search/run])))

;; ---------------------------------------------------------------- search

(defn- current-server [state]
  (let [{:keys [servers current]} (:api state)]
    (get servers current)))

(defn- fetch-effect [state request]
  (let [query (get-in state [:search :query])]
    [:api/fetch {:server (current-server state)
                 :query  (when (query-active? query) (str/trim query))
                 :seq    request}]))

(defmethod handle :search/open [state _]
  (ok (update state :search assoc :open? true :mode :insert)
      [[:dom/focus :search-input]]))

(defmethod handle :search/close [state _]
  (if (get-in state [:player :station])
    (ok (assoc-in state [:search :open?] false) [[:dom/focus :opener]])
    (ok state)))

(defmethod handle :search/mode [state [_ mode]]
  (ok (assoc-in state [:search :mode] mode)
      [[:dom/focus (if (= mode :normal) :search-list :search-input)]]))

(defmethod handle :search/typed [state [_ query]]
  (let [state (update state :search assoc :query query :highlight 0)
        top   (get-in state [:search :top])]
    (cond
      (query-active? query)
      (ok (assoc-in state [:search :status] :loading)
          [[:timer/start :search debounce-ms [:search/run]]])

      (seq top)
      (ok (update state :search
                  (fn [search]
                    (-> search
                        (assoc :status :done :results top :error nil)
                        (update :seq inc))))
          [[:timer/cancel :search]])

      :else
      (-> (ok state [[:timer/cancel :search]])
          (then [:search/run])))))

(defmethod handle :search/run [state _]
  (let [request (inc (get-in state [:search :seq]))
        state   (update state :search assoc :status :loading :seq request :retried? false)]
    (if (current-server state)
      (ok state [[:timer/cancel :search] (fetch-effect state request)])
      (ok state))))

(defmethod handle :search/loaded [state [_ request stations]]
  (if (not= request (get-in state [:search :seq]))
    (ok state)
    (let [top? (not (query-active? (get-in state [:search :query])))]
      (ok (update state :search
                  (fn [search]
                    (cond-> (assoc search :status :done :results (vec stations)
                                   :highlight 0 :error nil :retried? false)
                      top? (assoc :top (vec stations)))))))))

(defmethod handle :search/failed [state [_ request at]]
  (let [{:keys [servers current]} (:api state)
        search                    (:search state)]
    (cond
      (not= request (:seq search))
      (ok state)

      (and (not (:retried? search)) (> (count servers) 1))
      (let [state (-> state
                      (assoc-in [:api :current] (mod (inc current) (count servers)))
                      (assoc-in [:search :retried?] true))]
        (ok state [(fetch-effect state request)]))

      :else
      (ok (update state :search assoc :status :error :retried? false
                  :error [:error/network {:at at}])))))

(defmethod handle :search/enter [state _]
  (if (= :done (get-in state [:search :status]))
    (handle state [:search/choose])
    (handle state [:search/run])))

(defmethod handle :search/move [state [_ delta]]
  (let [n (count (visible-stations (:search state)))]
    (if (zero? n)
      (ok state)
      (ok (update-in state [:search :highlight] #(clamp 0 (dec n) (+ % delta)))
          [[:dom/scroll-highlight]]))))

(defmethod handle :search/edge [state [_ edge]]
  (let [n (count (visible-stations (:search state)))]
    (if (zero? n)
      (ok state)
      (ok (assoc-in state [:search :highlight] (if (= edge :last) (dec n) 0))
          [[:dom/scroll-highlight]]))))

(defmethod handle :keys/g [state [_ now]]
  (ok (assoc-in state [:keys :last-g] now)))
```

`[:search/choose]` is used by `:search/enter` but defined in Task 7; until then the `:default` method answers with a `:log/warn` effect, which no test in this task asserts against.

- [ ] **Step 5: Run the tests**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: `exit=0`, `0 failures, 0 errors.`

- [ ] **Step 6: Lint**

Run: `bb lint`
Expected: `errors: 0, warnings: 0`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/radiolisp/frontend/store.cljs frontend/src/radiolisp/frontend/actions.cljs frontend/test/radiolisp/frontend/actions_search_test.cljs
git commit -m "feat(frontend): add store and search actions"
```

---

### Task 7: Player, audio and misc actions

**Files:**
- Modify: `frontend/src/radiolisp/frontend/actions.cljs` (append at the end)
- Test: `frontend/test/radiolisp/frontend/actions_player_test.cljs`

- [ ] **Step 1: Write the failing test**

```clojure
(ns radiolisp.frontend.actions-player-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.store :as store]))

(def jazz {:id "j1" :name "Jazz Radio" :url "http://jazz.example/stream" :hls? false})
(def blues {:id "b1" :name "Blues FM" :url "http://blues.example/stream" :hls? false})
(def hls-fm {:id "h1" :name "HLS FM" :url "https://hls.example/live.m3u8" :hls? true})

(def searching
  (-> store/initial-state
      (assoc :api {:servers ["s1"] :current 0})
      (update :search assoc :status :done :results [jazz blues hls-fm] :top [jazz blues hls-fm])))

(defn- run-all [state & actions]
  (reduce (fn [{:keys [state effects]} action]
            (let [r (a/handle state action)]
              {:state (:state r) :effects (into effects (:effects r))}))
          {:state state :effects []}
          actions))

(def playing-jazz
  (:state (run-all searching [:search/choose 0] [:audio/playing 1000])))

(deftest choosing-a-station-plays-it-and-closes-search
  (let [{:keys [state effects]} (a/handle searching [:search/choose 1])]
    (is (= blues (get-in state [:player :station])))
    (is (= :connecting (get-in state [:player :status])))
    (is (false? (get-in state [:search :open?])))
    (is (= [[:audio/play "http://blues.example/stream"]
            [:timer/start :connect 10000 [:audio/timeout]]
            [:dom/focus :toggle]]
           effects))))

(deftest choose-without-index-uses-the-highlight
  (let [{:keys [state]} (run-all searching [:search/move 1] [:search/choose])]
    (is (= blues (get-in state [:player :station])))))

(deftest choosing-the-station-that-plays-only-closes-search
  (let [state (assoc-in playing-jazz [:search :open?] true)
        {:keys [state effects]} (a/handle state [:search/choose 0])]
    (is (= :playing (get-in state [:player :status])))
    (is (false? (get-in state [:search :open?])))
    (is (= [[:dom/focus :toggle]] effects))))

(deftest hls-stations-are-refused
  (let [{:keys [state effects]} (a/handle searching [:search/choose 2])]
    (is (= :error (get-in state [:player :status])))
    (is (= [:error/unsupported {}] (get-in state [:player :error])))
    (is (some #{[:audio/stop]} effects))))

(deftest enter-chooses-when-results-are-shown
  (let [{:keys [state]} (a/handle searching [:search/enter])]
    (is (= jazz (get-in state [:player :station])))))

(deftest playing-event-starts-the-clock-once
  (is (= :playing (get-in playing-jazz [:player :status])))
  (is (= 1000 (get-in playing-jazz [:player :started-at])))
  (is (= 1000 (:now playing-jazz)))
  (let [{:keys [state]} (run-all playing-jazz [:player/toggle] [:player/toggle] [:audio/playing 9000])]
    (is (= 1000 (get-in state [:player :started-at])) "resuming keeps the clock")))

(deftest toggle-pauses-and-resumes
  (let [{:keys [state effects]} (a/handle playing-jazz [:player/toggle])]
    (is (= :paused (get-in state [:player :status])))
    (is (= [[:audio/pause] [:timer/cancel :connect]] effects))
    (let [{:keys [state effects]} (a/handle state [:player/toggle])]
      (is (= :connecting (get-in state [:player :status])))
      (is (= [:audio/play "http://jazz.example/stream"] (first effects))))))

(deftest toggle-does-nothing-without-a-station
  (is (= {:state searching :effects []} (a/handle searching [:player/toggle]))))

(deftest volume-is-clamped-and-unmutes
  (let [{:keys [state effects]} (a/handle (assoc-in searching [:player :muted?] true) [:player/volume 140])]
    (is (= 100 (get-in state [:player :volume])))
    (is (false? (get-in state [:player :muted?])))
    (is (= [[:audio/volume 1]] effects)))
  (is (= 0 (get-in (:state (a/handle searching [:player/volume -3])) [:player :volume])))
  (is (= 65 (get-in (:state (a/handle searching [:player/volume-step -5])) [:player :volume]))))

(deftest mute-toggles-the-audio-volume
  (let [{:keys [state effects]} (a/handle searching [:player/mute])]
    (is (true? (get-in state [:player :muted?])))
    (is (= [[:audio/volume 0]] effects))
    (is (= [[:audio/volume 0.7]] (:effects (a/handle state [:player/mute]))))))

(deftest audio-events
  (testing "an outside pause (media keys) is reflected"
    (is (= :paused (get-in (:state (a/handle playing-jazz [:audio/paused])) [:player :status]))))
  (testing "buffering goes back to connecting and arms the timeout"
    (let [{:keys [state effects]} (a/handle playing-jazz [:audio/waiting])]
      (is (= :connecting (get-in state [:player :status])))
      (is (= [[:timer/start :connect 10000 [:audio/timeout]]] effects))))
  (testing "a stream error is shown"
    (let [state (:state (a/handle playing-jazz [:audio/failed]))]
      (is (= :error (get-in state [:player :status])))
      (is (= [:error/stream {}] (get-in state [:player :error])))))
  (testing "the timeout only fires while connecting"
    (is (= :playing (get-in (:state (a/handle playing-jazz [:audio/timeout])) [:player :status])))
    (let [connecting (:state (a/handle searching [:search/choose 0]))]
      (is (= :error (get-in (:state (a/handle connecting [:audio/timeout])) [:player :status])))))
  (testing "errors never overwrite an unsupported station"
    (let [unsupported (:state (a/handle searching [:search/choose 2]))]
      (is (= [:error/unsupported {}] (get-in (:state (a/handle unsupported [:audio/failed])) [:player :error]))))))

(deftest misc-actions
  (is (= 42 (:now (:state (a/handle searching [:clock/tick 42])))))
  (let [{:keys [state effects]} (a/handle searching [:i18n/set :es])]
    (is (= :es (:locale state)))
    (is (= [[:dom/lang :es]] effects)))
  (let [{:keys [state effects]} (a/handle searching [:help/open])]
    (is (true? (:help? state)))
    (is (= [[:dom/show-help]] effects))
    (is (false? (:help? (:state (a/handle state [:help/close])))))))
```

- [ ] **Step 2: Run it to see it fail**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: non-zero exit; failures in `actions-player-test` (for example `choosing-a-station-plays-it-and-closes-search` gets `[[:log/warn "Unhandled action" …]]`).

- [ ] **Step 3: Append to `frontend/src/radiolisp/frontend/actions.cljs`**

```clojure
;; ---------------------------------------------------------------- player

(defn- start-playback
  "Connects to the station in the player and arms the connect timeout."
  [state]
  (ok (update state :player assoc :status :connecting :error nil)
      [[:audio/play (get-in state [:player :station :url])]
       [:timer/start :connect connect-timeout-ms [:audio/timeout]]]))

(defmethod handle :search/choose [state [_ index]]
  (let [{:keys [highlight] :as search} (:search state)
        station (get (visible-stations search) (or index highlight))
        player  (:player state)
        closed  (assoc-in state [:search :open?] false)]
    (cond
      (nil? station)
      (ok state)

      (and (= (:id station) (get-in player [:station :id]))
           (#{:playing :connecting} (:status player)))
      (ok closed [[:dom/focus :toggle]])

      (:hls? station)
      (ok (update closed :player assoc :station station :status :error
                  :error [:error/unsupported {}] :started-at nil)
          [[:audio/stop] [:timer/cancel :connect] [:dom/focus :toggle]])

      :else
      (-> (start-playback (update closed :player assoc :station station :started-at nil))
          (update :effects conj [:dom/focus :toggle])))))

(defmethod handle :player/toggle [state _]
  (let [{:keys [station status]} (:player state)]
    (cond
      (or (nil? station) (:hls? station))
      (ok state)

      (#{:playing :connecting} status)
      (ok (assoc-in state [:player :status] :paused)
          [[:audio/pause] [:timer/cancel :connect]])

      :else
      (start-playback state))))

(defmethod handle :player/volume [state [_ volume]]
  (let [player (assoc (:player state)
                      :volume (clamp 0 100 (js/Math.round volume))
                      :muted? false)]
    (ok (assoc state :player player) [(volume-effect player)])))

(defmethod handle :player/volume-step [state [_ delta]]
  (handle state [:player/volume (+ (get-in state [:player :volume]) delta)]))

(defmethod handle :player/mute [state _]
  (let [player (update (:player state) :muted? not)]
    (ok (assoc state :player player) [(volume-effect player)])))

;; ---------------------------------------------------------------- audio element

(defn- status-in? [state statuses]
  (contains? statuses (get-in state [:player :status])))

(defmethod handle :audio/playing [state [_ now]]
  (ok (-> state
          (assoc :now now)
          (update :player (fn [player]
                            (cond-> (assoc player :status :playing :error nil)
                              (nil? (:started-at player)) (assoc :started-at now)))))
      [[:timer/cancel :connect]]))

(defmethod handle :audio/paused [state _]
  (if (status-in? state #{:playing})
    (ok (assoc-in state [:player :status] :paused))
    (ok state)))

(defmethod handle :audio/waiting [state _]
  (if (status-in? state #{:playing})
    (ok (assoc-in state [:player :status] :connecting)
        [[:timer/start :connect connect-timeout-ms [:audio/timeout]]])
    (ok state)))

(defmethod handle :audio/failed [state _]
  (if (status-in? state #{:playing :connecting})
    (ok (update state :player assoc :status :error :error [:error/stream {}])
        [[:timer/cancel :connect]])
    (ok state)))

(defmethod handle :audio/timeout [state _]
  (if (status-in? state #{:connecting})
    (ok (update state :player assoc :status :error :error [:error/stream {}])
        [[:audio/stop]])
    (ok state)))

;; ---------------------------------------------------------------- misc

(defmethod handle :clock/tick [state [_ now]]
  (ok (assoc state :now now)))

(defmethod handle :i18n/set [state [_ locale]]
  (ok (assoc state :locale locale) [[:dom/lang locale]]))

(defmethod handle :help/open [state _]
  (ok (assoc state :help? true) [[:dom/show-help]]))

(defmethod handle :help/close [state _]
  (ok (assoc state :help? false)))
```

- [ ] **Step 4: Run the tests**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: `exit=0`, `0 failures, 0 errors.`

- [ ] **Step 5: Lint and format**

Run: `bb lint; bb fmt:check`
Expected: no errors or warnings. If `fmt:check` complains, run `bb fmt` and re-run the tests.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/radiolisp/frontend/actions.cljs frontend/test/radiolisp/frontend/actions_player_test.cljs
git commit -m "feat(frontend): add player, audio and misc actions"
```

---

### Task 8: Keymap

**Files:**
- Create: `frontend/src/radiolisp/frontend/keymap.cljs`
- Test: `frontend/test/radiolisp/frontend/keymap_test.cljs`

- [ ] **Step 1: Write the failing test**

```clojure
(ns radiolisp.frontend.keymap-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.keymap :as km]))

(def station {:id "j1"})

(def insert-mode {:help? false :player {:station nil} :search {:open? true :mode :insert} :keys {:last-g 0}})
(def normal-mode (assoc-in insert-mode [:search :mode] :normal))
(def closed (-> insert-mode (assoc-in [:search :open?] false) (assoc-in [:player :station] station)))

(defn- press
  ([state key] (press state key {}))
  ([state key opts]
   (km/key->action state (merge {:key key :ctrl? false :alt? false :meta? false :target :other :now 10000}
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
```

- [ ] **Step 2: Run it to see it fail**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: non-zero exit; `The required namespace "radiolisp.frontend.keymap" is not available`.

- [ ] **Step 3: Implement `frontend/src/radiolisp/frontend/keymap.cljs`**

```clojure
(ns radiolisp.frontend.keymap
  "Keyboard map, vim style. Pure: the state plus a description of the key
  press give the action to dispatch, or nil to let the browser handle it.

  Modes: insert (typing in the search input) and normal (browsing the list).
  Global keys work outside the input.")

(def gg-window-ms 500)

(defn- global-action [{:keys [search player]} {:keys [key target]}]
  (let [open? (:open? search)]
    (case key
      "/"      (if open? [:search/mode :insert] [:search/open])
      " "      (when-not (= target :button) [:player/toggle])
      "h"      [:player/volume-step -5]
      "l"      [:player/volume-step 5]
      "m"      [:player/mute]
      "?"      [:help/open]
      "Escape" (when (and open? (:station player)) [:search/close])
      nil)))

(defn- input-action [{:keys [key]}]
  (case key
    "ArrowDown" [:search/move 1]
    "ArrowUp"   [:search/move -1]
    "Enter"     [:search/enter]
    "Escape"    [:search/mode :normal]
    nil))

(defn- list-action [state {:keys [key now]}]
  (case key
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
  [{:keys [help? search] :as state} {:keys [key ctrl? alt? meta? target] :as press}]
  (let [open?    (:open? search)
        browsing (and open? (= :normal (:mode search)))]
    (cond
      (or help? alt? meta?)            nil
      (and open? ctrl? (#{"j" "n"} key)) [:search/move 1]
      (and open? ctrl? (#{"k" "p"} key)) [:search/move -1]
      ctrl?                            nil
      (= target :search-input)         (input-action press)
      (= target :slider)               nil
      browsing                         (or (list-action state press) (global-action state press))
      :else                            (global-action state press))))
```

- [ ] **Step 4: Run the tests**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: `exit=0`, `0 failures, 0 errors.`

- [ ] **Step 5: Lint, format, commit**

Run: `bb lint; bb fmt:check` (run `bb fmt` if needed). Then:

```bash
git add frontend/src/radiolisp/frontend/keymap.cljs frontend/test/radiolisp/frontend/keymap_test.cljs
git commit -m "feat(frontend): add vim-style keymap"
```

---

### Task 9: Effects layer (API, audio, effects)

No unit tests: this is the impure edge. It is checked in Task 12 in the browser.

**Files:**
- Create: `frontend/src/radiolisp/frontend/api.cljs`
- Create: `frontend/src/radiolisp/frontend/audio.cljs`
- Create: `frontend/src/radiolisp/frontend/effects.cljs`

- [ ] **Step 1: `frontend/src/radiolisp/frontend/api.cljs`**

```clojure
(ns radiolisp.frontend.api
  "Radio Browser over HTTP. Returns promises of plain Clojure data."
  (:require [radiolisp.common.station :as station]))

(def app-id "radiolisp/0.1.0")
(def servers-url "https://all.api.radio-browser.info/json/servers")
(def fallback-servers ["de1.api.radio-browser.info" "de2.api.radio-browser.info"])
(def search-limit 50)
(def top-limit 20)

(defonce ^:private in-flight (atom nil))

(defn- get-json [url ^js signal]
  ;; Radio Browser asks clients to identify themselves; its CORS policy only
  ;; lets browsers send X-Requested-With for that.
  (-> (js/fetch url #js {:headers #js {"X-Requested-With" app-id} :signal signal})
      (.then (fn [^js res]
               (if (.-ok res)
                 (.json res)
                 (throw (ex-info "Radio Browser answered with an error" {:status (.-status res)})))))))

(defn fetch-servers
  "Promise of a shuffled vector of server host names. Never rejects: on any
  failure it resolves to `fallback-servers`."
  []
  (-> (get-json servers-url nil)
      (.then (fn [servers]
               (let [names (->> (js->clj servers) (map #(get % "name")) distinct vec)]
                 (if (seq names) (shuffle names) fallback-servers))))
      (.catch (fn [_] fallback-servers))))

(defn stations-url
  "Search by name when `query` is given, otherwise today's most played."
  [server query]
  (if query
    (str "https://" server "/json/stations/search?"
         (js/URLSearchParams. #js {:name query :hidebroken "true" :order "clickcount"
                                   :reverse "true" :limit search-limit}))
    (str "https://" server "/json/stations/topclick/" top-limit "?hidebroken=true")))

(defn fetch-stations
  "Promise of normalized stations. Aborts the previous request still in flight."
  [server query]
  (some-> ^js @in-flight .abort)
  (let [controller (js/AbortController.)]
    (reset! in-flight controller)
    (-> (get-json (stations-url server query) (.-signal controller))
        (.then (fn [stations] (mapv station/normalize (js->clj stations)))))))

(defn aborted? [^js error]
  (= "AbortError" (.-name error)))
```

- [ ] **Step 2: `frontend/src/radiolisp/frontend/audio.cljs`**

```clojure
(ns radiolisp.frontend.audio
  "The one <audio> element. Lives outside the Replicant tree; its events come
  back into the app as actions.")

(defonce ^:private element (atom nil))

(defn init!
  "Creates the element and wires its events to `dispatch`."
  [dispatch]
  (let [audio (js/Audio.)]
    (set! (.-preload audio) "none")
    (.addEventListener audio "playing" #(dispatch [:audio/playing (js/Date.now)]))
    (.addEventListener audio "pause" #(dispatch [:audio/paused]))
    (.addEventListener audio "waiting" #(dispatch [:audio/waiting]))
    (.addEventListener audio "error" #(dispatch [:audio/failed]))
    (reset! element audio)))

(defn play! [url]
  (let [^js audio @element]
    (when (not= (.-src audio) url)
      (set! (.-src audio) url))
    ;; Failures arrive through the "error" event; the rejection adds nothing.
    (-> (.play audio) (.catch (fn [_] nil)))))

(defn pause! []
  (.pause ^js @element))

(defn stop! []
  (let [^js audio @element]
    (.pause audio)
    (.removeAttribute audio "src")
    (.load audio)))

(defn set-volume! [volume]
  (set! (.-volume ^js @element) volume))
```

- [ ] **Step 3: `frontend/src/radiolisp/frontend/effects.cljs`**

```clojure
(ns radiolisp.frontend.effects
  "The only impure layer. `execute!` runs one effect vector; results come
  back as actions through `dispatch`."
  (:require [radiolisp.frontend.api :as api]
            [radiolisp.frontend.audio :as audio]))

(def focus-targets
  {:search-input "#search-input"
   :search-list  "#search-list"
   :opener       "#search-opener"
   :toggle       "#player-toggle"})

(defonce ^:private timers (atom {}))

(defmulti execute! (fn [_dispatch [effect-type]] effect-type))

(defmethod execute! :default [_ effect]
  (js/console.warn "Unknown effect" (pr-str effect)))

(defmethod execute! :log/warn [_ [_ message data]]
  (js/console.warn message (pr-str data)))

;; timers: one per id; starting an id again replaces it

(defn- cancel-timer! [id]
  (js/clearTimeout (get @timers id))
  (swap! timers dissoc id))

(defmethod execute! :timer/start [dispatch [_ id ms action]]
  (cancel-timer! id)
  (swap! timers assoc id (js/setTimeout #(do (swap! timers dissoc id) (dispatch action)) ms)))

(defmethod execute! :timer/cancel [_ [_ id]]
  (cancel-timer! id))

;; Radio Browser

(defmethod execute! :api/servers [dispatch _]
  (-> (api/fetch-servers)
      (.then #(dispatch [:api/servers-loaded %]))))

(defmethod execute! :api/fetch [dispatch [_ {:keys [server query] request :seq}]]
  (-> (api/fetch-stations server query)
      (.then #(dispatch [:search/loaded request %]))
      (.catch (fn [error]
                (when-not (api/aborted? error)
                  (dispatch [:search/failed request (js/Date.now)]))))))

;; audio

(defmethod execute! :audio/play [_ [_ url]] (audio/play! url))
(defmethod execute! :audio/pause [_ _] (audio/pause!))
(defmethod execute! :audio/stop [_ _] (audio/stop!))
(defmethod execute! :audio/volume [_ [_ volume]] (audio/set-volume! volume))

;; DOM. The render runs synchronously before effects, so the nodes exist.

(defmethod execute! :dom/focus [_ [_ target]]
  (some-> (js/document.querySelector (focus-targets target)) (.focus)))

(defmethod execute! :dom/scroll-highlight [_ _]
  (some-> (js/document.querySelector "#search-list [aria-selected=true]")
          (.scrollIntoView #js {:block "nearest"})))

(defmethod execute! :dom/lang [_ [_ locale]]
  (set! (.-lang js/document.documentElement) (name locale)))

(defmethod execute! :dom/show-help [_ _]
  (let [^js dialog (js/document.getElementById "help")]
    (when-not (.-open dialog) (.showModal dialog))))
```

- [ ] **Step 4: Compile check**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file.
Expected: `exit=0`. The new namespaces are not required by tests yet; this checks nothing broke. Then run `bb lint` and expect `errors: 0, warnings: 0`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/radiolisp/frontend/api.cljs frontend/src/radiolisp/frontend/audio.cljs frontend/src/radiolisp/frontend/effects.cljs
git commit -m "feat(frontend): add effects layer for Radio Browser, audio and DOM"
```

---

### Task 10: Views and wiring

**Files:**
- Create: `frontend/src/radiolisp/frontend/views/icons.cljs`
- Create: `frontend/src/radiolisp/frontend/views/station.cljs`
- Create: `frontend/src/radiolisp/frontend/views/player.cljs`
- Create: `frontend/src/radiolisp/frontend/views/search.cljs`
- Create: `frontend/src/radiolisp/frontend/views/help.cljs`
- Create: `frontend/src/radiolisp/frontend/views/shell.cljs`
- Replace: `frontend/src/radiolisp/frontend/main.cljs`

Replicant facts used here (checked against its source):
- `replicant.dom/set-dispatch!` takes `(fn [event-data handler] …)`. `event-data` has `:replicant/trigger` and `:replicant/dom-event`.
- An attribute whose value is `false` or `nil` is removed, so `{:disabled false}` is fine.
- `:class` accepts a collection and skips `nil` entries.
- `:value` is set as a DOM property.

Event handlers in this app are always a **vector of actions**: `{:on {:click [[:player/toggle]]}}`. `main` replaces the placeholders `:event.target/value` and `:event.target/value-as-number` before dispatching.

- [ ] **Step 1: `views/icons.cljs`**

```clojure
(ns radiolisp.frontend.views.icons
  "Inline SVG icons as hiccup.")

(def play
  [:svg {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true"}
   [:path {:d "M4 2.5v11l9-5.5z"}]])

(def pause
  [:svg {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true"}
   [:rect {:x "3.5" :y "2.5" :width "3" :height "11"}]
   [:rect {:x "9.5" :y "2.5" :width "3" :height "11"}]])

(def volume
  [:svg {:viewBox "0 0 18 18" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:path {:d "M3 7h3l4-3v10l-4-3H3z"}]
   [:path {:d "M13 6.5a3.5 3.5 0 010 5M15 4.5a6.5 6.5 0 010 9"}]])

(def mute
  [:svg {:viewBox "0 0 18 18" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:path {:d "M3 7h3l4-3v10l-4-3H3z"}]
   [:path {:d "M13 7l4 4M17 7l-4 4"}]])

(def search
  [:svg {:viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:circle {:cx "7" :cy "7" :r "5"}]
   [:path {:d "M11 11l3.5 3.5"}]])

(def close
  [:svg {:viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:path {:d "M3 3l10 10M13 3L3 13"}]])

(def mark
  "The radiolisp mark: Lisp parentheses drawn as radio waves around a dot."
  [:svg.mark {:viewBox "0 0 28 20" :fill "none" :stroke "currentColor" :stroke-width "2" :aria-hidden "true"}
   [:path {:d "M6 2 Q1 10 6 18"}]
   [:path {:d "M10 5 Q7 10 10 15"}]
   [:rect {:x "12" :y "8" :width "4" :height "4" :fill "currentColor" :stroke "none"}]
   [:path {:d "M18 5 Q21 10 18 15"}]
   [:path {:d "M22 2 Q27 10 22 18"}]])

(def waves
  "The mark's waves; they pulse while a station plays (see _player.scss)."
  [:svg.waves {:viewBox "0 0 22 16" :fill "none" :stroke "currentColor" :stroke-width "1.75" :aria-hidden "true"}
   [:path.w2 {:d "M4 1.5 Q0 8 4 14.5"}]
   [:path.w1 {:d "M7.5 4 Q5 8 7.5 12"}]
   [:rect {:x "9.5" :y "6.5" :width "3" :height "3" :fill "currentColor" :stroke "none"}]
   [:path.w1 {:d "M14.5 4 Q17 8 14.5 12"}]
   [:path.w2 {:d "M18 1.5 Q22 8 18 14.5"}]])
```

- [ ] **Step 2: `views/station.cljs`**

```clojure
(ns radiolisp.frontend.views.station
  "Pieces of a station shared by the player and the list."
  (:require [clojure.string :as str]))

(defn format-badge
  "\"MP3 192 kbps\", or a dash when Radio Browser does not know."
  [{:keys [codec bitrate]}]
  (if (and codec bitrate)
    [:span.format [:span.codec codec " "] [:span.num bitrate] " " [:span.unit "kbps"]]
    [:span.dash "—"]))

(defn tags-line [t {:keys [tags]}]
  (if (seq tags)
    (str/join " · " tags)
    [:span.dash (t :station/no-tags)]))

(defn country [{:keys [country]}]
  (or country "—"))
```

- [ ] **Step 3: `views/player.cljs`**

```clojure
(ns radiolisp.frontend.views.player
  (:require [radiolisp.common.format :as fmt]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.station :as sv]))

(defn- status-line [t {:keys [status error started-at]} now]
  (case status
    :error      [:div.status.err (t (first error))]
    :connecting [:div.status icons/waves [:span (t :player/connecting)]]
    :playing    [:div.status icons/waves
                 [:span.live (t :player/live)]
                 [:span.num.clock (fmt/clock (fmt/elapsed started-at now))]]
    :paused     [:div.status icons/waves [:span (t :player/paused)]]
    nil))

(defn view [{:keys [player now]} t]
  (let [{:keys [station status volume muted?]} player
        active? (contains? #{:playing :connecting} status)
        shown   (if muted? 0 volume)]
    [:section#player.player {:class     [(when (= :playing status) "is-playing")
                                         (when-not station "empty")]
                             :aria-live "polite"}
     (when station (status-line t player now))
     [:h1 {:title (:name station)}
      (if station (:name station) (t :player/idle-title))]
     [:p.facts
      (if station
        (list (sv/country station) " · " (sv/format-badge station) " · " (sv/tags-line t station))
        (t :player/idle-hint))]
     [:div.controls
      [:button#player-toggle.toggle
       {:disabled   (nil? station)
        :aria-label (t (if active? :player/pause :player/play) {:name (:name station "")})
        :on         {:click [[:player/toggle]]}}
       (if active? icons/pause icons/play)]
      [:button.icon-btn.mute
       {:aria-label (t (if muted? :player/unmute :player/mute))
        :on         {:click [[:player/mute]]}}
       (if muted? icons/mute icons/volume)]
      [:div.slider
       [:span.track]
       [:span.fill {:style {:width (str shown "%")}}]
       [:span.thumb {:style {:left (str "calc(" shown "% - 6px)")}}]
       [:input {:type       "range" :min "0" :max "100" :value (str shown)
                :aria-label (t :player/volume)
                :on         {:input [[:player/volume :event.target/value-as-number]]}}]]
      [:span.vol-val.num (str shown " %")]]]))
```

- [ ] **Step 4: `views/search.cljs`**

```clojure
(ns radiolisp.frontend.views.search
  (:require [clojure.string :as str]
            [radiolisp.frontend.actions :as actions]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.station :as sv]))

(defn- list-head [t {:keys [mode]} label]
  [:div.list-head
   [:span label]
   [:span.mode (t (if (= :normal mode) :search/mode-normal :search/mode-insert))]])

(defn- station-row [t {:keys [highlight]} playing-id index station]
  [:li.st {:id            (str "st-" index)
           :replicant/key (:id station)
           :role          "option"
           :aria-selected (str (= index highlight))
           :class         [(when (= index highlight) "hl")
                           (when (= playing-id (:id station)) "on")]
           :on            {:click [[:search/choose index]]}}
   [:span.n {:title (:name station)} (:name station)]
   [:span.f (sv/format-badge station)]
   [:span.m (sv/country station) " · "
    (t :station/listens {:count (:clicks station)}) " · "
    (sv/tags-line t station)]])

(defn- skeleton-rows []
  [:ul.results
   (for [i (range 5)]
     [:li.st.skeleton {:replicant/key i}
      [:span.skel {:style {:width "55%"}}]
      [:span.skel {:style {:width "56px"}}]
      [:span.m [:span.skel {:style {:width "35%"}}]]])])

(defn- body [{:keys [search player locale]} t]
  (let [{:keys [query status error highlight]} search
        stations (actions/visible-stations search)
        active?  (actions/query-active? query)]
    (cond
      (= :error status)
      (let [[message {:keys [at]}] error]
        [:div.note.error
         [:span [:span.dot] (t message {:time (i18n/format-time locale at)})]
         [:button.text-btn {:on {:click [[:search/run]]}} (t :error/retry)]])

      (= :loading status)
      (list (list-head t search (t :search/loading)) (skeleton-rows))

      (and active? (empty? stations))
      [:p.note (t :search/empty {:query (str/trim query)})]

      :else
      (list
       (list-head t search (if active?
                             (str (t :search/results {:count (count stations)}) " · " (t :search/order))
                             (t :search/top)))
       [:ul#search-list.results
        {:role                  "listbox"
         :tabindex              "-1"
         :aria-label            (t :search/label)
         :aria-activedescendant (str "st-" highlight)
         :on                    {:focus [[:search/mode :normal]]}}
        (map-indexed (fn [i station] (station-row t search (get-in player [:station :id]) i station))
                     stations)]))))

(defn view [{:keys [search player] :as state} t]
  (if-not (:open? search)
    [:button#search-opener.opener {:on {:click [[:search/open]]}}
     icons/search [:span (t :search/open)]]
    [:section#panel.panel {:class      (when (= :normal (:mode search)) "normal")
                           :aria-label (t :search/label)}
     [:div.field
      [:label.vh {:for "search-input"} (t :search/label)]
      icons/search
      [:input#search-input {:type         "search"
                            :value        (:query search)
                            :placeholder  (t :search/placeholder)
                            :autocomplete "off"
                            :spellcheck   "false"
                            :on           {:input [[:search/typed :event.target/value]]
                                           :focus [[:search/mode :insert]]}}]
      (when (:station player)
        [:button.icon-btn.close {:aria-label (t :search/close)
                                 :on         {:click [[:search/close]]}}
         icons/close])]
     (body state t)]))
```

- [ ] **Step 5: `views/help.cljs`**

```clojure
(ns radiolisp.frontend.views.help
  (:require [radiolisp.frontend.views.icons :as icons]))

(defn- shortcut [t key-labels message]
  (list [:dt (for [k key-labels] [:kbd {:replicant/key k} k])]
        [:dd (t message)]))

(defn view [t]
  [:dialog#help {:on {:close [[:help/close]]}}
   [:h2 (t :help/title)]
   [:form {:method "dialog"}
    [:button.icon-btn.close {:aria-label (t :help/close)} icons/close]]
   [:h3 (t :help/global)]
   [:dl
    (shortcut t ["/"] :help/k-search)
    (shortcut t ["space"] :help/k-toggle)
    (shortcut t ["h" "l"] :help/k-volume)
    (shortcut t ["m"] :help/k-mute)
    (shortcut t ["?"] :help/k-help)]
   [:h3 (t :help/search)]
   [:dl
    (shortcut t ["↓" "↑"] :help/k-move)
    (shortcut t ["j" "k"] :help/k-move)
    (shortcut t ["ctrl j" "ctrl k"] :help/k-move)
    (shortcut t ["gg" "G"] :help/k-edges)
    (shortcut t ["enter"] :help/k-choose)
    (shortcut t ["esc"] :help/k-normal)
    (shortcut t ["i" "/"] :help/k-insert)]])
```

- [ ] **Step 6: `views/shell.cljs`**

```clojure
(ns radiolisp.frontend.views.shell
  (:require [clojure.string :as str]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.views.help :as help]
            [radiolisp.frontend.views.icons :as icons]
            [radiolisp.frontend.views.player :as player]
            [radiolisp.frontend.views.search :as search]))

(defn- translator
  "`t` bound to one locale, so views call `(t :key)` or `(t :key vars)`."
  [locale]
  (fn
    ([k] (i18n/t locale k))
    ([k vars] (i18n/t locale k vars))))

(defn- language-button [locale current]
  [:button {:lang         (name locale)
            :aria-pressed (str (= locale current))
            :on           {:click [[:i18n/set locale]]}}
   (str/upper-case (name locale))])

(defn app [{:keys [locale search] :as state}]
  (let [t (translator locale)]
    [:div.app {:class (when (:open? search) "searching")}
     [:a.brand {:href "/"} icons/mark [:b (t :brand/name)]]
     [:div.corner
      [:button {:on {:click [[:help/open]]}} (t :help/open) " " [:kbd "?"]]
      [:span {:role "group" :aria-label (t :lang/label)}
       (language-button :es locale) " · " (language-button :en locale)]]
     [:main.stage
      [:div.col
       (player/view state t)
       (search/view state t)]]
     (help/view t)]))
```

- [ ] **Step 7: Replace `frontend/src/radiolisp/frontend/main.cljs`**

```clojure
(ns radiolisp.frontend.main
  "Wiring: one dispatch function, the render loop, the keyboard, the clock."
  (:require [clojure.walk :as walk]
            [radiolisp.frontend.actions :as actions]
            [radiolisp.frontend.audio :as audio]
            [radiolisp.frontend.effects :as effects]
            [radiolisp.frontend.i18n :as i18n]
            [radiolisp.frontend.keymap :as keymap]
            [radiolisp.frontend.store :as store]
            [radiolisp.frontend.views.shell :as shell]
            [replicant.dom :as r]))

(defn dispatch!
  "Runs `action` through the pure core, stores the new state (which renders)
  and then executes the effects."
  [action]
  (let [{:keys [state effects]} (actions/handle @store/state action)]
    (reset! store/state state)
    (doseq [effect effects]
      (effects/execute! dispatch! effect))))

(defn- interpolate
  "Replaces event placeholders inside an action with values from the DOM event."
  [action ^js dom-event]
  (walk/postwalk
   (fn [x]
     (case x
       :event.target/value           (.. dom-event -target -value)
       :event.target/value-as-number (.. dom-event -target -valueAsNumber)
       x))
   action))

(defn- dispatch-dom-event! [event-data handler]
  (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
    (doseq [action handler]
      (dispatch! (interpolate action (:replicant/dom-event event-data))))))

(defn- target-kind [^js el]
  (cond
    (= "search-input" (.-id el))                               :search-input
    (and (= "INPUT" (.-tagName el)) (= "range" (.-type el)))   :slider
    (= "BUTTON" (.-tagName el))                                :button
    :else                                                      :other))

(defn- on-keydown [^js e]
  (when-let [action (keymap/key->action @store/state
                                        {:key    (.-key e)
                                         :ctrl?  (.-ctrlKey e)
                                         :alt?   (.-altKey e)
                                         :meta?  (.-metaKey e)
                                         :target (target-kind (.-target e))
                                         :now    (js/Date.now)})]
    (.preventDefault e)
    (dispatch! action)))

(defonce ^:private root (atom nil))

(defn- render! [state]
  (r/render @root (shell/app state)))

(defn ^:dev/after-load reload []
  (render! @store/state))

(defn init []
  (reset! root (js/document.getElementById "app"))
  (r/set-dispatch! dispatch-dom-event!)
  (add-watch store/state ::render (fn [_ _ _ state] (render! state)))
  (audio/init! dispatch!)
  (js/document.addEventListener "keydown" on-keydown)
  (js/setInterval #(when (= :playing (get-in @store/state [:player :status]))
                     (dispatch! [:clock/tick (js/Date.now)]))
                  1000)
  (render! @store/state)
  (dispatch! [:app/init {:locale (i18n/detect-locale
                                  (js->clj (or js/navigator.languages
                                               #js [js/navigator.language])))}]))
```

- [ ] **Step 8: Compile, test, lint**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, read the file; then `bb lint; bb fmt:check`.
Expected: `exit=0`, `0 failures, 0 errors.`, lint `errors: 0`. Fix format with `bb fmt` if needed.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/radiolisp/frontend/views frontend/src/radiolisp/frontend/main.cljs
git commit -m "feat(frontend): add views and wire dispatch, render and keyboard"
```

---

### Task 11: Styles (SCSS)

These styles port `docs/design/prototype/index.html`. The state class lives on `.app` (`.app.searching`) instead of `<body>`.

**Files:**
- Create: `frontend/resources/styles/main.scss`
- Create: `frontend/resources/styles/_tokens.scss`, `_base.scss`, `_shell.scss`, `_player.scss`, `_search.scss`, `_help.scss`

- [ ] **Step 1: `_tokens.scss`**

```scss
// accent-ladder, hue raspberry (design-taste-frontend, seed 396668359).
// The full hue only on small marks; its tints carry the areas.
$accent: #b8336a;
$accent-soft: #fdf2f7;
$accent-track: #f8dbe8;
$accent-ink: #6b1d3e;
$ground: #fbf8f9;
$surface: #ffffff;
$raised: #f5f1f3;
$active: #f1ebee;
$border: #ebe4e7;
$rule: #f3eef0;
$control: #d8cfd3;
$text: #181214;
$muted: #665a5f;
$faint: #72656b;
$negative: #c4242a;
$negative-ink: #831c1e;

$phone: 639px;
$tablet: 1024px;

:root {
  --accent: #{$accent};
  --accent-soft: #{$accent-soft};
  --accent-track: #{$accent-track};
  --accent-ink: #{$accent-ink};
  --ground: #{$ground};
  --surface: #{$surface};
  --raised: #{$raised};
  --active: #{$active};
  --border: #{$border};
  --rule: #{$rule};
  --control: #{$control};
  --text: #{$text};
  --muted: #{$muted};
  --faint: #{$faint};
  --negative: #{$negative};
  --negative-ink: #{$negative-ink};

  --col: 560px;
  --edge: 24px;
  --pad: 32px;
  --font: "DM Sans", Inter, system-ui, sans-serif;
  --ease: cubic-bezier(.2, .7, .2, 1);

  @media (max-width: $tablet) { --edge: 20px; }
  @media (max-width: $phone) { --edge: 16px; --pad: 20px; }
}
```

- [ ] **Step 2: `_base.scss`**

```scss
@use "tokens" as *;

*, *::before, *::after { box-sizing: border-box; border-radius: 0; }
html, body { margin: 0; }
body {
  background: var(--ground);
  color: var(--text);
  font: 400 14px/20px var(--font);
  -webkit-font-smoothing: antialiased;
}
button, input { font: inherit; color: inherit; }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.num { font-variant-numeric: tabular-nums; }
.dash { color: var(--faint); }
.vh {
  position: absolute; width: 1px; height: 1px;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap;
}
kbd {
  display: inline-block; min-width: 20px; padding: 0 5px;
  border: 1px solid var(--control); background: var(--surface);
  font: 500 12px/18px var(--font); text-align: center; color: var(--muted);
}
.icon-btn {
  display: grid; place-items: center; width: 40px; height: 40px;
  border: 1px solid transparent; background: none; color: var(--muted); cursor: pointer;
  &:hover { border-color: var(--control); color: var(--text); }
  svg { width: 18px; height: 18px; }
  @media (max-width: $phone) { width: 44px; height: 44px; }
}
.text-btn {
  padding: 0; border: 0; background: none; font-weight: 500; white-space: nowrap;
  text-decoration: underline; text-underline-offset: 3px; cursor: pointer;
}
```

- [ ] **Step 3: `_shell.scss`**

```scss
@use "tokens" as *;

.brand {
  position: fixed; top: var(--edge); left: var(--edge); z-index: 1;
  display: flex; align-items: center; gap: 10px;
  font-weight: 600; font-size: 16px; letter-spacing: -0.01em;
  color: var(--text); text-decoration: none;
  .mark { width: 28px; height: 20px; color: var(--accent); }
  @media (max-width: $phone) { b { display: none; } }
}

.corner {
  position: fixed; top: calc(var(--edge) + 2px); right: var(--edge);
  display: flex; gap: 16px; align-items: center;
  color: var(--muted); font-size: 13px;
  button {
    padding: 0; border: 0; background: none; cursor: pointer; color: inherit;
    text-decoration: underline; text-underline-offset: 3px;
    &[aria-pressed="true"] { color: var(--text); font-weight: 600; text-decoration: none; }
  }
}

// The column: centred when only the player shows, at the top while searching.
.stage {
  min-height: 100svh;
  display: grid; justify-items: center; align-content: center;
  padding: 88px var(--edge);
  transition: padding-top 280ms var(--ease);
  @media (max-width: $phone) { padding: 64px var(--edge) 72px; }
}
.col { width: 100%; max-width: var(--col); }

.app.searching {
  .stage { align-content: start; padding-top: max(72px, 9vh); }

  // Phone: searching becomes a full-screen sheet.
  @media (max-width: $phone) {
    .brand, .corner { display: none; }
    .stage { padding: 0; align-content: stretch; }
    .col { max-width: none; display: flex; flex-direction: column; height: 100svh; }
  }
}

@media (prefers-reduced-motion: reduce) {
  .stage { transition: none; }
}
```

- [ ] **Step 4: `_player.scss`**

```scss
@use "tokens" as *;

.player {
  background: var(--surface);
  border: 1px solid var(--border);
  padding: var(--pad);

  h1 {
    margin: 12px 0 0;
    font-size: 28px; line-height: 34px; font-weight: 700; letter-spacing: -0.02em;
    display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
    overflow: hidden; overflow-wrap: anywhere;
    @media (max-width: $phone) { font-size: 24px; line-height: 30px; }
  }
  &.empty h1 { margin-top: 0; color: var(--muted); font-weight: 600; }
}

.status {
  display: flex; align-items: center; gap: 10px; height: 18px;
  color: var(--muted); font-size: 13px;
  .live { color: var(--accent-ink); font-weight: 600; }
  &.err { color: var(--negative-ink); }
}

.waves {
  width: 22px; height: 16px; flex: none; color: var(--accent);
  .w1, .w2 { opacity: .25; }
}
.is-playing .waves {
  .w1 { animation: wave 1.6s ease-in-out infinite; }
  .w2 { animation: wave 1.6s ease-in-out .3s infinite; }
}
@keyframes wave { 0%, 100% { opacity: .2; } 50% { opacity: 1; } }
@media (prefers-reduced-motion: reduce) {
  .is-playing .waves .w1, .is-playing .waves .w2 { animation: none; opacity: 1; }
}

.facts {
  margin: 6px 0 0; color: var(--muted);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  .unit { font-size: 12px; }
}

.controls {
  display: grid; grid-template-columns: 56px 40px minmax(0, 1fr) 40px;
  align-items: center; gap: 12px; margin-top: 28px;
  @media (max-width: $phone) {
    grid-template-columns: 56px minmax(0, 1fr) 44px;
    .slider, .vol-val { display: none; }
    .mute { grid-column: 3; }
  }
}

.toggle {
  display: grid; place-items: center; width: 56px; height: 56px;
  border: 0; background: var(--accent); color: #fff; cursor: pointer;
  transition: transform 90ms var(--ease);
  &:active { transform: scale(.94); }
  &:disabled { background: var(--raised); color: var(--faint); cursor: not-allowed; }
  svg { width: 22px; height: 22px; }
}

// The volume track is drawn with flat spans; the native range input sits on top, invisible.
.slider {
  position: relative; height: 24px;
  .track, .fill { position: absolute; top: 10px; height: 4px; }
  .track { left: 0; right: 0; background: var(--accent-track); }
  .fill { left: 0; background: var(--accent); }
  .thumb { position: absolute; top: 6px; width: 12px; height: 12px; background: var(--accent-ink); }
  input { position: absolute; inset: 0; width: 100%; margin: 0; opacity: 0; cursor: pointer; }
  &:has(input:focus-visible) { outline: 2px solid var(--accent); outline-offset: 4px; }
}
.vol-val { width: 40px; text-align: right; color: var(--muted); font-size: 13px; }

// Phone while searching: the player shrinks to a strip (toggle + name).
.app.searching .player {
  @media (max-width: $phone) {
    display: grid; grid-template-columns: 44px minmax(0, 1fr); align-items: center; gap: 12px;
    padding: 8px var(--edge); border-width: 0 0 1px;
    .status, .facts, .controls > :not(.toggle) { display: none; }
    .controls { display: contents; }
    .toggle { width: 44px; height: 44px; grid-column: 1; grid-row: 1; }
    h1 { grid-column: 2; grid-row: 1; margin: 0; font-size: 16px; line-height: 22px; -webkit-line-clamp: 1; }
  }
}
```

- [ ] **Step 5: `_search.scss`**

```scss
@use "tokens" as *;

.opener {
  display: flex; align-items: center; gap: 12px;
  width: 100%; height: 48px; margin-top: 8px; padding: 0 var(--pad);
  border: 1px solid var(--border); background: var(--surface); color: var(--muted);
  text-align: left; cursor: pointer;
  transition: border-color 120ms, color 120ms;
  &:hover { border-color: var(--control); color: var(--text); }
  svg { width: 16px; height: 16px; }
}

.panel {
  margin-top: 8px; background: var(--surface); border: 1px solid var(--border);
  animation: drop 200ms var(--ease);
}
@keyframes drop { from { opacity: 0; transform: translateY(-8px); } }
@media (prefers-reduced-motion: reduce) { .panel { animation: none; } }

.field {
  position: relative;
  > svg { position: absolute; left: var(--pad); top: 16px; width: 16px; height: 16px; color: var(--muted); }
  input {
    width: 100%; height: 48px; padding: 0 56px 0 calc(var(--pad) + 28px);
    border: 0; border-bottom: 2px solid transparent; background: var(--surface);
    &:focus { outline: none; border-bottom-color: var(--accent); }
    &::placeholder { color: var(--faint); }
    &::-webkit-search-cancel-button { display: none; }
    @media (max-width: $phone) { font-size: 16px; } // 16px: iOS does not zoom
  }
  .close { position: absolute; right: 4px; top: 4px; }
}

.list-head {
  display: flex; justify-content: space-between;
  padding: 14px var(--pad) 6px; color: var(--muted); font-size: 13px;
  .mode { color: var(--faint); }
}

.results {
  list-style: none; margin: 0; padding: 0 0 8px; overflow-y: auto;
  // viewport − top offset − player (~240) − gap − input − list head − bottom air
  max-height: calc(100svh - max(72px, 9vh) - 240px - 8px - 48px - 40px - 24px);
  &:focus { outline: none; }
}

.st {
  display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 2px 16px;
  padding: 10px var(--pad) 10px calc(var(--pad) - 3px);
  border-top: 1px solid var(--rule); border-left: 3px solid transparent;
  cursor: pointer;
  &:first-child { border-top-color: transparent; }
  &:hover { outline: 1px solid var(--control); outline-offset: -1px; }
  &.hl { background: var(--active); }
  &.skeleton { cursor: default; &:hover { outline: none; } }
  .n { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .f { text-align: right; white-space: nowrap; .unit { color: var(--muted); font-size: 12px; } }
  .m {
    grid-column: 1 / -1; color: var(--muted); font-size: 13px; line-height: 18px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  &.on .n {
    color: var(--accent-ink);
    &::before {
      content: ""; display: inline-block; width: 8px; height: 8px; margin-right: 8px;
      background: var(--accent); vertical-align: 1px;
    }
  }
  @media (max-width: $phone) {
    min-height: 56px; padding: 12px var(--edge) 12px calc(var(--edge) - 3px);
  }
}
.panel.normal .st.hl { border-left-color: var(--accent); }

.skel { display: block; height: 12px; margin: 4px 0; background: var(--raised); }

.note {
  margin: 8px var(--pad) 16px; padding: 14px 16px;
  border: 1px dashed var(--control); color: var(--muted);
  &.error {
    display: flex; justify-content: space-between; gap: 16px;
    border: 1px solid var(--border); color: var(--text);
  }
  .dot { display: inline-block; width: 8px; height: 8px; margin-right: 10px; background: var(--negative); }
}

// Phone while searching: the panel fills the sheet under the player strip.
.app.searching {
  @media (max-width: $phone) {
    .panel { flex: 1; display: flex; flex-direction: column; min-height: 0; margin-top: 0; border: 0; animation: none; }
    .field { border-bottom: 1px solid var(--border); }
    .results { max-height: none; flex: 1; }
  }
}
```

- [ ] **Step 6: `_help.scss`**

```scss
@use "tokens" as *;

// The one thing that floats, so the one shadow.
#help {
  width: min(480px, calc(100vw - 32px)); padding: var(--pad);
  border: 1px solid var(--border); background: var(--surface); color: var(--text);
  box-shadow: 0 12px 32px rgba(24, 18, 20, .12);
  &::backdrop { background: rgba(24, 18, 20, .24); }
  h2 { margin: 0 0 16px; font-size: 18px; line-height: 24px; }
  h3 { margin: 20px 0 8px; font-size: 14px; font-weight: 600; }
  dl { display: grid; grid-template-columns: auto 1fr; gap: 8px 16px; margin: 0; align-items: center; }
  dt { display: flex; gap: 4px; flex-wrap: wrap; }
  dd { margin: 0; color: var(--muted); }
  .close { position: absolute; top: 16px; right: 16px; }
}
```

- [ ] **Step 7: `main.scss`**

```scss
@use "tokens";
@use "base";
@use "shell";
@use "player";
@use "search";
@use "help";
```

- [ ] **Step 8: Compile**

Run: `bb css && ls -l frontend/resources/public/css/main.css`
Expected: no sass errors; the file exists and is a few KB.

- [ ] **Step 9: Commit**

```bash
git add frontend/resources/styles
git commit -m "feat(frontend): add SCSS styles from the design prototype"
```

---

### Task 12: Run it and verify in the browser

**Files:** none new; fixes go in the file that is wrong.

- [ ] **Step 1: Start the dev environment in the background**

Run: `bb dev > /tmp/radiolisp-dev.txt 2>&1 &`. Wait until `/tmp/radiolisp-dev.txt` contains `Build completed`, then run `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/`.
Expected: `200`.

- [ ] **Step 2: Screenshot the first screen at three widths**

```bash
mkdir -p /tmp/radiolisp-shots
for w in 1440x900 768x1024 390x844; do
  chromium --headless=new --disable-gpu --hide-scrollbars --window-size=${w/x/,} \
    --virtual-time-budget=6000 --lang=es-ES \
    --screenshot=/tmp/radiolisp-shots/first-$w.png http://localhost:8080/
done
```

Open each PNG and compare with `docs/design/prototype/index.html` (first visit):
- "Nada sonando" in the player.
- The search panel open with "Más escuchadas hoy" and real stations.
- Brand top left, "Atajos ?" and ES · EN top right.
- On the phone (390), the full-screen sheet.

- [ ] **Step 3: Manual checklist in a real browser**

Open http://localhost:8080 and check each item. Fix anything that fails before going on.

1. Typing `jazz` shows "Buscando…" and then results. In the Network panel there is one request per pause, not one per key.
2. Deleting down to one letter brings back "Más escuchadas hoy" with no new request.
3. Clicking an MP3 station: the panel closes, "Conectando…" changes to "En directo" with a running clock, and the waves pulse.
4. Play/pause, the slider, the mute button, and `h`, `l`, `m`, `space` all work.
5. `/` opens the search with the ✕ visible. `↓`/`↑` and `ctrl-j`/`ctrl-k` move the marked row. `esc` switches the list head to "navegando". `j`, `k`, `gg` and `G` move. `enter` plays. `esc` while browsing closes the panel.
6. `?` opens the help dialog, and `esc` closes it.
7. ES · EN switches every text, including the help, the `aria-label`s and an error.
8. In DevTools, set the network to Offline and search: the error note "No se pudo conectar…" appears with "Reintentar". Back online, "Reintentar" works.
9. A station whose name contains "HLS" or whose URL ends in `.m3u8`: the player shows the unsupported-format message.
10. At 390 px wide: the player card, the sheet while searching, and choosing a station returns to the player.

- [ ] **Step 4: Design gate**

Run:

```bash
S=.agents/skills/design-taste-frontend
node $S/scripts/inspect.mjs --app "http://localhost:8080/" --out .taste-skill/inspect-app > /tmp/radiolisp-inspect.txt 2>&1
```

Then read `/tmp/radiolisp-inspect.txt` and the screenshots it lists.
Expected: `0 FAIL`. Allowed warnings: `hero-void` (the empty space around the centred player, which is the design) and a `shadow` on `#help`.

- [ ] **Step 5: Final checks and commit**

Run: `bb test > /tmp/radiolisp-test.txt 2>&1; echo exit=$?`, then read the file; then `bb lint; bb fmt:check`.
Expected: all green. Stop the dev environment (`kill %1`, or `pkill -f "shadow-cljs watch"` and `pkill -f "sass --watch"`).

```bash
git add -A frontend common
git commit -m "fix(frontend): adjustments from browser verification"
```

(Skip this commit if nothing changed.)

---

### Task 13: Release build

**Files:** none.

- [ ] **Step 1: Build**

Run: `bb build > /tmp/radiolisp-build.txt 2>&1; echo exit=$?`, then read the file.
Expected: `exit=0`, `Build completed`, `frontend/resources/public/js/main.js` and `css/main.css` exist.

- [ ] **Step 2: Serve the release and smoke-test**

Run: `(cd frontend/resources/public && python3 -m http.server 8090 --bind 127.0.0.1 > /dev/null 2>&1 &)`, then take a screenshot of `http://127.0.0.1:8090/` with the chromium command from Task 12 Step 2. Check the first screen renders as in Task 12. Stop the server afterwards.

- [ ] **Step 3: Nothing to commit**

The build output is ignored by git. `git status --short` must be empty.
