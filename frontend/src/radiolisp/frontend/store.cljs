(ns radiolisp.frontend.store
  "The single source of truth: one atom with the whole app state.")

(def initial-state
  {:locale       :en
   :help?        false
   :theme        {:current :light       ; :light | :dark
                  :chosen? false}       ; true once picked with the button: the browser's no longer applies
   :player       {:station nil
                  :status  :stopped     ; :stopped :connecting :playing :paused :error
                  :error   nil          ; [:error/stream {}] | [:error/unsupported {}]
                  :volume  100
                  :muted?  false}
   :favorites    {:items    []          ; [{:id :name :key}], in grid order
                  :status   :idle       ; :idle (storage unread) :pending :loading :done :error
                  :retried? false
                  :missing  #{}}        ; ids Radio Browser no longer knows
   :stations     {}                     ; id → normalized station
   :art          {}                     ; id → :cover :contain :small or :broken
   :filter       ""
   :filter-open? false                  ; the filter field shows only while in use
   :drag         nil                    ; {:id :over} while a tile is dragged
   :file-drag?   false                  ; true while a file is dragged over the page
   :menu         nil                    ; {:kind :station :id :anchor} | {:kind :list :anchor}
   :dialog       nil                    ; {:kind :remove :id} | {:kind :key :id :pending :message}
   :toast        nil                    ; [[message-key vars] ...]
   :search       {:open?     false
                  :query     ""
                  :mode      :insert    ; :insert (typing) | :normal (browsing the list)
                  :status    :idle      ; :idle :loading :done :error
                  :seq       0
                  :retried?  false
                  :results   []
                  :top       []
                  :highlight 0
                  :error     nil}       ; [:error/network {:at ms}]
   :api          {:servers [] :current 0}
   :keys         {:last-g 0}})

(defonce state (atom initial-state))
