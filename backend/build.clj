(ns build
  "The backend uberjar: `clojure -T:build uber`, or `bb uberjar` from the root."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file "../dist/radiolisp-backend.jar")

(defn clean [_]
  (b/delete {:path "target/classes"}))

(defn uber
  "AOT-compiles `radiolisp.backend.main` and writes `dist/radiolisp-backend.jar`
  with every dependency. It does not include the frontend: the jar serves the
  `public` folder next to it, or `PUBLIC_DIR`."
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :ns-compile '[radiolisp.backend.main] :class-dir class-dir})
    (b/uber {:class-dir class-dir :uber-file jar-file :basis basis :main 'radiolisp.backend.main})
    (println "wrote" jar-file)))
