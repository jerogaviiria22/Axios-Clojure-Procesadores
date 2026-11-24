# Bitácora - Axios

Aquí anotamos cada decisión: qué problema había, qué opciones vimos, qué escogimos y cómo lo comprobamos.

Nota: para el diseño y el código usamos como apoyo un asistente de IA (Claude). En la memoria se explica en qué partes.

---

## 18 nov 2026 - Parte 1 (base de datos, cargar, palabra)

### D-01. Dónde guardar el léxico y las reglas

El enunciado no deja poner el léxico ni las reglas en el código, y además hay que poder meter señales y correcciones nuevas sin recompilar.

Pensamos en tres opciones:

- Un mapa de Clojure con las palabras. No sirve, porque eso es léxico dentro del código.
- Un JSON que se lee al arrancar. Así no toca recompilar, pero no queda historia de los cambios.
- Archivos de texto en `datos/` que se cargan a SQLite guardando versiones.

Nos quedamos con la tercera. Pasamos el manual 0.7 a `datos/manual_v0.7.txt`, con tres secciones: léxico, números y reglas. Las reglas (orden de las palabras, sufijos, partículas, etc.) quedan como filas en la tabla `regla`. El código solo las aplica: por ejemplo, sabe que `salok` es `sal` + `ok` porque en la base de datos está la regla `tiempo.pasado = sufijo ok`.

Prueba: `carga-del-manual` (el orden de cada lengua sale de la BD) y `explicar-formas-de-las-senales` (todas las palabras de las señales de la semana 1 se pueden explicar con lo que hay en la BD).

### D-02. Kethari cuenta en base 6

El manual solo trae los números del 0 al 5 en Kethari. En la señal del día 3 aparece *ra-sel kumesh vinok*, y el decodificador dice "quince naves". Si `ra-sel` (2 y 3) se lee en base 10, da 23, no 15.

Teníamos dos opciones: pensar que el decodificador se equivocó, o probar con base 6, que da 2·6 + 3 = 15.

Lo confirmamos con otra señal, la del día 7: *ti-vo lunesh* es "seis ciclos", y 1·6 + 0 = 6. Además, eso explica por qué no hay palabras para 6, 7, 8 y 9: en base 6 no se necesitan.

Quedó como regla `kethari | numero.base | 6` en el archivo de corrección, no en el código. Ozh sí va en base 10: en el día 3, *ois-ulo* = 1 y 5 = 15.

Prueba: `versiones-del-lexico` revisa que antes de la corrección no existía la regla y que después vale 6.

### D-03. anul y anir

El manual da dos palabras para "nosotros" en Kethari y no dice cuál es la diferencia. Al traducir hay que escoger una, entonces nos pusimos a mirar las señales.

Las dos empiezan por *an* (yo). *anul* termina en *ul* (tú) y *anir* en *ir* (él). Sacamos que *anul* es un "nosotros" que incluye a quien escucha y *anir* uno que lo deja por fuera. Esto existe en lenguas reales, como el quechua o el aymara.

La señal del día 4 cuadra con eso: *anir ulesh nukorok* = "nosotros no los vimos a ustedes". Ahí el que escucha no puede estar dentro del "nosotros".

Lo guardamos como un rasgo `clusividad` en el archivo de corrección. Cuando traduzcamos (parte 3), lo normal será usar *anir*, porque es la Tierra hablándole a Axios.

Prueba: `versiones-del-lexico` pregunta por *anul* antes y después de cargar la corrección.

### D-04. Versiones de las palabras

Hay que poder responder "qué creíamos que significaba esta palabra tal día".

Si sobrescribimos la fila, se pierde lo anterior. Una tabla aparte de auditoría también serviría, pero complica las consultas. Escogimos meter dos columnas en la misma tabla, `valido_desde` y `valido_hasta`; si `valido_hasta` está vacía, esa es la versión actual.

Una palabra se identifica por lengua + forma. Si llega con otro significado, se cierra la fila vieja y se crea una nueva; si llega igual, no se toca nada. Con las reglas se hace lo mismo, usando lengua + clave.

