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
