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
    (shortcut t ["/"] :help/k-filter)
    (shortcut t ["a"] :help/k-add)
    (shortcut t ["space"] :help/k-toggle)
    (shortcut t ["-" "+"] :help/k-volume)
    (shortcut t ["i"] :help/k-panel)
    (shortcut t ["R"] :help/k-random-play)
    (shortcut t ["?"] :help/k-help)]
   [:h3 (t :help/grid)]
   [:dl
    (shortcut t ["←" "↓" "↑" "→"] :help/k-select)
    (shortcut t ["h" "j" "k" "l"] :help/k-select)
    (shortcut t ["tab"] :help/k-next)
    (shortcut t ["enter" "space"] :help/k-play)
    (shortcut t ["s"] :help/k-options)
    (shortcut t ["shift ←↓↑→" "shift hjkl"] :help/k-reorder)
    (shortcut t ["r"] :help/k-random)]
   [:h3 (t :help/filter)]
   [:dl
    (shortcut t ["enter"] :help/k-play-first)
    (shortcut t ["↓"] :help/k-to-grid)
    (shortcut t ["esc"] :help/k-clear-filter)]
   [:h3 (t :help/typing)]
   [:dl
    (shortcut t ["↓" "↑"] :help/k-move)
    (shortcut t ["ctrl j" "ctrl k"] :help/k-move)
    (shortcut t ["enter"] :help/k-choose)
    (shortcut t ["shift enter"] :help/k-favorite)
    (shortcut t ["esc"] :help/k-normal)]
   [:h3 (t :help/browsing)]
   [:dl
    (shortcut t ["j" "k"] :help/k-move)
    (shortcut t ["gg" "G"] :help/k-edges)
    (shortcut t ["enter"] :help/k-choose)
    (shortcut t ["f"] :help/k-favorite)
    (shortcut t ["i" "/"] :help/k-insert)
    (shortcut t ["esc"] :help/k-close)]])
