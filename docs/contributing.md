# Contributing to radiolisp

Issues and pull requests are welcome. This guide covers the development
environment, the tasks, how the code is organised and how it is tested. For
building and running your own copy, see the [self-hosting guide](selfhosting.md).

The design is in [`docs/specs/`](specs/) (written in Spanish); read the
spec for the part you are changing before changing its behaviour. Ideas that
need a backend are collected in [`docs/ideas/backend.md`](ideas/backend.md).

## Requirements

The recommended way is [Nix](https://nixos.org/) with flakes. The `flake.nix`
provides every tool with pinned versions:

| Tool | What for |
|---|---|
| JDK 21 + `clojure` CLI | shadow-cljs, the backend and the dependencies (`deps.edn`) |
| [babashka](https://babashka.org/) | the project tasks (`bb.edn`) |
| Node.js 22 | the shadow-cljs CLI and the tests |
| dart-sass | compiling the SCSS |
| clj-kondo, cljfmt, clojure-lsp | lint, formatting and editor support |
| Chrome or Chromium | only for `bb e2e` |

Without Nix, install those tools by hand and run the same commands without the
`nix develop` prefix.

## Getting started

```sh
nix develop      # enter the environment (or `direnv allow` once, if you use direnv)
bb setup         # install shadow-cljs and hls.js from npm (only once)
bb dev           # compile in watch mode and serve the app
```

Open <http://localhost:8080>. The first build downloads the dependencies and
takes a few minutes; later ones take seconds. `bb dev` also starts the backend
on <http://localhost:8090>, and its logs show up in the same terminal (at
`debug` level; `LOG_LEVEL=info bb dev` for less).

`bb dev` watches both the ClojureScript (shadow-cljs) and the SCSS (sass). When
you save a `.cljs`, a `.scss` or an `.edn` dictionary, the browser hot-reloads
without losing state.

In development the page on `:8080` talks to the backend on `:8090` (the dev
build sets this with `radiolisp.frontend.socket/backend-origin`). `:8090` also
serves the app, as in production, and hot reload works on both. For the
backend alone: `bb backend`.

## Tasks

Run them all from the repo root, inside `nix develop`. `bb tasks` lists them.

| Task | What it does |
|---|---|
| `bb setup` | `npm install` in `frontend/` |
| `bb dev` | watch ClojureScript and SCSS on `:8080`, and the backend on `:8090` with its logs |
| `bb backend` | the backend on `:8090` (variables in [Backend](#backend)) |
| `bb test` | all the tests: `common` and `frontend` on Node; `common` and `backend` on the JVM |
| `bb lint` | clj-kondo |
| `bb fmt` / `bb fmt:check` | apply or check formatting with cljfmt |
| `bb build` | release build of everything into `dist/` (frontend and backend jar) |
| `bb build:frontend` / `bb build:backend` | only the web into `dist/public/` / only `dist/radiolisp-backend.jar` |
| `bb build:static` | the web without a backend into `dist/static/` (what GitHub Pages serves) |
| `bb clean` | delete `dist/` and the backend's compiled classes |
| `bb e2e` | end-to-end check in Chromium (see [Tests](#tests)) |

Before calling a change done: `bb lint && bb fmt:check && bb test`.

## Structure

It is organised by modules:

```
radiolisp/
├── flake.nix  bb.edn  deps.edn   # environment, tasks and root deps.edn (only for clojure-lsp)
├── dist/                          # build output: public/, radiolisp-backend.jar and static/ (not in git)
├── common/                        # pure .cljc, shared by frontend and backend
│   ├── src/radiolisp/common/      #   station (normalises Radio Browser), favorites (list, M3U, .list, Radiolise),
│   │                              #   protocol (WebSocket messages)
│   └── test/
├── frontend/
│   ├── deps.edn  shadow-cljs.edn  package.json
│   ├── resources/
│   │   ├── public/                # index.html, fonts/, icons/; css/ and js/ are build output
│   │   ├── styles/                # main.scss and partials (_tokens, _grid, _player, _search…)
│   │   └── i18n/                  # one .edn per language (es, en, pt, fr…)
│   ├── src/radiolisp/frontend/
│   │   ├── main.cljs              # wiring: dispatch, render, keyboard, media keys
│   │   ├── store.cljs             # the initial state and the atom
│   │   ├── actions.cljs           # all the logic: state × action → state + effects (pure)
│   │   ├── keymap.cljs            # key + context → action (pure)
│   │   ├── effects.cljs           # runs effects: fetch, audio, storage, files, focus
│   │   ├── api.cljs  audio.cljs   # Radio Browser and the <audio> element
│   │   ├── stream_url.cljs        # which URLs to try for a stream (https, original, proxy)
│   │   ├── socket.cljs            # the WebSocket to the backend (now playing)
│   │   ├── config.cljs            # build flags: is there a backend, the repository URL
│   │   ├── hls.cljs               # hls.js, in its own module (loaded on demand)
│   │   ├── i18n.cljs              # translations, plurals and numbers
│   │   └── views/                 # hiccup for Replicant
│   ├── test/
│   └── e2e/check.mjs              # browser check (bb e2e)
├── backend/                       # Clojure on the JVM: Ring + Jetty 12 with virtual threads
│   ├── deps.edn  build.clj        # build.clj: the uberjar (tools.build)
│   ├── src/radiolisp/backend/
│   │   ├── main.clj  http.clj     # startup, routes and static files
│   │   ├── stream.clj  socket.clj # /api/stream/<uuid> and /api/ws
│   │   ├── relay.clj              # one session per station: reader, listeners, subscribers
│   │   ├── upstream.clj  icy.clj  # HTTP/1.0 client (accepts ICY) and metadata reader
│   │   ├── radio_browser.clj      # uuid → station, cached
│   │   └── guard.clj  limits.clj  config.clj   # SSRF, per-IP limits, configuration
│   └── test/                      # unit and integration tests against a fake station
└── docs/                          # specs, plans, ideas and prototype
```

## How it works

Data flows in one direction and everything is data:

```
click / key / <audio> event / HTTP response
        │
        ▼
   action (vector)              [:search/typed "jazz"]
        │
        ▼
   actions/handle               state × action → {:state … :effects […]}   (pure)
        │                               │
        ▼                               ▼
   store (atom) ──► render      effects/execute! ──► new actions
                   (Replicant)
```

- **Views** ([Replicant](https://github.com/cjohansen/replicant)): pure functions
  from state to hiccup. Event handlers are data:
  `{:on {:click [[:player/toggle]]}}`.
- **Actions**: `actions/handle` is a `defmulti` on the first element of the
  vector. It does no I/O: it returns the new state and a list of effects, such as
  `[:api/fetch {...}]`, `[:storage/save favourites]`, `[:audio/play url hls?]` or
  `[:timer/start :search 300 [:search/run]]`.
- **Effects**: `effects/execute!` is the only place with side effects. Their
  results come back as actions, for example `[:search/loaded seq stations]`.
- **State holds keys, never text.** An error is `[:error/network {:at ms}]`, and
  the view is what translates it.

### Adding a feature

1. Write a test in `frontend/test/…/actions_*_test.cljs` with the starting state,
   the action, and the expected state and effects.
2. Add the `defmethod handle :your/action` in `actions.cljs`.
3. If you need a new effect, add its `defmethod execute!` in `effects.cljs`.
4. If there is a new key, add it to `keymap.cljs` and its test, and also to the
   help (`views/help.cljs`).
5. Render what you need in `views/` and dispatch the action from `:on`.

### Text and languages

Every visible string, `aria-label` included, lives in
`frontend/resources/i18n/<language>.edn` (`es`, `en`, `pt`, `fr`, `it`, `ca`,
`gl`, `eu`, `ro`, `bg`, `ru`, `zh`), with the same keys and the same
`{placeholders}` in all of them (tests check this). From the views:
`(t :search/empty {:query q})`. Plurals are a map with the forms
`Intl.PluralRules` gives the language (`{:one "…" :other "…"}`; Romanian adds
`:few` and Russian `:few` and `:many`), picked with `{:count n}`. `:lang/name` is
the language's name in that language: it is what the selector lists. To add a
language, create its `.edn` and add it to `dictionaries` and `locales` in
`i18n.cljs`.

### Styles

They are SCSS in `frontend/resources/styles/`. The colour tokens, such as the
*raspberry* scale, are in `_tokens.scss` and exposed as custom properties.
Design rules: radius 0, a single type family (DM Sans, self-hosted), full colour
only on small marks, and no gradients or shadows at rest. For visual changes,
follow the skill `.agents/skills/design-taste-frontend` (*app screens* rules).

Dark mode: the dark palette is the `$dark` map in `_tokens.scss`, which
redefines the same custom properties under `:root[data-theme="dark"]`. A script
in `index.html` sets `data-theme` before the first paint (the choice saved in
`radiolisp/theme`, otherwise the browser preference). The sun/moon button in the
top bar switches and saves it; until it is pressed, the app follows changes to
the system preference. A new colour needs its value in both themes: no loose hex
values in the styles.

## REPL

With `bb dev` running, shadow-cljs opens an nREPL and writes its port to
`frontend/.shadow-cljs/nrepl.port`.

- **Editor** (Calva, CIDER, Conjure…): connect to that nREPL and pick the `:app`
  build. With the app open in the browser, whatever you evaluate runs there.
- **Terminal**: `cd frontend && npx shadow-cljs cljs-repl app`.

To poke at the live state:

```clojure
(require '[radiolisp.frontend.store :as store])
(:player @store/state)
(:favorites @store/state)
(radiolisp.frontend.main/dispatch! [:i18n/set :es])
```

## Tests

- **`bb test`**: unit tests with `cljs.test`, compiled with shadow-cljs's
  `:node-test` target. They cover the pure logic: station normalisation, the
  favourites list (order, keys, filter, EDN, M3U, `.list` and Radiolise), image
  fitting, i18n, every action and the key map. Any namespace ending in `-test`,
  in `common/test` or `frontend/test`, is picked up automatically. Then it runs
  the backend tests on the JVM (`clojure -X:test` in `backend/`, plus those of
  `common/`): the ICY reader, the client, the guard, the limits and an
  integration test that starts the backend in front of a local fake station.
- **`bb e2e`**: drives Chromium over CDP against the running app and checks about
  80 things: adding from the palette, a single `byuuid` request on reload, real
  playback, assigned keys, menus, drag and drop, filter, removing with
  confirmation, export (M3U and `.list`) and import (M3U, `.list` and Radiolise),
  HLS with hls.js, help, language, theme and the mobile layout. It seeds
  `localStorage` and blocks `/json/url` and `/json/vote`, so it does not alter
  Radio Browser's statistics. `bb dev` must be running; for another address, for
  example the backend, use `URL=http://localhost:8090/ bb e2e`: if it answers
  `/api/health`, it also checks that now playing arrives. It uses the real API
  and real streams, so it needs network access and depends on the stations
  responding. Screenshots end up in `frontend/target/e2e/`.

## Release build

There are two ways to build radiolisp, depending on whether a backend will be
there:

| | Frontend alone | Frontend with backend |
|---|---|---|
| Command | `bb build:static` | `bb build` (or `bb build:frontend` + `bb build:backend`) |
| Output | `dist/static/` | `dist/public/` and `dist/radiolisp-backend.jar` |
| Served by | any static file server, under any path (GitHub Pages: `/radiolisp/`) | the jar, or nginx in front of it |
| Search, favourites, playback, import/export | yes | yes |
| Now playing | no | yes |
| `http://` stations on an `https` page | only if they also stream over `https` | through the proxy |

Both are the same code. The difference is a compile-time flag,
`radiolisp.frontend.config/backend`, which the `:static` build in
`frontend/shadow-cljs.edn` sets to `false`: then the app never opens the
WebSocket and never tries the proxy. All paths in the pages are relative, so
either build works at the root of a domain or in a subfolder.

The `:app` build (the default one, `bb build:frontend`) also works without a
backend: it tries the proxy, gets a 404 and tells you the station only streams
over http, and while a station plays it retries the WebSocket now and then.
`build:static` just skips those attempts.

Everything a build produces goes to `dist/` (not in git):

```
dist/
├── public/                  # the website for the backend: index.html, css/, js/, fonts/, icons/
├── radiolisp-backend.jar    # the backend with all its dependencies (JDK 21)
└── static/                  # the website without a backend, same layout as public/
```

`frontend/resources/public/` is for development only: that is where `bb dev` and
sass in watch mode write.

Run it with:

```sh
java -jar dist/radiolisp-backend.jar
```

Without `PUBLIC_DIR`, the jar serves the `public/` folder next to it, so the two
can be moved anywhere together. The website without the backend can be
served by any static file server, for example:

```sh
bb build:static
cd dist/static && python3 -m http.server 8000
```

## Backend

A single process serves the app, `GET /api/stream/<uuid>`, the WebSocket
`/api/ws` and `GET /api/health`.

- **Now playing.** While a station plays, the frontend subscribes over the
  WebSocket with its uuid. The backend opens the station with `Icy-MetaData: 1`,
  reads the `StreamTitle`s and sends them as EDN actions
  (`[:now-playing/title {:id … :title …}]`). On pause or when switching stations,
  it unsubscribes.
- **Proxy.** On an `https` page, an `http://` station whose `https://` version
  does not exist plays through `/api/stream/<uuid>`.
- **One connection per station**, shared by all its proxy listeners and
  subscribers. A new listener immediately receives the last 64 KiB; one that
  falls too far behind is disconnected without slowing the others down. If the
  station drops, it retries three times; with nobody left, the connection closes
  after 5 s.
- **It is not an open proxy**: it only accepts Radio Browser uuids and resolves
  the URL on the server; it does not connect to private addresses, not even after
  a redirect. It does not log IPs.

It is configured with environment variables:

| Variable | Default | What it is |
|---|---|---|
| `PORT` / `HOST` | `8090` / `127.0.0.1` | where it listens; in production, behind a proxy with TLS |
| `PUBLIC_DIR` | `public/` next to the jar; otherwise `../frontend/resources/public` | the frontend build |
| `TRUST_PROXY` | `false` | `true` to take the IP from `X-Forwarded-For` |
| `LOG_LEVEL` | `info` | `debug` adds every `/api` request, the titles and the listeners |
| `MAX_SESSIONS` | `32` | stations open at once |
| `MAX_LISTENERS` / `MAX_LISTENERS_PER_IP` | `200` / `2` | proxy listeners |
| `MAX_SOCKETS_PER_IP` | `4` | WebSockets per IP |
| `STREAM_REQUESTS_PER_MINUTE` | `20` | requests to `/api/stream` per IP |

Logs go to the console (logback, `backend/resources/logback.xml`): which stations
open, connect, drop or close, and the errors. Never IPs.

In production: `bb build` and `java -jar dist/radiolisp-backend.jar` (with
`dist/public/` next to it) behind Caddy or nginx with TLS, with
`TRUST_PROXY=true`. The proxy must let the WebSocket upgrade through on
`/api/ws`.

## Development workflow and CI

The repository follows GitHub flow: `main` is always deployable, and every
change goes in a branch (`feat/…`, `fix/…`, `docs/…`, `ci/…`) and reaches `main`
through a pull request. `main` is protected: no direct pushes, and the pull
request needs CI to pass.

Two GitHub Actions workflows, in `.github/workflows/`:

- **CI** (`ci.yml`), on every pull request to `main` and every push to `main`:
  `bb lint`, `bb fmt:check`, `bb test`, `bb build` and `bb build:static`,
  inside the same `nix develop` shell as locally. `bb e2e` does not run there:
  it needs real streams and the real Radio Browser API.
- **Pages** (`pages.yml`), when CI passes on `main` (or by hand): builds the
  frontend alone with `bb build:static` and publishes `dist/static/` to
  <https://haztecaso.github.io/radiolisp/>.

## Deployment

The copy on GitHub Pages deploys itself (see above). The full one, with the
backend, runs on a server of your own: a `radiolisp` user, a `radiolisp.service`
service (`java -jar` on `/var/www/radiolisp/current/`) and nginx, which serves
`public/` and passes `/api/` to the backend.

```sh
bb deploy
```

It runs `bb build`, keeps what was deployed in `/var/www/radiolisp/previous/`,
uploads `dist/` to `current/` with rsync, restarts the service and waits for
`/api/health` to answer. If it does not answer within 30 s, it puts the previous
version back. The target is not in the repository: set `DEPLOY_SERVER` (the
ssh destination) and `DEPLOY_URL` (where the app answers) in `.env`, which git
ignores (copy `.env.example`), or in the environment.
