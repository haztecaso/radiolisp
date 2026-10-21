# Idea: radios agrupadas (variantes de una misma emisora)

Estado: idea en análisis, sin spec. Retomar **cuando el backend
(`docs/specs/2026-10-20-backend-design.md`) esté implementado y fusionado**: el
failover entre variantes toca las mismas acciones del reproductor que el proxy y
`:now-playing`.

## Motivación

Al buscar, la misma emisora sale varias veces (bitrates, http/https, HLS,
nombres con sufijos). Queremos agrupar los resultados en *radios* únicas, cada
una con su lista de variantes (streams alternativos).

## Propuesta inicial

1. **`group-key`**, función pura y determinista: nombre en minúsculas, sin
   acentos ni puntuación, sin sufijos de calidad o códec (`128k`, `(mp3)`,
   `aac`…), más `countrycode`. Refuerzo opcional con el host de `homepage`. Hash
   determinista.
2. **Modelo propio de radio**: grupo de estaciones de Radio Browser con la misma
   `group-key`.
3. **Representante** de cada radio, por este orden: `lastcheckok=1`, compatible
   con el reproductor (códec y `hls`), `https` antes que `http`, calidad del
   stream, más `votes`/`clickcount`.
4. **Reproducción**: si el stream falla, probar la siguiente variante antes de
   mostrar el error. Resolver siempre por `stationuuid` al reproducir.
5. **Listas**: guardar el `stationuuid` de la representante y la `group-key`. El
   uuid puede cambiar si se elige otra representante; la `group-key` no.
6. **Paginación**: agrupar reduce cada página, así que pedir más resultados de
   los que se muestran o seguir con `offset` hasta llenar la lista.
   `hidebroken=true`.

Requisitos: agrupación y selección en funciones puras con tests sobre casos
reales (misma radio con varios bitrates, http/https, nombre con sufijos), y un
esquema del modelo de radio agrupada.

## Datos reales (2026-10-20)

Simulación con una clave sencilla (minúsculas, sin acentos, puntuación → espacio,
lista corta de sufijos, más país) sobre `stations/search` con
`hidebroken=true&order=clickcount&reverse=true&limit=50`:

| Búsqueda | Estaciones | Grupos | Ejemplos agrupados |
|---|---|---|---|
| `fip` | 50 | 20 | `FIP` ×10: `FIP`, `Fip`, `FIP (hifi + metadata)`, `FIP (hifi.aac)`, `FIP (metadata)`, `FIP (no pub)` |
| `radio 3` | 50 | 40 | `Rai Radio 3` ×6 (misma URL); `radio 3 \| rbb \| HQ` y `… \| LQ` |
| `cadena ser` | 50 | 41 | **falso positivo**: `Cadena Ser +` con `cadena ser`; `Cadena Ser + Sevilla` con `Cadena SER Sevilla` |
| `bbc radio 1` | 17 | 12 | `BBC Radio 1`, `(128k)`, `(320kbps)` |
| `los 40` | 27 | 23 | `LOS 40 URBAN` y `Los 40 Urban` |

Buenos candidatos para los fixtures de los tests.

## Huecos encontrados

1. **Puntuación.** Quitarla a ciegas une emisoras distintas: SER+ es otro
   programa que la SER. `+` debe quedarse como token. La puntuación se cambia por
   espacio, no se borra (`Radio 3:16` → `radio 3 16`, no `radio 316`).
2. **Descripciones en el nombre.** `BBC RADIO 1 - The biggest new pop…` tienta a
   cortar tras ` - `, pero `Cadena SER - Radio Vigo` quedaría en `cadena ser`.
   Preferir falsos negativos a falsos positivos: no se corta.
3. **Sufijos.** Más que códec y bitrate: `(metadata)`, `(no pub)`, `HQ`/`LQ`,
   `Hi-Fi`, `320kbps`, `hifi.aac`. Lista cerrada y versionada; no un regex que
   borre cualquier paréntesis.
4. **`countrycode` erróneo o vacío.** `FIP` MP3 sin país; `BBC RADIO 1 UK` con
   `MX`. No se agrupan. Aceptable, pero conocido.
5. **`homepage` en la clave fragmenta.** `Rai Radio 3` tiene `radio3.rai.it`,
   `rai.it` o nada: tres grupos. Hay homepages basura
   (`playerservices.streamtheworld.com`) y `bbc.co.uk` exige la lista de sufijos
   públicos para sacar el dominio registrable. Mejor como señal para *separar*
   que como parte de la clave.
6. **Misma URL = misma radio**, la señal más fuerte, y falta en la propuesta.
   Pero agrupar por URL hace que el grupo dependa del conjunto de resultados
   (union-find) y la clave deja de ser estable, que es lo que pide el punto 5.
   Propuesta: la clave sigue siendo nombre + país; la URL solo une grupos al
   mostrarlos.
7. **Hash.** `hash` de Clojure y de ClojureScript no garantiza el mismo valor en
   las dos plataformas y es de 32 bits. Una clave legible y versionada
   (`v1|es|cadena ser`) se depura mejor y no colisiona. Si se quiere hash, uno
   explícito en cljc (FNV-1a de 64 bits).
8. **Versión de la clave.** Cambiar la lista de sufijos cambia las claves
   guardadas. Al cargar, si la versión no coincide, se recalcula a partir de la
   representante.
