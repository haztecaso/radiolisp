(ns radiolisp.frontend.config
  "What the build says about where the app runs.")

(goog-define backend true)
;; Whether a backend serves this page: the proxy at /api/stream and the
;; WebSocket at /api/ws. The static build (`bb build:static`, the one on
;; GitHub Pages) sets it to false, so the app never tries them.

(goog-define repository "https://github.com/haztecaso/radiolisp")
