(ns radiolisp.frontend.store
  "The single source of truth: one atom with the whole app state.")

(def initial-state
  {:locale       :en
   :help?        false
   :theme        {:current :light       ; :light | :dark
                  :chosen? false}       ; true once picked with the button: the browser's no longer applies
   :player       {:station nil
                  :status  :stopped     ; :stopped :connecting :playing :reconnecting :paused :error
                  :played? false        ; the station in the player played at least once: a cut reconnects
                  :retries 0            ; reconnections tried since it last played
                  :error   nil          ; [:error/stream {}] | [:error/unsupported {}]
                  :volume  100
                  :muted?  false
                  :now-playing nil}             ; {:id :title} while following the station's titles (backend)
   :favorites    {:items    []          ; [{:id :name :key :custom-name?}], in grid order
                  :status   :idle       ; :idle (storage unread) :pending :loading :done :error
                  :retried? false
                  :missing  #{}}        ; ids Radio Browser no longer knows
   :stations     {}                     ; id → normalized station
   :cache        {}                     ; id → {:name :favicon}: favourites as last seen, drawn until :stations has them
   :art          {}                     ; id → {:w :h} (natural size) or :broken
   :probes       {}                     ; id → {:src :alpha? :dark? :edge}: what the artwork detection found
   :installable? false                  ; the browser offers to install the app (Chrome, Edge)
   :panel?       false                  ; the «sonando ahora» panel is open
   :filter       ""
   :filter-open? false                  ; the filter field shows only while in use
   :drag         nil                    ; {:id :over} while a tile is dragged
   :file-drag?   false                  ; true while a file is dragged over the page
   :menu         nil                    ; {:kind :station :id :anchor} | {:kind :list :anchor}
   :dialog       nil                    ; {:kind :remove :id} | {:kind :key :id :pending :message} | {:kind :customize :id :name :display :color} | …
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
