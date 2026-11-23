(ns axios.pruebas-parte2
  "Pruebas de la parte 2: normalizador, autómata, corrector y morfología."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing are use-fixtures]]
            [axios.analisis :as analisis]
            [axios.bd :as bd]
            [axios.carga :as carga]
            [axios.corrector :as corrector]
            [axios.espanol :as esp]
            [axios.lexico :as lexico]
            [axios.morfologia :as morfologia]
            [axios.normalizador :as normalizador]))

(def ^:dynamic *con* nil)

(defn- con-conocimiento
  "Carga los datos una sola vez en una BD temporal y deja el conocimiento del español en *con*."
  [f]
  (let [archivo (java.io.File/createTempFile "axios-p2" ".db")]
    (try
      (with-open [c (bd/conectar (.getPath archivo))]
        (bd/crear-esquema! c)
        (doseq [a ["datos/manual_v0.7.txt" "datos/espanol.txt" "datos/correccion_equipo_2026-11-18.txt"]]
          (carga/cargar! c a))
        (binding [*con* (esp/cargar c nil)]
          (f)))
      (finally (io/delete-file archivo true)))))

(use-fixtures :once con-conocimiento)

(defn- norm [s] (:texto (normalizador/normalizar *con* s)))

(deftest normalizador
  (testing "minúsculas, signos y espacios"
    (is (= "¿cuándo vendrán ustedes?" (norm "  ¿Cuándo   VENDRÁN ustedes?  ")))
    (is (= "¿qué es la tierra?" (norm "¿Qué es \"la tierra\"?"))))
  (testing "números en palabras hasta 9.999"
    (are [entrada salida] (= salida (norm entrada))
      "quince naves"                          "15 naves"
      "dos mil quince"                        "2015"
      "ciento veintitrés"                     "123"
      "cien"                                  "100"
      "mil"                                   "1000"
      "treinta y cinco ciclos"                "35 ciclos"
      "nueve mil novecientos noventa y nueve" "9999"
      "doscientos cuarenta y seis"            "246"
      "veintiún mensajes"                     "21 mensajes"))
  (testing "un y una solos son artículos, no el número 1"
    (is (= "una nave" (norm "una nave")))
    (is (= "un mensaje y 2 naves" (norm "un mensaje y dos naves"))))
  (testing "cifras con punto o coma de miles"
    (is (= "en 2015" (norm "en 2.015")))
    (is (= "1500 naves" (norm "1,500 naves"))))
  (testing "sabe si es pregunta"
    (is (:pregunta? (normalizador/normalizar *con* "¿vienen?")))
    (is (not (:pregunta? (normalizador/normalizar *con* "vienen."))))))

(deftest automata
  (let [ts (lexico/tokens "¿cuántas naves enviaron 15 mensajes?")]
    (is (= [:ABRE :PALABRA :PALABRA :PALABRA :NUMERO :PALABRA :CIERRA] (map :tipo ts)))
    (testing "cada token trae su posición (columnas desde 1)"
      (is (= {:tipo :PALABRA :texto "naves" :inicio 10 :fin 14} (nth ts 2)))
      (is (= 15 (:valor (nth ts 4))))))
  (testing "un carácter raro da un token ERROR y el resto sigue"
    (is (= [:ERROR :PALABRA] (map :tipo (lexico/tokens "#hola")))))
  (testing "número pegado a una palabra: dos tokens"
    (is (= [:NUMERO :PALABRA] (map :tipo (lexico/tokens "15naves")))))
  (testing "la tabla de transiciones es la del diagrama"
    (is (= :q1 (lexico/transiciones [:q0 :letra])))
    (is (nil? (lexico/transiciones [:q1 :digito])))))

(deftest levenshtein
  (are [a b d] (= d (corrector/levenshtein a b))
    "nave" "nave" 0
    "nabe" "nave" 1
    "nae" "nave" 1
    "naver" "nave" 1
    "kitten" "sitting" 3
    "" "abc" 3))

