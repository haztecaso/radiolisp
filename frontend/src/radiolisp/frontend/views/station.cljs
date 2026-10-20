(ns radiolisp.frontend.views.station
  "Pieces of a station shared by the player bar, the grid and the palette."
  (:require [clojure.string :as str]))

(defn quality
  "A bordered chip: \"MP3 128 kbps\", \"HLS\", the codec alone, or nothing
  when Radio Browser does not know."
  [{:keys [hls? codec bitrate]}]
  (cond
    hls?                [:span.q "HLS"]
    (and codec bitrate) [:span.q codec [:span.unit.num (str bitrate " kbps")]]
    codec               [:span.q codec]
    :else               nil))

(defn tags-line
  "Tags, optionally after the country code, joined by middle dots."
  ([station] (tags-line station false))
  ([{:keys [country tags]} with-country?]
   (str/join " · " (cond->> tags with-country? (cons country) true (remove nil?)))))
