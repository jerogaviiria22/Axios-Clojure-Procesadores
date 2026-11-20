(ns axios.texto
  "Funciones de texto hechas a mano, sin regex (solo se permiten en el normalizador).")

(defn espacio? [c] (Character/isWhitespace (char c)))

(defn dividir
  "Parte s por el carácter sep y le quita los espacios a cada pedazo."
  [s sep]
  (loop [cs (seq (str s)), actual (StringBuilder.), partes []]
    (if-let [c (first cs)]
      (if (= c sep)
        (recur (rest cs) (StringBuilder.) (conj partes (.trim (str actual))))
        (recur (rest cs) (.append actual c) partes))
      (conj partes (.trim (str actual))))))

(defn palabras
  "Parte por espacios."
  [s]
  (loop [cs (seq (str s)), actual (StringBuilder.), acc []]
    (if-let [c (first cs)]
      (if (espacio? c)
        (recur (rest cs) (StringBuilder.) (if (pos? (.length actual)) (conj acc (str actual)) acc))
        (recur (rest cs) (.append actual c) acc))
      (if (pos? (.length actual)) (conj acc (str actual)) acc))))

(def ^:private plegado
  {\á \a \à \a \ä \a \é \e \è \e \ë \e \í \i \ì \i \ï \i
   \ó \o \ò \o \ö \o \ú \u \ù \u \ü \u})

(def ^:private pausas #{\ʔ \' \’ \`})

(defn plegar
  "Forma para buscar: minúsculas, sin tildes, sin ʔ y ü -> u.
  Así 'enka' encuentra 'ʔenka'. La ñ se deja (año no es ano)."
  [s]
  (apply str (for [c (.toLowerCase (.trim (str s)))
                   :when (not (pausas c))]
               (get plegado c c))))
