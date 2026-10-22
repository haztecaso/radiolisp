(ns deploy
  "`bb deploy`: copies dist/ to the server, restarts the service and checks
  that it answers. If it does not, the previous deploy goes back.

  The target is not in the repository: DEPLOY_SERVER (an ssh destination)
  and DEPLOY_URL (where the app answers) come from the environment or from
  `.env` at the repo root (see `.env.example`)."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :refer [shell]]
            [clojure.string :as str]))

(defn- dotenv
  "The KEY=VALUE lines of `.env`, if there is one; # starts a comment."
  []
  (when (fs/exists? ".env")
    (into {}
          (keep (fn [line]
                  (let [line (str/trim line)]
                    (when-let [[_ k v] (and (not (str/starts-with? line "#"))
                                            (re-matches #"([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)" line))]
                      [k (str/replace v #"^\"(.*)\"$" "$1")]))))
          (fs/read-all-lines ".env"))))

(defn- setting [k]
  (or (System/getenv k)
      (get (dotenv) k)
      (do (println (str k " is not set: add it to .env (see .env.example)"))
          (System/exit 1))))

(def root "/var/www/radiolisp")
(def service "radiolisp.service")

(defn- remote [server & commands]
  (apply shell "ssh" server commands))

(defn- healthy? [url]
  (some (fn [_]
          (Thread/sleep 1000)
          (try (= 200 (:status (http/get (str url "/api/health") {:throw false :timeout 2000})))
               (catch Exception _ false)))
        (range 30)))

(defn run [_]
  (let [server (setting "DEPLOY_SERVER")
        url    (setting "DEPLOY_URL")]
    (println "Keeping the current deploy in" (str root "/previous"))
    (remote server (format "rsync -a --delete %1$s/current/ %1$s/previous/" root))
    (shell "rsync -az --delete dist/" (str server ":" root "/current/"))
    (remote server "sudo systemctl restart" service)
    (if (healthy? url)
      (println "Deployed:" url)
      (do (println "No answer from" (str url "/api/health") "- restoring the previous deploy")
          (remote server (format "rsync -a --delete %1$s/previous/ %1$s/current/" root))
          (remote server "sudo systemctl restart" service)
          (System/exit 1)))))
