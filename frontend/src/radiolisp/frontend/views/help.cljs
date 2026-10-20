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
