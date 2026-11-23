(ns axios.cli
  "Órdenes del programa.

    clojure -M -m axios.cli iniciar [--borrar]
    clojure -M -m axios.cli cargar <archivo>
    clojure -M -m axios.cli palabra <término> [en <fecha>]
    clojure -M -m axios.cli traducir \"<pregunta>\"        (parte 3)
    clojure -M -m axios.cli historial [desde <fecha>]    (parte 4)

  Opción global: --bd <ruta> (por defecto axios.db o la variable AXIOS_BD)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [axios.bd :as bd]
            [axios.analisis :as analisis]
            [axios.carga :as carga]
            [axios.consola :as consola]
            [axios.consulta :as consulta]
            [axios.lexico :as lexico]
            [axios.morfologia :as morfologia])
  (:gen-class))

(def ^:dynamic *mostrar* identity)

(defn- decir [& partes] (println (*mostrar* (apply str partes))))

;;; Fechas

(defn fecha-de
  "Acepta '2026-11-18', '2026-11-18 22:30' o '2026-11-18T22:30:05'.
  Si no tiene hora se toma el final del día."
  [s]
  (let [s (str/trim (.replace (str s) \space \T))]
    (cond
      (= 10 (count s)) (str s "T23:59:59")
      (= 16 (count s)) (str s ":59")
      :else s)))

;;; Órdenes

(defn- mostrar-carga [{:keys [archivo tipo nuevas cambios sin-cambios avisos repetido?]}]
  (if repetido?
    (decir "  " archivo ": ya estaba cargado (mismo contenido), no se cambió nada.")
    (do (decir "  " archivo " (" tipo "): " nuevas " nuevas, " cambios " cambios, " sin-cambios " sin cambios.")
        (doseq [a avisos] (decir "    aviso, " a)))))

