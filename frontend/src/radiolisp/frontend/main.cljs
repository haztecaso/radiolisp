(ns radiolisp.frontend.main)

(defn init []
  (set! (.-textContent (js/document.getElementById "app")) "radiolisp"))
