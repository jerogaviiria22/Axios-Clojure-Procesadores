(ns axios.consulta
  "Búsquedas en el léxico (orden palabra). Todas reciben una fecha;
  si es nil se usa lo de ahora."
  (:require [clojure.string :as str]
            [axios.bd :as bd]
            [axios.texto :as t]))

;;; Reglas y léxico en una fecha

(defn reglas
  "Las reglas de una lengua en esa fecha, como mapa clave -> valor."
  [c lengua fecha]
  (let [[condicion params] (bd/vigente-en fecha)]
    (into {} (map (juxt :clave :valor)
                  (apply bd/consultar c (str "SELECT clave, valor FROM regla WHERE lengua = ? AND " condicion)
                         lengua params)))))

(defn palabras-vigentes
  "Palabras que valían en esa fecha y cumplen la condición."
  [c fecha condicion & params]
  (let [[vig vparams] (bd/vigente-en fecha)]
    (apply bd/consultar c (str "SELECT * FROM palabra WHERE " vig " AND " condicion)
           (concat vparams params))))

;;; Explicar una forma de Kethari u Ozh con el léxico y las reglas de la BD

(defn- afijos
  "Saca los prefijos y sufijos de las reglas. Ej: 'sufijo ok' en tiempo.pasado."
  [rs]
  (for [[clave valor] rs
        :let [[tipo forma] (t/palabras valor)]
        :when (#{"sufijo" "prefijo"} tipo)]
    {:tipo (keyword tipo) :forma forma :significado clave}))

(defn- palabras-funcionales
  "Partículas sueltas (ka, zi, ʔoka, ne...) sacadas de las reglas."
  [rs]
  (merge
   (when-let [p (get rs "pregunta.particula")] {p "pregunta.particula"})
   (into {} (for [p (t/palabras (get rs "evidencial.particulas" ""))] [p "evidencial"]))
   (into {} (for [[clave valor] rs
                  :let [[tipo forma] (t/palabras valor)]
                  :when (and forma (str/starts-with? tipo "palabra"))]
              [forma clave]))))

(defn- quitar-afijos
  "Prueba quitando afijos (máximo 3) y devuelve todas las raíces posibles."
  [forma afs]
  (loop [pendientes [[forma []]], vistos #{}, acc []]
    (if-let [[f usados :as par] (first pendientes)]
      (let [nuevos (when (< (count usados) 3)
                     (for [{:keys [tipo] :as a} afs
                           :let [af (:forma a)]
                           :when (and (> (count f) (count af))
                                      (if (= tipo :sufijo) (str/ends-with? f af) (str/starts-with? f af)))]
                       [(if (= tipo :sufijo) (subs f 0 (- (count f) (count af))) (subs f (count af)))
                        (conj usados a)]))]
        (recur (into (vec (rest pendientes)) (remove #(vistos (first %)) nuevos))
               (conj vistos f)
               (conj acc par)))
      acc)))

(defn explicar
  "Trata de explicar una palabra de Kethari u Ozh: partícula, número o raíz + afijos.
  Si no se puede explicar con lo que hay en la BD devuelve nil."
  [c lengua forma fecha]
  (let [rs   (reglas c lengua fecha)
        func (palabras-funcionales rs)
        sep  (get rs "numero.separador" "-")
        buscar (fn [f & [categoria]]
                 (first (if categoria
                          (palabras-vigentes c fecha "lengua = ? AND forma = ? AND categoria = ?" lengua f categoria)
                          (palabras-vigentes c fecha "lengua = ? AND forma = ?" lengua f))))]
    (cond
      (contains? func forma)
      {:tipo :funcional :forma forma :significado (get func forma)}

      ;; Número: cifras encadenadas con el separador (ra-sel, ois-ulo)
      (and (str/includes? forma sep)
           (every? #(buscar % "digito") (t/dividir forma (first sep))))
      (let [cifras (mapv #(buscar % "digito") (t/dividir forma (first sep)))]
        {:tipo :numero :forma forma :cifras (mapv :concepto cifras)})

      :else
      (some (fn [[raiz usados]]
              (when-let [p (buscar raiz)]
                {:tipo :palabra :forma forma :raiz raiz :concepto (:concepto p)
                 :categoria (:categoria p) :afijos (mapv #(select-keys % [:tipo :forma :significado]) usados)}))
            (quitar-afijos forma (afijos rs))))))

;;; Orden palabra

(defn- evidencias
  "Señales donde sale la palabra (o la palabra con afijos)."
  [c lengua forma fecha]
  (let [filas (apply bd/consultar c
                     (str "SELECT s.id, s.dia, s.voz, s.transcripcion, s.salida, o.forma AS ocurrencia"
                          " FROM ocurrencia o JOIN senal s ON s.id = o.senal_id"
                          " WHERE s.voz = ?" (when fecha " AND s.fecha_carga <= ?")
                          " ORDER BY s.dia, s.id")
                     (cond-> [lengua] fecha (conj fecha)))
        coincide? (fn [oc]
                    (or (= oc forma)
                        (let [e (explicar c lengua oc fecha)]
                          (or (= forma (:raiz e))
                              (and (= :numero (:tipo e))
                                   (some #{forma} (t/dividir oc \-)))))))]
    (vec (distinct (map #(dissoc % :ocurrencia) (filter #(coincide? (:ocurrencia %)) filas))))))

(defn- historial-de [c lengua forma]
  (bd/consultar c (str "SELECT p.*, k.archivo FROM palabra p LEFT JOIN carga k ON k.id = p.carga_id"
                       " WHERE p.lengua = ? AND p.forma = ? ORDER BY p.valido_desde, p.id")
                lengua forma))

(defn palabra
  "Todo lo que se sabe de una palabra en esa fecha: equivalentes, historial y señales."
  [c termino fecha]
  (let [plegado (bd/plegar termino)
        directas (palabras-vigentes c fecha "forma_plegada = ?" plegado)
        ;; si no está tal cual puede tener afijos (salok, kumesh, ʔasur)
        analisis (when (empty? directas)
                   (remove nil?
                           (for [lengua ["kethari" "ozh"]]
                             (when-let [e (explicar c lengua (str/lower-case (str/trim termino)) fecha)]
                               (assoc e :lengua lengua)))))
        base (or (seq directas)
                 (mapcat (fn [{:keys [lengua raiz]}]
                           (when raiz (palabras-vigentes c fecha "lengua = ? AND forma = ?" lengua raiz)))
                         analisis))]
    {:termino termino
     :fecha fecha
     :analisis (vec analisis)
     :entradas
     (vec (for [p base]
            {:palabra p
             :equivalentes (palabras-vigentes c fecha "concepto = ? AND categoria = ? AND id <> ? ORDER BY lengua, forma"
                                              (:concepto p) (:categoria p) (:id p))
             :historial (historial-de c (:lengua p) (:forma p))
             :evidencias (if (= "es" (:lengua p))
                           []
                           (evidencias c (:lengua p) (:forma p) fecha))}))}))