Prueba: `versiones-del-lexico` y `linea-de-comandos` (el caso `palabra anul en 2026-11-20 10:02`).

### D-05. Archivo de señales con formato flexible

El archivo de la semana 2 tiene que cargarse sin cambiar código, y todavía no sabemos cómo va a venir.

Decidimos aceptar variaciones:

- separador `|`, tab o `;`;
- `@tipo` opcional: si todas las líneas empiezan con un número, son señales;
- si viene una fila de títulos ("Día | Voz | ..."), se salta y se avisa;
- la voz se reconoce aunque venga en mayúsculas;
- si una señal no tiene transcripción, como la del día 6, se guarda igual, para no perder ninguna;
- una línea mala no tumba la carga: se avisa con su número de línea.

Cada archivo se carga en una transacción, así que entra todo o no entra nada.

Prueba: `senales-sin-directivas-y-con-otro-separador` y `carga-de-senales`.

### D-06. Buscar sin tildes ni ʔ

Escribir ʔ o ü en el teclado es incómodo. Por eso cada palabra se guarda también en una forma "plegada", que se usa solo para buscar: minúsculas, sin tildes, sin ʔ y con la ü como u. La ñ sí se deja, porque "año" y "ano" no son lo mismo. Lo que se muestra en pantalla es siempre la forma original.

Prueba: `orden-palabra` (buscar "cuando" encuentra "cuándo", y buscar "enka" encuentra "ʔenka").

### D-07. Nada de regex fuera del normalizador

El enunciado solo deja usar expresiones regulares en el normalizador. Para no arriesgarnos, partir texto, quitar tildes y leer rasgos lo hacemos carácter por carácter, en `texto.clj`.

Prueba: buscar `#"` en `src` y `test` solo encuentra `normalizador.clj` (desde la parte 2).

### D-08. Cargar dos veces el mismo archivo

Si alguien corre `cargar` dos veces con el mismo archivo, no queremos señales repetidas ni versiones falsas.

Comparar por nombre no sirve, porque el archivo se puede renombrar o editar. Por eso guardamos un hash SHA-256 del contenido en la tabla `carga`. Si ya existe, avisamos y no hacemos nada.

Prueba: `no-carga-dos-veces-lo-mismo`.

---

## 21 nov 2026 - Parte 2 (normalizador, autómata, corrector, morfología)

### D-09. El español también va en un archivo de datos

Para analizar el español hacen falta artículos, preposiciones, números en palabras, terminaciones de verbos y verbos irregulares. Si los ponemos en el código, rompemos la misma regla de D-01.

Hicimos `datos/espanol.txt` (`@tipo espanol`) con dos secciones. En `[espanol]` va cada palabra con su categoría y sus rasgos. En `[reglas]` van las terminaciones de los verbos (por ejemplo, `verbo.ar.preterito.3.pl = aron`), a qué tiempo del manual corresponde cada forma (pretérito e imperfecto son pasado), la perífrasis "ir a" y los plurales.

Las formas regulares no se escriben una por una: se generan al cargar, juntando raíz + terminación. Solo escribimos las irregulares (vendrán, quiero, fue...), y esas reemplazan a la regular de la misma persona y tiempo.

"ustedes" usa la forma de 3.ª plural (ustedes vienen), así que no listamos formas de vosotros.

Prueba: `morfologia-verbos` y `sustantivos-y-plurales`.

### D-10. Qué hace el normalizador y en qué orden

Pasos: tildes y minúsculas, acentos raros (à → á), quitar comillas y signos que no sirven, separador de miles (2.015 → 2015), números en palabras a cifras y, al final, espacios.

No le quitamos las tildes al texto. "qué" (pregunta) y "que" (conjunción) son palabras distintas, y "él" no es lo mismo que "el". Las tildes solo se ignoran al buscar, cuando no hay coincidencia exacta.

"un" y "una" solos se dejan como artículos ("una nave" no es "1 nave"). Solo cuentan como número dentro de uno más largo ("veintiún", "una mil"...).

Los números se leen con una gramática pequeña: [centena] [decena y unidad | especial | unidad], con "mil" opcional adelante. Si el número pasa de 9.999 igual se convierte, pero queda un aviso.

