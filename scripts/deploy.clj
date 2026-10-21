(ns deploy
  "`bb deploy`: copies dist/ to the server, restarts the service and checks
  that it answers. If it does not, the previous deploy goes back."
  (:require [babashka.http-client :as http]
            [babashka.process :refer [shell]]))

(def server (or (System/getenv "DEPLOY_SERVER") "user@host"))
(def url (or (System/getenv "DEPLOY_URL") "https://radio.example.org"))
;; The layout and the service come from /etc/nixos/radiolisp.nix.
(def root "/var/www/radiolisp")
(def service "radiolisp.service")

(defn- remote [& commands]
  (apply shell "ssh" server commands))

(defn- healthy? []
  (some (fn [_]
          (Thread/sleep 1000)
          (try (= 200 (:status (http/get (str url "/api/health") {:throw false :timeout 2000})))
               (catch Exception _ false)))
        (range 30)))

(defn run [_]
  (println "Keeping the current deploy in" (str root "/previous"))
  (remote (format "rsync -a --delete %1$s/current/ %1$s/previous/" root))
  (shell "rsync -az --delete dist/" (str server ":" root "/current/"))
  (remote "sudo systemctl restart" service)
  (if (healthy?)
    (println "Deployed:" url)
    (do (println "No answer from" (str url "/api/health") "- restoring the previous deploy")
        (remote (format "rsync -a --delete %1$s/previous/ %1$s/current/" root))
        (remote "sudo systemctl restart" service)
        (System/exit 1))))
