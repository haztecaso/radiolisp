# radiolisp: guide for AI agents

radiolisp is a minimal web radio player written in ClojureScript, built to learn
Clojure. The design specs are `docs/specs/2026-10-20-mvp-design.md`,
`docs/specs/2026-10-20-iteration-2-design.md` (favourites; it supersedes the MVP
layout), `docs/specs/2026-10-21-iteration-3-design.md` (artwork modes,
«Personalizar», the player bar and its panel) and
`docs/specs/2026-10-20-backend-design.md` (the backend: http proxy and now
playing); read them before changing behaviour.
Ideas that need a backend are collected in `docs/ideas/backend.md`.

## Hard rules

- Never `git push` or touch remotes. The user pushes. Never run `bb deploy`.
- Run `bb lint`, `bb fmt:check` and `bb test` before you call a change done.
- Never pipe test output into `head`, `tail` or `grep`; redirect to a file first.
- UI strings live only in `frontend/resources/i18n/<locale>.edn` (es, en, pt, fr,
  it, ca, gl, eu, ro, bg, ru, zh), with the same `{placeholders}` as `en` and
  every plural form `Intl.PluralRules` gives the locale. State holds keys and
  data, never translated text.
- When iterating on features, edit only `es.edn` and `en.edn`: add, change and
  remove keys there. Leave the other locales out of sync; they are translated
  in a separate pass, only when the user asks for it.
- `actions/handle` and `keymap/key->action` stay pure. Side effects go in
  `effects/execute!`, `api`, `audio`.
- Design changes follow `.agents/skills/design-taste-frontend` (app-screen rules).
- `bb e2e` must never count clicks or votes in Radio Browser: keep `/json/url` and
  `/json/vote` blocked in `frontend/e2e/check.mjs`.
- `radiolisp.frontend.hls` is only loaded through `shadow.lazy`; never require it
  from another namespace, or hls.js moves into the main module.
- The backend never logs client IPs, and never connects to a URL that did not
  come from Radio Browser through a station uuid; every connection goes through
  `radiolisp.backend.guard`.
- Backend tests must not touch the network: use `radiolisp.backend.fake-station`
  and pass `:resolve-station` to `main/start!`.

## Modules

- `common/`: pure `.cljc` shared by frontend and backend (`radiolisp.common.*`),
  including the WebSocket messages (`radiolisp.common.protocol`).
- `frontend/`: shadow-cljs + Replicant SPA (`radiolisp.frontend.*`), SCSS in
  `resources/styles/`. `resources/public/` holds dev output only; release builds
  go to `dist/`.
- `backend/`: Clojure on the JVM (`radiolisp.backend.*`): Ring + Jetty 12 on
  virtual threads, one relay session per station for the stream proxy and the
  ICY titles.

## Tasks (inside `nix develop`)

`bb setup` · `bb dev` (http://localhost:8080, plus the backend on :8090 with debug
logs) · `bb backend` (http://localhost:8090 alone, serves the frontend build too) · `bb test` (Node and JVM) · `bb lint` · `bb fmt` ·
`bb fmt:check` · `bb build` (everything into `dist/`: the web in `dist/public/`,
the backend uberjar in `dist/radiolisp-backend.jar`; also `build:frontend`,
`build:backend`) · `bb clean` · `bb e2e` (browser check; needs `bb dev` running or
`URL=…`, uses the real Radio Browser API and real streams)
