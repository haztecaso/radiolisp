(ns radiolisp.common.format
  "Pure formatting helpers shared by every platform.")

(defn- pad2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn clock
  "Formats a number of seconds as hh:mm:ss. Negative input counts as zero."
  [seconds]
  (let [s (max 0 (int seconds))]
    (str (pad2 (quot s 3600)) ":" (pad2 (mod (quot s 60) 60)) ":" (pad2 (mod s 60)))))

(defn elapsed
  "Whole seconds from `started-at` to `now`, both in milliseconds.
  Zero when playback has not started or the clock went backwards."
  [started-at now]
  (if started-at
    (max 0 (quot (- now started-at) 1000))
    0))
