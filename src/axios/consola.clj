(ns axios.consola
  "Para que se vean las tildes y la ʔ en la consola de Windows.
  Se pone la consola en UTF-8 (chcp 65001) y al salir se deja como estaba.
  Si no se puede, la ʔ se muestra como '."
  (:require [clojure.string :as str])
  (:import [java.nio.charset Charset]))

(defn- windows? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "windows"))

(defn- chcp
  "Corre chcp. Sin argumento solo dice la página que hay."
  [& [pagina]]
  (try
    (let [pb (ProcessBuilder. ^java.util.List (cond-> ["cmd" "/c" "chcp"] pagina (conj (str pagina))))
          _  (.redirectInput pb java.lang.ProcessBuilder$Redirect/INHERIT)
          p  (.start pb)
          s  (slurp (.getInputStream p))]
      (.waitFor p)
      (let [n (apply str (filter #(Character/isDigit (char %)) s))]
        (when (seq n) n)))
    (catch Exception _ nil)))

(defn preparar!
  "Devuelve la salida que hay que usar y una función que cambia la ʔ si no se puede mostrar."
  []
  (let [cs (if (windows?)
             (let [original (chcp)]
               (if (= "65001" (chcp 65001))
                 (do (when (and original (not= original "65001"))
                       (.addShutdownHook (Runtime/getRuntime) (Thread. #(chcp original))))
                     (Charset/forName "UTF-8"))
                 (or (some #(try (Charset/forName %) (catch Exception _ nil))
                           [(str "IBM" original) (str "Cp" original)])
                     (Charset/defaultCharset))))
             (or (some-> (System/getProperty "stdout.encoding")
                         (#(try (Charset/forName %) (catch Exception _ nil))))
                 (Charset/forName "UTF-8")))
        puede-pausa? (.canEncode (.newEncoder cs) "ʔ")]
    {:salida (java.io.PrintWriter. (java.io.OutputStreamWriter. System/out cs) true)
     :mostrar (if puede-pausa? identity #(.replace (str %) "ʔ" "'"))}))
