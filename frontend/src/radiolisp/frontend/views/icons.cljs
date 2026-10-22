(ns radiolisp.frontend.views.icons
  "Inline SVG icons as hiccup.")

(def play
  [:svg {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true"}
   [:path {:d "M4 2.5v11l9-5.5z"}]])

(def pause
  [:svg {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true"}
   [:rect {:x "3.5" :y "2.5" :width "3" :height "11"}]
   [:rect {:x "9.5" :y "2.5" :width "3" :height "11"}]])

(def volume
  [:svg {:viewBox "0 0 18 18" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:path {:d "M3 7h3l4-3v10l-4-3H3z"}]
   [:path {:d "M13 6.5a3.5 3.5 0 010 5M15 4.5a6.5 6.5 0 010 9"}]])

(def mute
  [:svg {:viewBox "0 0 18 18" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:path {:d "M3 7h3l4-3v10l-4-3H3z"}]
   [:path {:d "M13 7l4 4M17 7l-4 4"}]])

(def search
  [:svg {:viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:circle {:cx "7" :cy "7" :r "5"}]
   [:path {:d "M11 11l3.5 3.5"}]])

(def close
  [:svg {:viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:path {:d "M3 3l10 10M13 3L3 13"}]])

(def mark
  "The radiolisp mark: Lisp parentheses drawn as radio waves around a dot."
  [:svg.mark {:viewBox "0 0 28 20" :fill "none" :stroke "currentColor" :stroke-width "2" :aria-hidden "true"}
   [:path {:d "M6 2 Q1 10 6 18"}]
   [:path {:d "M10 5 Q7 10 10 15"}]
   [:rect {:x "12" :y "8" :width "4" :height "4" :fill "currentColor" :stroke "none"}]
   [:path {:d "M18 5 Q21 10 18 15"}]
   [:path {:d "M22 2 Q27 10 22 18"}]])

(def waves
  "The mark's waves; they pulse while a station plays (see _player.scss)."
  [:svg.waves {:viewBox "0 0 22 16" :fill "none" :stroke "currentColor" :stroke-width "1.75" :aria-hidden "true"}
   [:path.w2 {:d "M4 1.5 Q0 8 4 14.5"}]
   [:path.w1 {:d "M7.5 4 Q5 8 7.5 12"}]
   [:rect {:x "9.5" :y "6.5" :width "3" :height "3" :fill "currentColor" :stroke "none"}]
   [:path.w1 {:d "M14.5 4 Q17 8 14.5 12"}]
   [:path.w2 {:d "M18 1.5 Q22 8 18 14.5"}]])

(defn- stroke [view-box & paths]
  (into [:svg {:viewBox view-box :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}]
        (map (fn [d] [:path {:d d}]) paths)))

(def plus (stroke "0 0 16 16" "M8 2v12M2 8h12"))

(def chevron-right (stroke "0 0 16 16" "M6 3.5L10.5 8 6 12.5"))

(def chevron-up (stroke "0 0 16 16" "M3.5 10L8 5.5l4.5 4.5"))

(def chevron-left (stroke "0 0 16 16" "M10 3.5L5.5 8l4.5 4.5"))

(def check (stroke "0 0 16 16" "M3 8.5l3.25 3.25L13 5"))

(def dots
  [:svg {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true"}
   [:circle {:cx "3" :cy "8" :r "1.4"}]
   [:circle {:cx "8" :cy "8" :r "1.4"}]
   [:circle {:cx "13" :cy "8" :r "1.4"}]])

(def ^:private heart-path
  "M9 15.5S2.5 11.6 2.5 6.9A3.4 3.4 0 019 5.1a3.4 3.4 0 016.5 1.8C15.5 11.6 9 15.5 9 15.5z")

(def heart (stroke "0 0 18 18" heart-path))

(def heart-on
  [:svg {:viewBox "0 0 18 18" :fill "currentColor" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:path {:d heart-path}]])

(def external (stroke "0 0 16 16" "M9.5 2.5h4v4M13.5 2.5L7.5 8.5" "M11.5 9.5v4h-9v-9h4"))
(def sliders (stroke "0 0 16 16" "M2 4.5h7M12.5 4.5H14M2 11.5h1.5M7 11.5h7" "M9 3h3.5v3H9z" "M3.5 10H7v3H3.5z"))
(def up (stroke "0 0 16 16" "M8 13V3M3.5 7.5L8 3l4.5 4.5"))
(def thumb (stroke "0 0 16 16" "M5 7v6.5H2.5V7zM5 7l3-4.5c1 0 1.6.8 1.4 1.8L9 6.5h3.6c.9 0 1.5.8 1.3 1.7l-1 4.2c-.2.6-.7 1.1-1.4 1.1H5"))
(def link (stroke "0 0 16 16" "M6.5 9.5l3-3M5 7.5L3.5 9a2.5 2.5 0 003.5 3.5L8.5 11M7.5 5L9 3.5A2.5 2.5 0 0112.5 7L11 8.5"))
(def pulse (stroke "0 0 16 16" "M1.5 8.5h3l2-4 3 7 2-3h3"))
(def trash (stroke "0 0 16 16" "M2.5 4h11M6 4V2.5h4V4M4 4l.7 9.5h6.6L12 4"))
(def download (stroke "0 0 16 16" "M3.5 1.5h6l3 3v10h-9z" "M9.5 1.5v3h3" "M8 7v5M5.5 9.5L8 12l2.5-2.5"))
(def upload (stroke "0 0 16 16" "M3.5 1.5h6l3 3v10h-9z" "M9.5 1.5v3h3" "M8 12V7M5.5 9.5L8 7l2.5 2.5"))

(def key-icon
  [:svg {:viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:rect {:x "1.5" :y "4" :width "13" :height "8"}]
   [:path {:d "M4 7h1M7 7h2M11 7h1M5 9.5h6"}]])

(def globe
  [:svg {:viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:circle {:cx "8" :cy "8" :r "6.25"}]
   [:path {:d "M1.75 8h12.5M8 1.75c-1.9 1.7-2.75 3.8-2.75 6.25S6.1 12.55 8 14.25M8 1.75c1.9 1.7 2.75 3.8 2.75 6.25S9.9 12.55 8 14.25"}]])

(def code (stroke "0 0 16 16" "M5.5 4L1.5 8l4 4M10.5 4l4 4-4 4"))

(def funnel (stroke "0 0 16 16" "M2 3h12l-4.75 5.5v4.5l-2.5 1V8.5z"))

(def moon (stroke "0 0 16 16" "M13.5 9.5A5.75 5.75 0 016.5 2.5a5.75 5.75 0 107 7z"))

(def sun
  [:svg {:viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :aria-hidden "true"}
   [:circle {:cx "8" :cy "8" :r "3"}]
   [:path {:d "M8 1v1.75M8 13.25V15M1 8h1.75M13.25 8H15M3.05 3.05l1.24 1.24M11.71 11.71l1.24 1.24M3.05 12.95l1.24-1.24M11.71 4.29l1.24-1.24"}]])
