(ns canvaslms.core
  (:require [canvaslms.cli.main :as cli])
  (:gen-class))

(defn -main [& args]
  (let [status (cli/run! args)]
    (shutdown-agents)
    (when-not (zero? status) (System/exit status))))