(deftest corrector
  (let [corregir #(corrector/corregir *con* (lexico/tokens %))]
    (testing "corrige letras cambiadas"
      (is (= [{:de "bendrán" :a "vendrán" :distancia 1 :inicio 1 :fin 7}] (:correcciones (corregir "bendrán"))))
      (is (= "naves" (:a (first (:correcciones (corregir "nabes")))))))
    (testing "las palabras sin tilde no se cuentan como error"
      (is (empty? (:correcciones (corregir "cuando vendran"))))
      (is (= "interrogativo" (:categoria (first (:lecturas (first (:tokens (corregir "cuando")))))))))
    (testing "lo que no se parece a nada se deja tal cual y se marca"
      (let [r (corregir "marte")]
        (is (empty? (:correcciones r)))
        (is (= ["marte"] (map :texto (:desconocidas r))))))
    (testing "las palabras muy cortas no se corrigen"
      (is (empty? (:correcciones (corregir "cu")))))))

(defn- verbo [palabra] (set (map #(select-keys % [:infinitivo :tiempo :persona :numero])
                                (morfologia/analizar-verbo *con* palabra))))

(deftest morfologia-verbos
  (are [palabra esperado] (contains? (verbo palabra) esperado)
    "vendrán"  {:infinitivo "venir" :tiempo "futuro" :persona "3" :numero "pl"}
    "enviaron" {:infinitivo "enviar" :tiempo "pasado" :persona "3" :numero "pl"}
    "envió"    {:infinitivo "enviar" :tiempo "pasado" :persona "3" :numero "sg"}
    "vimos"    {:infinitivo "ver" :tiempo "pasado" :persona "1" :numero "pl"}
    "quieren"  {:infinitivo "querer" :tiempo "presente" :persona "3" :numero "pl"}
    "es"       {:infinitivo "ser" :tiempo "presente" :persona "3" :numero "sg"}
    "esperaremos" {:infinitivo "esperar" :tiempo "futuro" :persona "1" :numero "pl"}
    "escuchaban"  {:infinitivo "escuchar" :tiempo "pasado" :persona "3" :numero "pl"}
    "llegué"   {:infinitivo "llegar" :tiempo "pasado" :persona "1" :numero "sg"}
    "visitas"  {:infinitivo "visitar" :tiempo "presente" :persona "2" :numero "sg"})
  (testing "hablamos puede ser presente o pasado: se dejan las dos lecturas"
    (is (= #{"presente" "pasado"} (set (map :tiempo (morfologia/analizar-verbo *con* "hablamos"))))))
  (testing "era sirve para 1.ª y 3.ª persona"
    (is (= #{"1" "3"} (set (map :persona (morfologia/analizar-verbo *con* "era"))))))
  (testing "llegar es otra forma del concepto venir del manual"
    (is (= "venir" (:concepto (first (morfologia/analizar-verbo *con* "llegaron")))))))

(deftest perifrasis-ir-a
  (let [r (analisis/analizar-con *con* "¿Van a llegar mañana?")
        v (first (filter :perifrasis (:tokens r)))]
    (is (= "van a llegar" (:texto v)))
    (is (= {"infinitivo" "llegar" "forma" "perifrasis" "tiempo" "futuro" "persona" "3" "numero" "pl"}
           (:rasgos (first (:lecturas v)))))
    (is (= [2 13] [(:inicio v) (:fin v)])))
  (testing "'a' sin infinitivo después no es perífrasis"
    (is (not-any? :perifrasis (:tokens (analisis/analizar-con *con* "van a la tierra"))))))

(deftest sustantivos-y-plurales
  (are [palabra concepto numero]
       (some #(and (= concepto (:concepto %)) (= numero (get-in % [:rasgos "numero"])))
             (esp/lecturas *con* palabra))
    "naves" "nave" "pl"
    "nave" "nave" "sg"
    "luces" "luz" "pl"
    "mensajes" "mensaje" "pl"
    "mundos" "planeta" "pl"))

(deftest analisis-completo
  (let [r (analisis/analizar-con *con* "¿Cuántas nabes enviaron dos mil quince mensajes a Marte?")]
    (is (= "¿cuántas nabes enviaron 2015 mensajes a marte?" (get-in r [:normalizado :texto])))
    (is (= [["nabes" "naves"]] (map (juxt :de :a) (:correcciones r))))
    (is (= ["marte"] (map :texto (:desconocidas r))))
    (testing "ninguna palabra se pierde: la desconocida sigue en los tokens"
      (is (some #(= "marte" (:texto %)) (:tokens r)))))
  (testing "aviso si el número pasa de 9.999"
    (is (some #(str/includes? % "10000") (:avisos (analisis/analizar-con *con* "diez mil naves"))))))