9. **Unicode JVM/JS.** Acentos con NFD y quitando `\p{M}` (en JS, `RegExp` con
   flag `u`). Conservar letras no latinas (`Старое радио`) con `\p{L}\p{N}`. Los
   mismos casos de test en Node y en la JVM.
10. **"Compatible con el reproductor" depende del entorno**: `canPlayType`, MSE
    para HLS, página https, si hay proxy. Para que la elección sea pura recibe un
    mapa de entorno, p. ej. `{:https-page? :proxy? :codecs :hls?}`.
11. **`hidebroken=true` ya está en la búsqueda.** `lastcheckok` solo importa en
    favoritas (`byuuid` no filtra rotas), y ahí una variante rota sigue valiendo
    como último recurso.
12. **Desempates.** HLS tiene bitrate 0 (nil): ¿dónde va? `clickcount` (tendencia
    diaria, el orden de la API) frente a `votes` (histórico). Desempate final por
    `stationuuid` para que sea determinista.
13. **Paginación.** Hoy no hay "cargar más" (`limit 50`). Lo más simple: pedir
    ~150 y agrupar. `offset` incremental es UI nueva, y una página nueva debe
    añadir variantes a grupos ya mostrados sin moverlos.
14. **Variantes de una favorita.** Al cargar solo hay uuids; buscar las variantes
    de 30 favoritas son 30 peticiones. Opciones: guardar `:variants [uuid …]` en
    la favorita y pedirlas en el mismo `byuuid` que ya se hace, o buscarlas
    (`search?name=…&countrycode=…`, filtrando por clave) solo cuando la
    reproducción falla.

## Conflictos con el frontend actual

- **La identidad es el `:id` (uuid)** en todas partes: `favorites` (`member?`,
  `add`, `move-before`, `key-owner`), arrastre, `:stations`, `:art`, `:missing`,
  el reproductor y las exportaciones. Si cambia el uuid de la representante hay
  que reescribir a la vez la favorita, `:art`, `:missing` y el reproductor: una
  función pura `favorites/replace-id`.
- **Almacenamiento.** `favorites/deserialize` devuelve nil si `version ≠ 1`:
  subir a v2 sin migración borra las favoritas. Hace falta migrar v1 → v2.
- **Duplicados en favoritas.** Hoy se miran por `:id`; pasarían a mirarse por
  grupo (la estrella marcada si cualquier variante es favorita).
- **Búsqueda.** `:results` es un vector de estaciones y el teclado navega por
  índice. Pasaría a vector de radios; la vista pinta la representante y el
  número de variantes.
- **Top** (`topclick`): también agrupar.
- **Importar/exportar.** `.list` y M3U llevan uuids; la clave no se exporta (se
  calcula al importar).

## Conflictos con el backend

- El failover entre variantes es una capa **encima** de `audio/candidates`, que
  el backend cambia (https → proxy por uuid). Va en `actions`: ante
  `:audio/failed`, `:audio/http-only`, `:audio/unsupported` o `:audio/timeout`,
  si queda una variante sin probar, reproducirla en vez de mostrar el error.
- El backend cambia el mapa de errores del proxy y suscribe `:now-playing` por
  id; cambiar de variante cambia el id, así que hay que volver a suscribirse. Por
  eso, implementar **después** de fusionar el backend. La lógica pura (un ns
  nuevo en `common/`) no choca con nada.
- "Resolver siempre por uuid" ya encaja: el proxy es `/api/stream/<uuid>`.
- **Tiempo.** Cada variante puede costar `https-attempt-ms` (8 s) más el connect.
  Limitar los intentos (p. ej. 3 variantes) y mostrar "probando alternativa".
- **Clicks.** Hoy se cuenta un click al empezar; con failover serían varios.
  Contarlo al llegar a `:audio/playing`.

## Dónde vive el código

- `common/src/radiolisp/common/radio.cljc`, puro: `normalize-name`, `group-key`,
  `rank`/`choose` con el entorno, `group` (estaciones → radios, en orden de
  primera aparición), `merge-page`. Tests cljc con fixtures reales, en Node y en
  la JVM.
- Compartido con sentido: el backend podría servir `/api/search` ya agrupado,
  cachear las variantes por clave o mantener un espejo de Radio Browser
  (`docs/ideas/backend.md` §4). Hoy solo lo usaría el frontend.

## Preguntas abiertas

1. **¿Cuándo cambia el uuid guardado de una favorita?**
   - A (recomendada): fijo hasta que falle; solo cambia cuando la representante
     falla y otra variante suena.
   - B: siempre la mejor según el ranking al cargar las variantes (cambia por
     votos ajenos).
   - C: A más elección manual de variante desde el menú, que la fija.
2. **Clave**: ¿legible y versionada, o hash (FNV-1a 64)?
3. **`homepage`**: ¿fuera de la clave y solo como señal para separar, o nada?
4. **Unir por URL idéntica** al mostrar: ¿sí o no?
5. **Variantes de favoritas**: ¿guardadas en la favorita o buscadas al fallar?
6. **Paginación**: ¿pedir ~150 de una vez o "cargar más" con `offset`?
7. **Esquema**: ¿predicado a mano (como `common.protocol`) o introducir malli
   ahora?
8. **UI de variantes**: ¿solo un contador, o un menú para elegir variante?
9. **Desempates**: ¿`clickcount` antes que `votes`? ¿Dónde va HLS sin bitrate?
10. **Límite del failover**: ¿cuántas variantes y cuánto tiempo en total?
