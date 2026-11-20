# Expediente Axios — traductor español → Kethari y Ozh

Proyecto final de Procesadores de Lenguaje. El sistema traduce preguntas en español a las dos lenguas de Axios, valida su propia salida y guarda todo en una base de datos SQLite local.

Estado: **parte 1 de 5** (base de datos, carga de archivos, orden `palabra`).

## Requisitos (Windows)

1. **Java 21** (Temurin): https://adoptium.net. Marca la opción «Set JAVA_HOME» al instalar.
2. **Clojure CLI** para Windows con el instalador MSI: https://github.com/casselc/clj-msi/releases. Deja disponibles `clj` y `clojure` en cmd y PowerShell.
3. Comprueba en una consola nueva:

   ```
   java -version
   clojure -M -e "(println :ok)"
   ```

   La primera vez, Clojure descarga sus dependencias (Clojure 1.12 y el controlador de SQLite). Después ya no necesita internet.

## Uso

Desde la carpeta del proyecto:

```
axios iniciar --borrar            crea axios.db y carga todo lo que hay en datos\
axios cargar datos\senales_semana2.txt
axios palabra nave
axios palabra anul
axios palabra anul en 2026-11-18 10:00
axios palabra salok               forma con afijos: sal + ok (pasado)
pruebas                           corre las pruebas automáticas
```

`axios.bat` es un atajo de `clojure -M:axios ...`, y `pruebas.bat`, de `clojure -M:test`. En Linux o macOS se usan esos comandos directamente.

Opción global: `--bd <ruta>` para usar otra base de datos. Por defecto se usa `axios.db` o el valor de la variable `AXIOS_BD`.

## Estructura

```
datos/                 todo el conocimiento del sistema (no está en el código)
  manual_v0.7.txt        léxico, números y reglas del manual
  senales_semana1.txt    señales recibidas
  correccion_*.txt       correcciones del equipo, cada una explicada en la bitácora
src/axios/
  texto.clj              utilidades de texto sin expresiones regulares
  bd.clj                 conexión SQLite, esquema y versiones
  carga.clj              lectura de los archivos de datos (orden cargar)
  consulta.clj           consultas al léxico en una fecha (orden palabra)
  consola.clj            salida con tildes y ʔ en la consola de Windows
  cli.clj                interfaz de línea de comandos
test/axios/              pruebas automáticas (clojure.test)
docs/bitacora.md         decisiones con fecha, opciones y prueba
```

## Formato de los archivos de datos

Las líneas que empiezan con `#` son comentarios. Las que empiezan con `@` son directivas (`@tipo manual`, `@tipo correccion`, `@tipo senales`). `[seccion]` abre una sección. Los campos se separan con `|`, con un tabulador o con `;`.

Un archivo de señales puede venir sin directivas: si cada línea empieza con el número de día, se reconoce solo.

```
8 | Kethari | ... | salida del decodificador
```

Una corrección del manual nunca borra nada: crea una versión nueva y deja la anterior cerrada con su fecha. Por eso `palabra X en <fecha>` responde con lo que el sistema sabía en ese momento.

## Si las tildes o la ʔ se ven mal en Windows

El programa cambia la consola a UTF-8 (`chcp 65001`) mientras corre y la restaura al salir. Si tu consola no lo permite, la ʔ se muestra como `'`. Windows Terminal muestra todo correctamente.
