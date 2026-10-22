(ns radiolisp.frontend.stream-url
  "Which URLs `radiolisp.frontend.audio` tries for a station, in order.
  Pure, so it can be tested apart from the audio element."
  (:require [clojure.string :as str]))

(def proxy-prefix
  "The backend's proxy, on the same origin as the app."
  "/api/stream/")

(defn http? [url]
  (str/starts-with? url "http://"))

(defn proxy? [url]
  (str/starts-with? url proxy-prefix))

(defn candidates
  "The URLs to try for station `id` streaming at `url`. An `http://` stream
  is first tried in its https form; then, on an http page, as it is, and on
  an https page (where the browser would block it) through the proxy, when
  `backend?` says there is one. HLS cannot go through the proxy."
  [url id hls? secure-page? backend?]
  (let [https-form (str "https://" (subs url (min (count url) (count "http://"))))]
    (cond
      (not (http? url))   [url]
      (not secure-page?)  [https-form url]
      (or hls? (nil? id) (not backend?)) [https-form]
      :else               [https-form (str proxy-prefix id)])))
