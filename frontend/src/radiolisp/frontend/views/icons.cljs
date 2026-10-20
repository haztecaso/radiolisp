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