(def archivos-iniciales
  "Los archivos de datos/ en orden: manual, español, señales y al final correcciones."
  (fn []
    (let [dir (io/file "datos")
          todos (sort (map #(.getPath %) (filter #(.isFile %) (or (.listFiles dir) []))))]
      (concat (filter #(str/includes? % "manual") todos)
              (filter #(str/includes? % "espanol") todos)
              (filter #(str/includes? % "senales") todos)
              (filter #(str/includes? % "correccion") todos)))))

(defn iniciar [ruta borrar?]
  (when (and borrar? (.exists (io/file ruta)))
    (io/delete-file ruta))
  (with-open [c (bd/conectar ruta)]
    (bd/crear-esquema! c)
    (decir "Base de datos lista en " ruta ".")
    (doseq [a (archivos-iniciales)]
      (mostrar-carga (carga/cargar! c a))))
  0)

(defn cargar [ruta archivo]
  (with-open [c (bd/conectar ruta)]
    (bd/crear-esquema! c)
    (mostrar-carga (carga/cargar! c archivo)))
  0)

(defn- lengua-bonita [l] (case l "es" "español" "kethari" "Kethari" "ozh" "Ozh" l))

(defn- describir-analisis [{:keys [lengua tipo forma raiz afijos significado cifras]}]
  (case tipo
    :palabra (str forma " (" (lengua-bonita lengua) ") = " raiz
                  (apply str (for [a afijos] (str " + " (:forma a) " [" (:significado a) "]"))))
    :funcional (str forma " (" (lengua-bonita lengua) ") es una partícula: " significado)
    :numero (str forma " (" (lengua-bonita lengua) ") es un número con las cifras " (str/join ", " cifras))
    (str forma)))

(defn palabra [ruta termino fecha]
  (with-open [c (bd/conectar ruta)]
    (let [{:keys [entradas analisis]} (consulta/palabra c termino fecha)]
      (when fecha (decir "Según lo que el sistema sabía el " fecha ":"))
      (doseq [a analisis] (decir "Análisis: " (describir-analisis a)))
      (if (and (empty? entradas) (empty? analisis))
        (do (decir "El sistema no conoce '" termino "' en ninguna de las tres lenguas.") 1)
        (do
          (doseq [{:keys [palabra equivalentes historial evidencias]} entradas
                  :let [p palabra]]
            (decir)
            (decir (:forma p) " (" (lengua-bonita (:lengua p)) ")  concepto: " (:concepto p)
                   ", categoría: " (:categoria p)
                   (when (seq (:rasgos p)) (str ", rasgos: " (:rasgos p))))
            (decir "  Fuente: " (:fuente p) ". Vigente desde " (:valido-desde p) ".")
            (when (:nota p) (decir "  Nota: " (:nota p)))
            (doseq [[lengua grupo] (group-by :lengua equivalentes)]
              (decir "  En " (lengua-bonita lengua) ": "
                     (str/join ", " (for [e grupo] (str (:forma e) (when (seq (:rasgos e))
                                                                    (when (str/includes? (:rasgos e) "clusividad")
                                                                      (str " (" (:rasgos e) ")"))))))))
            (when (> (count historial) 1)
              (decir "  Historial de significados:")
              (doseq [h historial]
                (decir "    " (:valido-desde h) " a " (or (:valido-hasta h) "hoy") ": concepto " (:concepto h)
                       (when (seq (:rasgos h)) (str ", " (:rasgos h)))
                       " (" (:archivo h) ")")))
            (if (seq evidencias)
              (do (decir "  Aparece en " (count evidencias) " señal(es):")
                  (doseq [s evidencias]
                    (decir "    día " (:dia s) ", " (lengua-bonita (:voz s)) ": " (:transcripcion s)
                           "  ->  " (:salida s))))
              (when (not= "es" (:lengua p))
                (decir "  No aparece en ninguna señal registrada."))))
          0)))))

(defn analizar
  "Muestra lo que hace cada fase de la parte 2 con una pregunta."
  [ruta texto]
  (with-open [c (bd/conectar ruta)]
    (let [{:keys [normalizado lexico tokens correcciones desconocidas avisos]} (analisis/analizar c texto)]
      (decir "Pregunta: " texto)
      (decir)
      (decir "1. Normalizador: " (:texto normalizado))
      (doseq [{:keys [paso de a]} (:cambios normalizado)]
        (decir "     " paso ": '" de "' -> '" a "'"))
      (decir "     ¿es pregunta?: " (if (:pregunta? normalizado) "sí" "no"))
      (decir)
      (decir "2. Autómata (tokens con su posición):")
      (doseq [t lexico] (decir "     " (lexico/describir t)))
      (decir)
      (decir "3. Corrector:")
      (if (and (empty? correcciones) (empty? desconocidas))
        (decir "     todas las palabras están en el vocabulario")
        (do (doseq [{:keys [de a distancia otras]} correcciones]
              (decir "     '" de "' -> '" a "' (distancia " distancia ")"
                     (when (seq otras) (str ", también podría ser: " (str/join ", " otras)))))
            (doseq [{:keys [texto inicio]} desconocidas]
              (decir "     '" texto "' (columna " inicio ") no se parece a ninguna palabra conocida: se deja tal cual"))))
      (decir)
      (decir "4. Morfología:")
      (doseq [t tokens :when (= :PALABRA (:tipo t))]
        (decir "     " (:texto t) ": " (morfologia/describir t)))
      (when (seq avisos)
        (decir)
        (decir "Avisos:")
        (doseq [a avisos] (decir "     " a)))
      0)))

(defn ayuda []
  (decir "Traductor de Axios: español -> Kethari y Ozh")
  (decir)
  (decir "Órdenes:")
  (decir "  iniciar [--borrar]          crea la base de datos y carga los archivos de datos/")
  (decir "  cargar <archivo>            incorpora un archivo de señales o una corrección del manual")
  (decir "  palabra <término> [en <fecha>]   lo que el sistema sabe de una palabra (en una fecha)")
  (decir "  analizar \"<pregunta>\"       muestra normalizador, tokens, correcciones y morfología")
  (decir "  traducir \"<pregunta>\"       traduce a Kethari y Ozh (disponible en la parte 3)")
  (decir "  historial [desde <fecha>]   lo que se tradujo (disponible en la parte 4)")
  (decir)
  (decir "Opción: --bd <ruta> para usar otra base de datos (por defecto axios.db).")
  0)

(defn ejecutar
  "Mira qué orden pidieron y la corre. Devuelve el código de salida."
  [args]
  (let [[ruta args] (let [i (.indexOf ^java.util.List (vec args) "--bd")]
                      (if (neg? i)
                        [bd/ruta-por-defecto args]
                        [(nth args (inc i)) (concat (take i args) (drop (+ i 2) args))]))
        [orden & resto] args]
    (try
      (case orden
        "iniciar"  (iniciar ruta (some #{"--borrar"} resto))
        "cargar"   (if-let [a (first resto)]
                     (cargar ruta a)
                     (do (decir "Uso: cargar <archivo>") 2))
        "palabra"  (let [[t en f] resto]
                     (cond
                       (nil? t) (do (decir "Uso: palabra <término> [en <fecha>]") 2)
                       (and (= en "en") f) (palabra ruta t (fecha-de (str/join " " (drop 2 resto))))
                       :else (palabra ruta (str/join " " resto) nil)))
        "analizar" (if (seq resto)
                     (analizar ruta (str/join " " resto))
                     (do (decir "Uso: analizar \"<pregunta>\"") 2))
        ("traducir" "historial")
        (do (decir "La orden '" orden "' llega en una próxima parte del proyecto.") 2)
        (nil "ayuda" "--ayuda" "-h") (ayuda)
        (do (decir "Orden desconocida '" orden "'.") (ayuda) 2))
      (catch clojure.lang.ExceptionInfo e
        (decir "Error: " (ex-message e))
        1))))

(defn -main [& args]
  (let [{:keys [salida mostrar]} (consola/preparar!)]
    (binding [*out* salida
              *mostrar* mostrar]
      (let [codigo (ejecutar args)]
        (flush)
        (System/exit codigo)))))
