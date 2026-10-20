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
