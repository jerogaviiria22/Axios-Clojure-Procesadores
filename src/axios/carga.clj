(ns axios.carga
  "Orden cargar: lee un archivo de datos/ (manual, corrección o señales) y lo
  mete a la base de datos. El formato está en el README."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [axios.bd :as bd]
            [axios.texto :as t])
  (:import [java.security MessageDigest]))

(def lenguas #{"es" "kethari" "ozh"})

;;; Lectura del archivo

(defn- huella
  "Hash SHA-256 del archivo, para no cargarlo dos veces (D-08)."
  [^String texto]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes texto "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- separador
  "Adivina el separador: | , tab o ;"
  [linea]
  (cond (str/includes? linea "|") \|
        (str/includes? linea "\t") \tab
        (str/includes? linea ";") \;
        :else \|))

(defn leer
  "Lee el texto y saca las directivas (@...) y las líneas con datos."
  [texto]
  (let [texto (if (str/starts-with? texto "﻿") (subs texto 1) texto)]
    (loop [[l & resto] (map-indexed vector (str/split-lines texto))
           seccion nil
           acc {:directivas {} :entradas []}]
      (if-not l
        acc
        (let [[i linea] l
              s (str/trim linea)]
          (cond
            (or (str/blank? s) (str/starts-with? s "#"))
            (recur resto seccion acc)

            (str/starts-with? s "@")
            (let [[k & v] (t/palabras (subs s 1))]
              (recur resto seccion (assoc-in acc [:directivas (str/lower-case k)] (str/join " " v))))

            (and (str/starts-with? s "[") (str/ends-with? s "]"))
            (recur resto (str/lower-case (str/trim (subs s 1 (dec (count s))))) acc)

            :else
            (recur resto seccion
                   (update acc :entradas conj {:seccion seccion :linea (inc i)
                                               :campos (t/dividir s (separador s))}))))))))

(defn- numero? [s] (and (seq s) (every? #(Character/isDigit (char %)) s)))

(defn- parece-senal? [{:keys [campos]}]
  (and (>= (count campos) 3) (numero? (first campos))))

(defn- encabezado?
  "¿Es la fila de títulos? (Día | Voz | ...)"
  [{:keys [campos]}]
  (= "dia" (t/plegar (str/lower-case (str/trim (str (first campos)))))))

(defn tipo-de
  "Si no hay @tipo y todas las líneas empiezan con un número, son señales."
  [{:keys [directivas entradas]}]
  (let [datos (remove encabezado? entradas)]
    (or (some-> (get directivas "tipo") str/lower-case)
        (when (and (seq datos) (every? parece-senal? datos)) "senales"))))

;;; Escritura con versiones

(defn- normalizar-rasgos [s]
  (str/join " " (map (fn [[k v]] (str k "=" v)) (bd/rasgos->mapa s))))

(defn guardar-palabra!
  "Guarda la palabra. Si ya estaba igual no hace nada; si cambió, crea otra versión.
  Devuelve :nueva, :cambio o :igual."
  [c carga-id fecha {:keys [lengua forma concepto categoria rasgos fuente nota]}]
  (let [rasgos (normalizar-rasgos rasgos)
        actual (first (bd/consultar c "SELECT * FROM palabra WHERE lengua = ? AND forma = ? AND valido_hasta IS NULL"
                                    lengua forma))]
    (if (and actual
             (= (:concepto actual) concepto)
             (= (:categoria actual) categoria)
             (= (or (:rasgos actual) "") rasgos))
      :igual
      (do
        (when actual
          (bd/ejecutar! c "UPDATE palabra SET valido_hasta = ? WHERE id = ?" fecha (:id actual)))
        (bd/ejecutar! c (str "INSERT INTO palabra (lengua, forma, forma_plegada, concepto, categoria, rasgos,"
                             " fuente, nota, carga_id, valido_desde) VALUES (?,?,?,?,?,?,?,?,?,?)")
                      lengua forma (bd/plegar forma) concepto categoria rasgos fuente nota carga-id fecha)
        (if actual :cambio :nueva)))))

(defn guardar-regla! [c carga-id fecha {:keys [lengua clave valor nota]}]
  (let [actual (first (bd/consultar c "SELECT * FROM regla WHERE lengua = ? AND clave = ? AND valido_hasta IS NULL"
                                    lengua clave))]
    (if (and actual (= (:valor actual) valor))
      :igual
      (do
        (when actual
          (bd/ejecutar! c "UPDATE regla SET valido_hasta = ? WHERE id = ?" fecha (:id actual)))
        (bd/ejecutar! c "INSERT INTO regla (lengua, clave, valor, nota, carga_id, valido_desde) VALUES (?,?,?,?,?,?)"
                      lengua clave valor nota carga-id fecha)
        (if actual :cambio :nueva)))))

(defn retirar-palabra! [c fecha lengua forma]
  (let [n (bd/consultar c "SELECT id FROM palabra WHERE lengua = ? AND forma = ? AND valido_hasta IS NULL" lengua forma)]
    (doseq [{:keys [id]} n] (bd/ejecutar! c "UPDATE palabra SET valido_hasta = ? WHERE id = ?" fecha id))
    (if (seq n) :cambio :igual)))

;;; Secciones

(defn- formas [celda]
  ;; "tú, usted" -> ["tú" "usted"]
  (remove str/blank? (map str/trim (t/dividir celda \,))))

(defn- aviso [e mensaje] (str "línea " (:linea e) ": " mensaje))

(defn- procesar-entrada
  "Procesa una línea según la sección en la que está. Devuelve [resultados avisos]."
  [c carga-id fecha fuente e]
  (let [cs (:campos e)
        lengua-ok? #(contains? lenguas %)]
    (case (:seccion e)
      "lexico"
      (if (< (count cs) 5)
        [[] [(aviso e "se esperaban: concepto | categoría | español | kethari | ozh | rasgos")]]
        (let [[concepto categoria es ke oz rasgos] cs]
          [(vec (for [[lengua celda] [["es" es] ["kethari" ke] ["ozh" oz]]
                      f (formas celda)]
                  (guardar-palabra! c carga-id fecha {:lengua lengua :forma (str/lower-case f) :concepto concepto
                                                      :categoria categoria :rasgos rasgos :fuente fuente})))
           []]))

      "numeros"
      (let [[valor ke oz] cs]
        (if-not (numero? valor)
          [[] [(aviso e "el primer campo debe ser el valor del dígito")]]
          [(vec (for [[lengua f] [["kethari" ke] ["ozh" oz]] :when (not (str/blank? f))]
                  (guardar-palabra! c carga-id fecha {:lengua lengua :forma (str/lower-case f) :concepto valor
                                                      :categoria "digito" :rasgos (str "valor=" valor)
                                                      :fuente fuente})))
           []]))

      "reglas"
      (let [[lengua clave valor nota] cs]
        (cond
          (< (count cs) 3) [[] [(aviso e "se esperaban: lengua | clave | valor")]]
          (not (lengua-ok? lengua)) [[] [(aviso e (str "lengua desconocida '" lengua "'"))]]
          :else [[(guardar-regla! c carga-id fecha {:lengua lengua :clave clave :valor valor :nota nota})] []]))

      "palabras"
      (let [[lengua forma concepto categoria rasgos nota] cs]
        (cond
          (< (count cs) 4) [[] [(aviso e "se esperaban: lengua | forma | concepto | categoría | rasgos | nota")]]
          (not (lengua-ok? lengua)) [[] [(aviso e (str "lengua desconocida '" lengua "'"))]]
          :else [[(guardar-palabra! c carga-id fecha {:lengua lengua :forma (str/lower-case forma) :concepto concepto
                                                      :categoria categoria :rasgos rasgos :fuente fuente :nota nota})]
                 []]))

      "retirar"
      (let [[lengua forma] cs]
        (if (lengua-ok? lengua)
          [[(retirar-palabra! c fecha lengua (str/lower-case forma))] []]
          [[] [(aviso e (str "lengua desconocida '" lengua "'"))]]))

      [[] [(aviso e (str "sección desconocida '" (:seccion e) "'"))]])))

(defn- voz [s]
  (let [v (bd/plegar s)]
    (cond (= v "kethari") "kethari" (= v "ozh") "ozh" :else "desconocida")))

(defn- procesar-senal [c carga-id fecha e]
  (let [[dia v transcripcion salida] (:campos e)
        trans (when-not (or (str/blank? transcripcion) (str/starts-with? transcripcion "("))
                (str/lower-case transcripcion))]
    (cond
      (encabezado? e) [[] [(aviso e "fila de títulos, se omite")]]
      (not (numero? dia)) [[] [(aviso e "el primer campo debe ser el día")]]
      :else
      (if (seq (bd/consultar c "SELECT id FROM senal WHERE dia = ? AND voz = ? AND transcripcion IS ?"
                             (parse-long dia) (voz v) trans))
        [[:igual] []]
        (let [id (bd/ejecutar! c "INSERT INTO senal (dia, voz, transcripcion, salida, carga_id, fecha_carga) VALUES (?,?,?,?,?,?)"
                               (parse-long dia) (voz v) trans salida carga-id fecha)]
          (doseq [[i f] (map-indexed vector (t/palabras trans))]
            (bd/ejecutar! c "INSERT INTO ocurrencia (senal_id, posicion, forma) VALUES (?,?,?)" id i f))
          [[:nueva] []])))))

;;; Orden cargar

(defn cargar!
  "Carga un archivo y devuelve cuántas cosas fueron nuevas, cuántas cambiaron, etc."
  [c ruta]
  (let [f (io/file ruta)]
    (when-not (.exists f)
      (throw (ex-info (str "No existe el archivo '" ruta "'.") {:tipo :archivo})))
    (let [texto (slurp f :encoding "UTF-8")
          h     (huella texto)
          doc   (leer texto)
          tipo  (tipo-de doc)]
      (when-not (#{"manual" "correccion" "senales"} tipo)
        (throw (ex-info (str "No se reconoce el tipo de '" (.getName f) "'. Agrega una línea '@tipo manual',"
                             " '@tipo correccion' o '@tipo senales'.") {:tipo :formato})))
      (if (seq (bd/consultar c "SELECT id FROM carga WHERE huella = ?" h))
        {:archivo (.getName f) :tipo tipo :repetido? true :nuevas 0 :cambios 0 :sin-cambios 0 :avisos []}
        (bd/en-transaccion
         c
         (fn []
           (let [fecha  (bd/ahora)
                 fuente (or (get-in doc [:directivas "fuente"])
                            (str tipo (when-let [v (get-in doc [:directivas "version"])] (str " v" v))))
                 id     (bd/ejecutar! c "INSERT INTO carga (archivo, tipo, fuente, version, fecha, huella) VALUES (?,?,?,?,?,?)"
                                      (.getName f) tipo fuente
                                      (get-in doc [:directivas "version"]) fecha h)
                 resultados (for [e (:entradas doc)]
                              (if (= tipo "senales")
                                (procesar-senal c id fecha e)
                                (procesar-entrada c id fecha (str fuente) e)))
                 todos   (doall (mapcat first resultados))
                 avisos  (vec (mapcat second resultados))
                 cuenta  (frequencies todos)
                 r {:archivo (.getName f) :tipo tipo :fecha fecha
                    :nuevas (get cuenta :nueva 0) :cambios (get cuenta :cambio 0)
                    :sin-cambios (get cuenta :igual 0) :avisos avisos}]
             (bd/ejecutar! c "UPDATE carga SET nuevas = ?, cambios = ?, sin_cambios = ? WHERE id = ?"
                           (:nuevas r) (:cambios r) (:sin-cambios r) id)
             r)))))))