Prueba: `normalizador`.

### D-11. El autómata

Pensamos en dos opciones: partir por espacios y ya, o un autómata. El enunciado pide un AFD hecho a mano, así que hicimos uno con 8 estados:

| Estado | Qué significa | Acepta |
| --- | --- | --- |
| q0 | inicio | - |
| q1 | dentro de una palabra | PALABRA |
| q2 | dentro de un número | NUMERO |
| q3 | leyó ¿ o ¡ | ABRE |
| q4 | leyó ? o ! | CIERRA |
| q5 | leyó una coma | COMA |
| q6 | leyó un punto | PUNTO |
| q7 | carácter no reconocido | ERROR |

Transiciones: q0 -letra-> q1, q1 -letra-> q1, q0 -dígito-> q2, q2 -dígito-> q2, y de q0 a q3...q7 con su signo. Lo demás no tiene transición, y ahí se corta el token (regla del token más largo).

La tabla está como dato en `lexico.clj` (`transiciones`), no como ifs, para que coincida con el diagrama. Un carácter raro no detiene el análisis: sale un token ERROR y un aviso.

Las posiciones son columnas del texto ya normalizado.

Prueba: `automata`.

### D-12. Corrector con Levenshtein

La distancia de Levenshtein cuenta cuántas letras hay que insertar, borrar o cambiar para pasar de una palabra a otra. La programamos con la tabla de programación dinámica, guardando solo dos filas.

Antes de corregir, la palabra se busca tal cual y luego sin tildes. Si aparece, no es un error ("cuando" sin tilde no se corrige, se lee como cuándo).

Distancia máxima: 1 para palabras de 3 o 4 letras y 2 para las más largas. Las de 1 o 2 letras no se corrigen, porque con distancia 1 casi cualquier palabra corta se parece a otra (en una prueba, "cu" se volvía "su"). Estos números están en las reglas, no en el código.

Si hay empate, se toma la primera en orden alfabético y se anotan las demás como "también podría ser". Si nada está cerca, la palabra se deja tal cual y se marca como desconocida, para no inventar.

Prueba: `levenshtein` y `corrector`.

### D-13. Morfología y "ir a + infinitivo"

Cada forma puede tener varias lecturas. "hablamos" es presente o pasado, y "era" es 1.ª o 3.ª persona. No escogemos todavía: dejamos todas y el parser (parte 3) decide con el resto de la frase.

"van a llegar" se junta en un solo token verbo: llegar, futuro, persona y número de "van". Solo se junta si después de "a" viene un infinitivo ("van a la tierra" no es perífrasis).

"llegar" está en el manual como otra palabra del concepto venir, así que "llegaron" queda con el concepto venir.

Prueba: `perifrasis-ir-a` y `morfologia-verbos`.

### D-14. Las formas irregulares y la identidad de las palabras

En D-04 dijimos que una palabra se identifica por lengua + forma. Eso choca con formas iguales que son de dos verbos ("fue" es de ser y de ir).

Como "ir" solo lo usamos como auxiliar en presente (voy, vas, va, vamos, van), no hay choques. Cuando una forma sirve para dos personas, se escribe `persona=1/3` en una sola fila.

Pendiente: si en la parte 3 hace falta "ir" en pasado, toca revisar esto.

---

## Pendientes y cosas raras que vimos

- Ozh tiene tres partículas según de dónde sale la información: *zi* (lo vimos), *mar* (nos dijeron) y *tho* (lo deducimos). En las preguntas del día 5 sale *mar*. Creemos que en una pregunta marca de dónde se espera la respuesta, pero hay que decidirlo en la parte 3.
- *teka* se usa para tierra o lugar, y *vael* para un mundo completo.
- "ser vivo" (criatura) son dos palabras. El corrector y el autómata trabajan palabra por palabra; juntarlas le toca al parser (parte 3).
- El mismo día, las dos voces no dicen lo mismo. El día 7, Kethari dice "nosotros esperaremos" y Ozh dice "yo espero". O sea, no son traducciones la una de la otra, y no se pueden emparejar palabra por palabra.
