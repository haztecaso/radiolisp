(ns radiolisp.frontend.store
  "The single source of truth: one atom with the whole app state.")

(def initial-state
  {:locale    :en
   :help?     false
   :player    {:station nil
               :status  :stopped    ; :stopped :connecting :playing :paused :error
               :error   nil         ; [:error/stream {}] | [:error/unsupported {}]
               :volume  100
               :muted?  false}
   :favorites {:items    []         ; [{:id :name :key}], in grid order
               :status   :idle      ; :idle (storage unread) :pending :loading :done :error
               :retried? false
               :missing  #{}}       ; ids Radio Browser no longer knows
   :stations  {}                    ; id → normalized station
   :art       {}                    ; id → :cover :contain :small or :broken
   :filter    ""
   :drag      nil                   ; {:id :over} while a tile is dragged
   :menu      nil                   ; {:kind :station :id :anchor} | {:kind :list :anchor}
   :dialog    nil                   ; {:kind :remove :id} | {:kind :key :id :pending :message}
   :toast     nil                   ; [[message-key vars] ...]
   :search    {:open?     false
               :query     ""
               :mode      :insert   ; :insert (typing) | :normal (browsing the list)
               :status    :idle     ; :idle :loading :done :error
               :seq       0
               :retried?  false
               :results   []
               :top       []
               :highlight 0
               :error     nil}      ; [:error/network {:at ms}]
   :api       {:servers [] :current 0}
   :keys      {:last-g 0}})

(defonce state (atom initial-state))
