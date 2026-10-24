(ns radiolisp.frontend.config
  "What the build says about where the app runs.")

(goog-define backend true)
;; Whether a backend serves this page: the proxy at /api/stream and the
;; WebSocket at /api/ws. The static build (`bb build:static`, the one on
;; GitHub Pages) sets it to false, so the app never tries them.

;; Whether the page registers the service worker (`sw.js`, written by the
;; release builds). Development sets it to false: the dev server has no
;; sw.js, and a cached shell would hide the reloads.
(goog-define service-worker true)

(goog-define repository "https://github.com/haztecaso/radiolisp")
