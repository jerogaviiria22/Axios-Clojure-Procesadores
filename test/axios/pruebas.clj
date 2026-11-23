(ns axios.pruebas
  "Corre todas las pruebas (clojure -M:test)"
  (:require [clojure.test :as t]
            [axios.pruebas-parte1]
            [axios.pruebas-parte2]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'axios.pruebas-parte1 'axios.pruebas-parte2)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
