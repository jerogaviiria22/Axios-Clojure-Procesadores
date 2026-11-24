# Axios - traductor español a Kethari y Ozh

Proyecto final de Procesadores de Lenguaje (Prof. John Harold Morales).

El programa recibe preguntas en español y las traduce a las dos lenguas de Axios. Todo lo que sabe (palabras, reglas, señales) lo guarda en una base de datos SQLite local.

Van hechas la parte 1 (base de datos, `cargar`, `palabra`) y la parte 2 (normalizador, autómata, corrector y morfología, que se ven con `analizar`).

## Qué hay que instalar

- Java 21. Nosotros usamos Temurin: https://adoptium.net (al instalar, marcar "Set JAVA_HOME").
- Clojure para Windows, con el instalador .msi de https://github.com/casselc/clj-msi/releases

Para ver si quedó bien, abrir una consola nueva y correr:

```
java -version
clojure -M -e "(println :ok)"
```

La primera vez Clojure se demora un poco porque descarga las librerías (Clojure 1.12 y el driver de SQLite).

## Cómo se usa

Desde la carpeta del proyecto:

```
axios iniciar --borrar
axios cargar datos\senales_semana2.txt
axios palabra nave
axios palabra salok
axios palabra anul
axios palabra anul en 2026-11-18 10:00
axios analizar "¿Cuántas nabes van a benir en dos mil quince?"
pruebas
```

- `iniciar --borrar` crea `axios.db` desde cero y carga lo que hay en `datos\`. La base de datos no se sube al repo, porque se arma con esta orden.
- `cargar` mete un archivo nuevo de señales o de correcciones, sin tocar el código.
- `palabra` muestra lo que el sistema sabe de una palabra. Si le pones `en <fecha>`, muestra lo que sabía ese día.
- `analizar` muestra paso por paso cómo se procesa una pregunta: normalizador, tokens, correcciones y morfología.
- `pruebas` corre las pruebas automáticas.

`axios.bat` y `pruebas.bat` son atajos de `clojure -M:axios` y `clojure -M:test`.

Con `--bd otra.db` se puede usar otra base de datos.

## Carpetas

```
datos/    manual, español, señales y correcciones (aquí está todo el léxico y las reglas)
src/      código
test/     pruebas
docs/     bitácora de decisiones
```

Dentro de `src/axios`:

- `texto.clj`: funciones para partir y limpiar texto (sin regex).
- `bd.clj`: conexión a SQLite y creación de las tablas.
- `carga.clj`: lee los archivos de `datos/`.
- `consulta.clj`: búsquedas en el léxico.
- `consola.clj`: arregla las tildes y la ʔ en la consola de Windows.
- `espanol.clj`: arma el vocabulario del español (conjugaciones y plurales) desde la BD.
- `normalizador.clj`: fase 1. El único archivo con regex.
- `lexico.clj`: fase 2, el autómata.
- `corrector.clj`: fase 3, Levenshtein.
- `morfologia.clj`: fase 4, verbos y "ir a".
- `analisis.clj`: une las cuatro fases.
- `cli.clj`: las órdenes.

## Formato de los archivos de datos

- `#` es un comentario.
- `@tipo`, `@version` y `@fuente` dicen qué es el archivo.
- `[seccion]` abre una sección.
- Los campos van separados con `|`. También sirven el tabulador o el `;`.

Si un archivo de señales no trae `@tipo`, igual se reconoce cuando cada línea empieza con el número del día:

```
8 | Kethari | ... | lo que dio el decodificador
```

Cuando se corrige una palabra no se borra la versión vieja. Queda guardada con su fecha, y por eso `palabra X en <fecha>` funciona.

## Tildes en Windows

Mientras corre, el programa pone la consola en UTF-8 (`chcp 65001`) y al salir la deja como estaba. Si aun así la ʔ no sale, se muestra como `'`.
