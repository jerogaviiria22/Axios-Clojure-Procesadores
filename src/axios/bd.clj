(ns axios.bd
  "Base de datos SQLite (con java.sql y el driver de sqlite).
  En palabra y regla no se borra nada: cuando algo cambia se le pone
  valido_hasta a la fila vieja y se inserta una nueva (ver D-04)."
  (:require [clojure.string :as str]
            [axios.texto :as t])
  (:import [java.sql DriverManager Connection PreparedStatement ResultSet Statement]
           [java.time LocalDateTime]
           [java.time.temporal ChronoUnit]))

(def ruta-por-defecto
  (or (System/getenv "AXIOS_BD") "axios.db"))

(def ^:dynamic *ahora*
  "Hora actual. En las pruebas se cambia para controlar las fechas."
  (fn [] (str (.truncatedTo (LocalDateTime/now) ChronoUnit/SECONDS))))

(defn ahora [] (*ahora*))

;;; Conexión y consultas

(defn conectar
  "Abre la base de datos (si no existe la crea)."
  ^Connection [ruta]
  (let [c (DriverManager/getConnection (str "jdbc:sqlite:" ruta))]
    (with-open [s (.createStatement c)]
      (.execute s "PRAGMA foreign_keys = ON"))
    c))

(defn- preparar ^PreparedStatement [^Connection c sql params]
  (let [ps (.prepareStatement c sql Statement/RETURN_GENERATED_KEYS)]
    (doseq [[i p] (map-indexed vector params)]
      (.setObject ps (int (inc i)) p))
    ps))

(defn- filas [^ResultSet rs]
  (let [md (.getMetaData rs)
        n  (.getColumnCount md)
        cols (mapv #(keyword (.replace (.getColumnLabel md (int %)) \_ \-)) (range 1 (inc n)))]
    (loop [acc []]
      (if (.next rs)
        (recur (conj acc (zipmap cols (map #(.getObject rs (int %)) (range 1 (inc n))))))
        acc))))

(defn consultar
  "Para SELECT. Devuelve una lista de mapas, ej. {:forma \"kum\" :valido-desde ...}"
  [c sql & params]
  (with-open [ps (preparar c sql params)
              rs (.executeQuery ps)]
    (filas rs)))

(defn ejecutar!
  "Para INSERT, UPDATE o DELETE. Devuelve el id nuevo si hay."
  [c sql & params]
  (with-open [ps (preparar c sql params)]
    (.executeUpdate ps)
    (with-open [rs (.getGeneratedKeys ps)]
      (when (.next rs) (.getLong rs 1)))))

(defn en-transaccion
  "Corre f en una transacción (si falla algo no se guarda nada)."
  [^Connection c f]
  (let [auto (.getAutoCommit c)]
    (.setAutoCommit c false)
    (try
      (let [r (f)] (.commit c) r)
      (catch Throwable e (.rollback c) (throw e))
      (finally (.setAutoCommit c auto)))))

;;; Esquema

(def esquema
  ["CREATE TABLE IF NOT EXISTS carga (
      id          INTEGER PRIMARY KEY,
      archivo     TEXT NOT NULL,
      tipo        TEXT NOT NULL,          -- manual | correccion | senales
      fuente      TEXT,
      version     TEXT,
      fecha       TEXT NOT NULL,
      huella      TEXT,                   -- SHA-256 del contenido: evita cargar dos veces lo mismo
      nuevas      INTEGER DEFAULT 0,
      cambios     INTEGER DEFAULT 0,
      sin_cambios INTEGER DEFAULT 0)"

   "CREATE TABLE IF NOT EXISTS palabra (
      id            INTEGER PRIMARY KEY,
      lengua        TEXT NOT NULL,        -- es | kethari | ozh
      forma         TEXT NOT NULL,        -- como se escribe: ʔenka, drüm, cuándo
      forma_plegada TEXT NOT NULL,        -- sin tildes, sin ʔ, ü -> u (para buscar)
      concepto      TEXT NOT NULL,        -- une las tres lenguas: 'nave', '15'
      categoria     TEXT NOT NULL,        -- pronombre | sustantivo | verbo | interrogativo | digito ...
      rasgos        TEXT,                 -- 'persona=1 numero=pl'
      fuente        TEXT,
      nota          TEXT,
      carga_id      INTEGER REFERENCES carga(id),
      valido_desde  TEXT NOT NULL,
      valido_hasta  TEXT)"                 ; NULL = vigente

   "CREATE INDEX IF NOT EXISTS idx_palabra_forma ON palabra(lengua, forma_plegada)"
   "CREATE INDEX IF NOT EXISTS idx_palabra_concepto ON palabra(concepto)"

   "CREATE TABLE IF NOT EXISTS regla (
      id           INTEGER PRIMARY KEY,
      lengua       TEXT NOT NULL,
      clave        TEXT NOT NULL,         -- 'orden', 'tiempo.pasado', 'numero.base' ...
      valor        TEXT NOT NULL,
      nota         TEXT,
      carga_id     INTEGER REFERENCES carga(id),
      valido_desde TEXT NOT NULL,
      valido_hasta TEXT)"

   "CREATE TABLE IF NOT EXISTS senal (
      id            INTEGER PRIMARY KEY,
      dia           INTEGER NOT NULL,
      voz           TEXT NOT NULL,        -- kethari | ozh | desconocida
      transcripcion TEXT,
      salida        TEXT,
      carga_id      INTEGER REFERENCES carga(id),
      fecha_carga   TEXT NOT NULL,
      UNIQUE (dia, voz, transcripcion))"

   "CREATE TABLE IF NOT EXISTS ocurrencia (
      senal_id INTEGER NOT NULL REFERENCES senal(id),
      posicion INTEGER NOT NULL,
      forma    TEXT NOT NULL,
      PRIMARY KEY (senal_id, posicion))"

   "CREATE TABLE IF NOT EXISTS consulta (
      id             INTEGER PRIMARY KEY,
      fecha          TEXT NOT NULL,
      original       TEXT NOT NULL,
      normalizada    TEXT,
      correcciones   TEXT,
      arbol          TEXT,
      kethari        TEXT,
      ozh            TEXT,
      valida_kethari INTEGER,
      valida_ozh     INTEGER,
      estado         TEXT NOT NULL,       -- traducida | rechazada | error
      motivo         TEXT,
      traza          TEXT)"])

(defn crear-esquema! [c]
  (doseq [sql esquema]
    (with-open [s (.createStatement ^Connection c)]
      (.execute s sql))))

;;; Utilidades del léxico

(def plegar t/plegar)

(defn rasgos->mapa
  "'persona=1 numero=pl' -> {\"persona\" \"1\", \"numero\" \"pl\"}"
  [s]
  (into (sorted-map)
        (for [par (t/palabras s)
              :when (str/includes? par "=")
              :let [i (str/index-of par "=")]]
          [(subs par 0 i) (subs par (inc i))])))

(defn vigente-en
  "Condición para quedarse con las filas que valían en esa fecha. Sin fecha = las actuales."
  [fecha]
  (if fecha
    ["valido_desde <= ? AND (valido_hasta IS NULL OR valido_hasta > ?)" [fecha fecha]]
    ["valido_hasta IS NULL" []]))
