(ns axios.pruebas-parte1
  "Pruebas de la parte 1."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [axios.bd :as bd]
            [axios.carga :as carga]
            [axios.consulta :as consulta]
            [axios.cli :as cli]))

(defn- con-bd
  "Crea una BD temporal para la prueba. La hora avanza un minuto en cada carga
  para poder probar las versiones."
  [f]
  (let [archivo (java.io.File/createTempFile "axios-prueba" ".db")
        minuto  (atom 0)]
    (try
      (binding [bd/*ahora* (fn [] (format "2026-11-%02dT10:%02d:00" 20 (swap! minuto inc)))]
        (with-open [c (bd/conectar (.getPath archivo))]
          (bd/crear-esquema! c)
          (f c)))
      (finally (io/delete-file archivo true)))))

(defn- archivo-temporal [contenido]
  (let [f (java.io.File/createTempFile "axios-datos" ".txt")]
    (spit f contenido :encoding "UTF-8")
    (.getPath f)))

(defn- cargar-todo! [c]
  (doseq [a ["datos/manual_v0.7.txt" "datos/senales_semana1.txt" "datos/correccion_equipo_2026-11-18.txt"]]
    (carga/cargar! c a)))

(deftest carga-del-manual
  (con-bd
   (fn [c]
     (let [r (carga/cargar! c "datos/manual_v0.7.txt")]
       (is (= "manual" (:tipo r)))
       (is (empty? (:avisos r)))
       (testing "32 conceptos del léxico en las tres lenguas"
         (is (= 32 (count (bd/consultar c "SELECT DISTINCT concepto FROM palabra WHERE categoria <> 'digito'")))))
       (testing "las formas con varias palabras se separan: tú, usted"
         (is (= #{"tú" "usted"} (set (map :forma (bd/consultar c "SELECT forma FROM palabra WHERE lengua='es' AND concepto='tu'"))))))
       (testing "Kethari solo tiene dígitos del 0 al 5; Ozh del 0 al 9"
         (is (= 6 (count (bd/consultar c "SELECT * FROM palabra WHERE lengua='kethari' AND categoria='digito'"))))
         (is (= 10 (count (bd/consultar c "SELECT * FROM palabra WHERE lengua='ozh' AND categoria='digito'")))))
       (testing "las reglas quedan en la base de datos, no en el código"
         (is (= "sujeto objeto verbo" (get (consulta/reglas c "kethari" nil) "orden")))
         (is (= "verbo sujeto objeto" (get (consulta/reglas c "ozh" nil) "orden"))))))))

(deftest no-carga-dos-veces-lo-mismo
  (con-bd
   (fn [c]
     (carga/cargar! c "datos/manual_v0.7.txt")
     (let [r (carga/cargar! c "datos/manual_v0.7.txt")]
       (is (:repetido? r))
       (is (= 1 (count (bd/consultar c "SELECT * FROM carga"))))))))

(deftest carga-de-senales
  (con-bd
   (fn [c]
     (carga/cargar! c "datos/manual_v0.7.txt")
     (let [r (carga/cargar! c "datos/senales_semana1.txt")]
       (is (= "senales" (:tipo r)))
       (is (= 15 (:nuevas r)))
       (is (= 1 (count (bd/consultar c "SELECT * FROM senal WHERE voz = 'desconocida' AND transcripcion IS NULL"))))
       (is (= "ʔoka" (:forma (first (bd/consultar c "SELECT o.forma FROM ocurrencia o JOIN senal s ON s.id=o.senal_id WHERE s.dia=1 AND s.voz='ozh' AND o.posicion=0")))))))))

(deftest senales-sin-directivas-y-con-otro-separador
  ;; El archivo de la semana 2 puede llegar con otro formato: se deduce el tipo.
  (con-bd
   (fn [c]
     (carga/cargar! c "datos/manual_v0.7.txt")
     (let [a (archivo-temporal "Día\tVoz\tTranscripción\tSalida\n8\tOZH\tha zi oru thüm\tÉl es un mundo. [zi]\n8\tKethari\tir vael mi\tÉl es un mundo.\n")
           r (carga/cargar! c a)]
       (is (= "senales" (:tipo r)))
       (is (= 2 (:nuevas r)))
       (is (= 1 (count (:avisos r))) "la fila de encabezado se reporta y se salta")
       (is (= #{"ozh" "kethari"} (set (map :voz (bd/consultar c "SELECT voz FROM senal WHERE dia = 8")))))))))

(deftest versiones-del-lexico
  (con-bd
   (fn [c]
     (cargar-todo! c)
     ;; manual a las 10:01, señales 10:02, corrección 10:03
     (testing "la corrección no borra: cierra la versión anterior y crea otra"
       (let [h (bd/consultar c "SELECT * FROM palabra WHERE lengua='kethari' AND forma='anul' ORDER BY id")]
         (is (= 2 (count h)))
         (is (= "2026-11-20T10:03:00" (:valido-hasta (first h))))
         (is (nil? (:valido-hasta (second h))))))
     (testing "qué significaba anul en cada momento"
       (let [antes (-> (consulta/palabra c "anul" "2026-11-20T10:02:30") :entradas first :palabra :rasgos)
             despues (-> (consulta/palabra c "anul" "2026-11-20T10:30:00") :entradas first :palabra :rasgos)]
         (is (not (str/includes? antes "clusividad")))
         (is (str/includes? despues "clusividad=inclusivo"))))
     (testing "la base de los números de Kethari también tiene versiones"
       (is (nil? (get (consulta/reglas c "kethari" "2026-11-20T10:02:30") "numero.base")))
       (is (= "6" (get (consulta/reglas c "kethari" nil) "numero.base"))))
     (testing "antes de cargar el manual el sistema no sabía nada"
       (is (empty? (:entradas (consulta/palabra c "nave" "2026-11-20T10:00:30"))))))))

(deftest orden-palabra
  (con-bd
   (fn [c]
     (cargar-todo! c)
     (testing "una palabra en español muestra sus equivalentes"
       (let [{:keys [entradas]} (consulta/palabra c "nave" nil)
             eq (set (map (juxt :lengua :forma) (:equivalentes (first entradas))))]
         (is (contains? eq ["kethari" "kum"]))
         (is (contains? eq ["ozh" "gor"]))))
     (testing "se puede buscar sin tildes y sin ʔ"
       (is (= "cuándo" (-> (consulta/palabra c "cuando" nil) :entradas first :palabra :forma)))
       (is (= "ʔenka" (-> (consulta/palabra c "enka" nil) :entradas first :palabra :forma))))
     (testing "una forma con afijos se analiza con las reglas de la BD"
       (let [a (first (:analisis (consulta/palabra c "nukorok" nil)))]
         (is (= "kor" (:raiz a)))
         (is (= ["nu" "ok"] (sort (map :forma (:afijos a)))))))
     (testing "las evidencias salen de las señales"
       (is (= [3] (map :dia (-> (consulta/palabra c "kum" nil) :entradas first :evidencias))))
       (is (= [7] (map :dia (-> (consulta/palabra c "sab" nil) :entradas first :evidencias)))))
     (testing "una palabra desconocida no se inventa"
       (let [r (consulta/palabra c "xilofono" nil)]
         (is (empty? (:entradas r)))
         (is (empty? (:analisis r))))))))

(deftest explicar-formas-de-las-senales
  (con-bd
   (fn [c]
     (cargar-todo! c)
     (is (= :funcional (:tipo (consulta/explicar c "kethari" "ka" nil))))
     (is (= :funcional (:tipo (consulta/explicar c "ozh" "zi" nil))))
     (is (= ["2" "3"] (:cifras (consulta/explicar c "kethari" "ra-sel" nil))))
     (is (= "sur" (:raiz (consulta/explicar c "ozh" "ʔasur" nil))))
     (testing "todas las palabras de las señales de la semana 1 tienen respaldo"
       (let [sin-respaldo (for [{:keys [voz forma]} (bd/consultar c "SELECT s.voz, o.forma FROM ocurrencia o JOIN senal s ON s.id=o.senal_id")
                                :when (nil? (consulta/explicar c voz forma nil))]
                            forma)]
         (is (empty? sin-respaldo) (str "sin respaldo: " (vec sin-respaldo))))))))

(deftest linea-de-comandos
  (let [archivo (java.io.File/createTempFile "axios-cli" ".db")
        ruta    (.getPath archivo)
        minuto  (atom 0)
        correr  (fn [& args]
                  (let [codigo (atom nil)
                        texto  (with-out-str (reset! codigo (cli/ejecutar (into ["--bd" ruta] args))))]
                    [@codigo texto]))]
    (try
      (binding [bd/*ahora* (fn [] (format "2026-11-20T10:%02d:00" (swap! minuto inc)))]
        (testing "iniciar carga los tres archivos de datos/"
          (let [[codigo texto] (correr "iniciar" "--borrar")]
            (is (zero? codigo))
            (is (str/includes? texto "manual_v0.7.txt (manual)"))
            (is (str/includes? texto "correccion_equipo_2026-11-18.txt (correccion): 1 nuevas, 2 cambios"))))
        (testing "cargar el mismo archivo otra vez no cambia nada"
          (is (str/includes? (second (correr "cargar" "datos/manual_v0.7.txt")) "ya estaba cargado")))
        (testing "palabra muestra el historial de significados"
          (let [[codigo texto] (correr "palabra" "anul")]
            (is (zero? codigo))
            (is (str/includes? texto "Historial de significados"))
            (is (str/includes? texto "clusividad=inclusivo"))))
        (testing "palabra ... en <fecha> responde con lo que se sabía entonces"
          (let [[_ texto] (correr "palabra" "anul" "en" "2026-11-20" "10:02")]
            (is (str/includes? texto "Según lo que el sistema sabía el 2026-11-20T10:02:59"))
            (is (not (str/includes? (subs texto 0 (or (str/index-of texto "Historial") (count texto))) "clusividad=inclusivo")))))
        (testing "una palabra desconocida devuelve código 1 y lo dice"
          (let [[codigo texto] (correr "palabra" "xilofono")]
            (is (= 1 codigo))
            (is (str/includes? texto "no conoce"))))
        (testing "un archivo que no existe se explica sin romper el programa"
          (let [[codigo texto] (correr "cargar" "no-existe.txt")]
            (is (= 1 codigo))
            (is (str/includes? texto "No existe el archivo")))))
      (finally (io/delete-file archivo true)))))
