(ns service-worker
  "The service worker of a web release: `frontend/resources/sw.js` with the
  shell's files and a version that changes whenever one of them does."
  (:require [babashka.fs :as fs]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(defn- shell-files
  "The files the page needs, relative to `dir`: the page itself as \"./\",
  then everything else but shadow-cljs's manifests and the worker."
  [dir]
  (->> (fs/glob dir "**")
       (filter fs/regular-file?)
       (map #(fs/unixify (fs/relativize dir %)))
       (remove #{"index.html" "sw.js"})
       (remove #(re-find #"\.(edn|json)$|^js/cljs-runtime/" %))
       sort
       (cons "./")))

(defn- version [dir files]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [f files]
      (.update digest (.getBytes ^String f "UTF-8"))
      (.update digest ^bytes (fs/read-all-bytes (fs/path dir (if (= "./" f) "index.html" f)))))
    (subs (str/join (map #(format "%02x" %) (.digest digest))) 0 12)))

(defn write!
  "Writes `dir`/sw.js for the release in `dir`."
  [dir]
  (let [files (shell-files dir)]
    (spit (str (fs/path dir "sw.js"))
          (-> (slurp "frontend/resources/sw.js")
              (str/replace "__VERSION__" (version dir files))
              (str/replace "__FILES__" (str "[" (str/join ", " (map #(str \" % \") files)) "]"))))))
