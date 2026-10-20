(ns radiolisp.frontend.views.station
  "Pieces of a station shared by the player and the list."
  (:require [clojure.string :as str]))

(defn format-badge
  "\"MP3 192 kbps\", or a dash when Radio Browser does not know."
  [{:keys [codec bitrate]}]
  (if (and codec bitrate)
    [:span.format [:span.codec codec " "] [:span.num bitrate] " " [:span.unit "kbps"]]
    [:span.dash "—"]))

(defn tags-line [t {:keys [tags]}]
  (if (seq tags)
    (str/join " · " tags)
    [:span.dash (t :station/no-tags)]))

(defn country-code [{:keys [country]}]
  (or country "—"))
