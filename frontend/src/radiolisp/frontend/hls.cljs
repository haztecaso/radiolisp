(ns radiolisp.frontend.hls
  "hls.js, alone in the shadow-cljs module :hls so that it only downloads
  when a browser without native HLS plays an HLS station."
  (:require ["hls.js" :default Hls]))

(defn start!
  "Plays the HLS stream `url` in `audio` through Media Source Extensions and
  calls `on-fatal` when hls.js gives up. Returns the Hls instance (destroy it
  to stop), or nil when the browser has no MSE."
  [^js audio url on-fatal]
  (when (.isSupported Hls)
    (let [hls (Hls. #js {:lowLatencyMode false})]
      (.on hls (.. Hls -Events -ERROR)
           (fn [_ ^js data] (when (.-fatal data) (on-fatal))))
      (.on hls (.. Hls -Events -MANIFEST_PARSED)
           (fn [] (-> (.play audio) (.catch (fn [_] nil)))))
      (.loadSource hls url)
      (.attachMedia hls audio)
      hls)))
