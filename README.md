# radiolisp

Reproductor de radio por internet, minimalista, escrito en ClojureScript.
Busca emisoras en el directorio comunitario [Radio Browser](https://www.radio-browser.info/)
y las reproduce en el navegador. Está inspirado en
[radiolise](https://gitlab.com/radiolise/radiolise.gitlab.io), y nació también
para aprender Clojure.

- Una cuadrícula de **favoritas** que se guarda en el navegador: se reordena
  arrastrando, se filtra con `/` y cada emisora puede tener su propia tecla.
- Una paleta (`a`) que busca en todo Radio Browser, con "Más escuchadas hoy" cuando
  no hay búsqueda.
- Reproductor fijo abajo: reproducir y pausar, volumen y silencio. Suena MP3, AAC,
  Ogg y HLS; las teclas multimedia del sistema también funcionan.
- Exportar las favoritas en M3U o como lista de uuids (`.list`), importar esos
  ficheros y los `.txt` que exporta Radiolise (desde el menú, el estado vacío o
  soltando el fichero sobre la página), votar emisoras y copiar su stream.
- Todo se maneja con teclado, con flechas o con atajos estilo vim (pulsa `?`).
- Interfaz en doce idiomas: español, inglés, portugués, francés, italiano, catalán, gallego, euskera, rumano, búlgaro, ruso y chino.

Por ahora es solo frontend, sin backend. El diseño está en
[`docs/specs/2026-10-20-mvp-design.md`](docs/specs/2026-10-20-mvp-design.md) y
[`docs/specs/2026-10-20-iteration-2-design.md`](docs/specs/2026-10-20-iteration-2-design.md)
(favoritas), y la referencia visual en
[`docs/design/prototype-2/`](docs/design/prototype-2/).

## Requisitos

La forma recomendada es [Nix](https://nixos.org/) con *flakes*. El `flake.nix` trae
todas las herramientas con versiones fijadas:

| Herramienta | Para qué |
|---|---|
| JDK 21 + CLI `clojure` | shadow-cljs y las dependencias (`deps.edn`) |
| [babashka](https://babashka.org/) | las tareas del proyecto (`bb.edn`) |
| Node.js 22 | el CLI de shadow-cljs y los tests |
| dart-sass | compilar el SCSS |
| clj-kondo, cljfmt, clojure-lsp | lint, formato y editor |
| Chrome o Chromium | solo para `bb e2e` |

Sin Nix, instala esas herramientas a mano y usa los mismos comandos sin el prefijo
`nix develop`.

## Puesta en marcha

```sh
nix develop      # entra en el entorno (o `direnv allow` una vez, si usas direnv)
bb setup         # instala shadow-cljs y hls.js desde npm (una sola vez)
bb dev           # compila en modo watch y sirve la app
```

Abre <http://localhost:8080>. La primera compilación descarga las dependencias y
tarda unos minutos; las siguientes, segundos.

`bb dev` vigila a la vez el ClojureScript (shadow-cljs) y el SCSS (sass). Al guardar
un `.cljs`, un `.scss` o un diccionario `.edn`, el navegador se recarga en caliente
sin perder el estado.

## Tareas

Todas se lanzan desde la raíz del repo, dentro de `nix develop`. `bb tasks` las
lista.

| Tarea | Qué hace |
|---|---|
| `bb setup` | `npm install` en `frontend/` |
| `bb dev` | watch de ClojureScript y SCSS, sirve en `:8080` |
| `bb test` | todos los tests (`common` y `frontend`) en Node |
| `bb lint` | clj-kondo |
| `bb fmt` / `bb fmt:check` | aplica o comprueba el formato con cljfmt |
| `bb css` | compila el SCSS una vez, comprimido |
| `bb build` | build de release en `frontend/resources/public/` |
| `bb e2e` | prueba de extremo a extremo en Chromium (ver [Tests](#tests)) |

Antes de dar un cambio por terminado: `bb lint && bb fmt:check && bb test`.

## Estructura

Está organizado por módulos, como [Penpot](https://github.com/penpot/penpot):

```
radiolisp/
├── flake.nix  bb.edn  deps.edn   # entorno, tareas y deps.edn raíz (solo para clojure-lsp)
├── common/                        # .cljc puro, compartido con el futuro backend
│   ├── src/radiolisp/common/      #   station (normaliza Radio Browser), favorites (lista, M3U, .list, Radiolise)
│   └── test/
├── frontend/
│   ├── deps.edn  shadow-cljs.edn  package.json
│   ├── resources/
│   │   ├── public/                # index.html, fonts/; css/ y js/ son salida de compilación
│   │   ├── styles/                # main.scss y parciales (_tokens, _grid, _player, _search…)
│   │   └── i18n/                  # un .edn por idioma (es, en, pt, fr…)
│   ├── src/radiolisp/frontend/
│   │   ├── main.cljs              # conexión: dispatch, render, teclado, teclas multimedia
│   │   ├── store.cljs             # el estado inicial y el átomo
│   │   ├── actions.cljs           # toda la lógica: estado × acción → estado + efectos (puro)
│   │   ├── keymap.cljs            # tecla + contexto → acción (puro)
│   │   ├── effects.cljs           # ejecuta efectos: fetch, audio, almacenamiento, ficheros, foco
│   │   ├── api.cljs  audio.cljs   # Radio Browser y el elemento <audio>
│   │   ├── hls.cljs               # hls.js, en su propio módulo (se carga bajo demanda)
│   │   ├── i18n.cljs              # traducciones, plurales y números
│   │   └── views/                 # hiccup para Replicant
│   ├── test/
│   └── e2e/check.mjs              # prueba en navegador (bb e2e)
└── docs/                          # spec, plan y prototipo
```

## Cómo funciona

El flujo va en un solo sentido y todo son datos:

```
clic / tecla / evento de <audio> / respuesta HTTP
        │
        ▼
   acción (vector)              [:search/typed "jazz"]
        │
        ▼
   actions/handle               estado × acción → {:state … :effects […]}   (puro)
        │                               │
        ▼                               ▼
   store (átomo) ──► render     effects/execute! ──► nuevas acciones
                   (Replicant)
```

- **Vistas** ([Replicant](https://github.com/cjohansen/replicant)): funciones puras
  de estado a hiccup. Los manejadores de eventos son datos:
  `{:on {:click [[:player/toggle]]}}`.
- **Acciones**: `actions/handle` es un `defmulti` sobre el primer elemento del
  vector. No hace I/O: devuelve el estado nuevo y una lista de efectos, como
  `[:api/fetch {...}]`, `[:storage/save favoritas]`, `[:audio/play url hls?]` o
  `[:timer/start :search 300 [:search/run]]`.
- **Efectos**: `effects/execute!` es el único sitio con efectos secundarios. Sus
  resultados vuelven como acciones, por ejemplo `[:search/loaded seq stations]`.
- **El estado guarda claves, nunca textos.** Un error es
  `[:error/network {:at ms}]`, y es la vista la que lo traduce.

### Añadir una funcionalidad

1. Escribe un test en `frontend/test/…/actions_*_test.cljs` con el estado de
   partida, la acción, y el estado y los efectos esperados.
2. Añade el `defmethod handle :tu/accion` en `actions.cljs`.
3. Si necesitas un efecto nuevo, añade su `defmethod execute!` en `effects.cljs`.
4. Si hay una tecla nueva, añádela en `keymap.cljs` y en su test, y también en la
   ayuda (`views/help.cljs`).
5. Pinta lo necesario en `views/` y despacha la acción desde `:on`.

### Textos e idiomas

Todos los textos visibles, `aria-label` incluidos, viven en
`frontend/resources/i18n/<idioma>.edn` (`es`, `en`, `pt`, `fr`, `it`, `ca`, `gl`,
`eu`, `ro`, `bg`, `ru`, `zh`), con las mismas claves y los mismos `{marcadores}` en
todos (unos tests lo comprueban). Desde las vistas: `(t :search/empty {:query q})`.
Los plurales son un mapa con las formas de `Intl.PluralRules` del idioma
(`{:one "…" :other "…"}`; el rumano añade `:few` y el ruso `:few` y `:many`), y se
eligen con `{:count n}`. `:lang/name` es el nombre del idioma en ese idioma: es lo
que lista el selector. Para añadir uno, crea su `.edn` y súmalo a
`dictionaries` y `locales` en `i18n.cljs`.

### Estilos

Son SCSS en `frontend/resources/styles/`. Los tokens de color, como la escala
*raspberry*, están en `_tokens.scss` y se exponen como custom properties. Reglas
del diseño: radio 0, una sola familia tipográfica (DM Sans, autoalojada), el color
pleno solo en marcas pequeñas, y nada de degradados ni sombras en reposo. Para
cambios visuales sigue el skill `.agents/skills/design-taste-frontend` (reglas de
*app screens*).

Modo oscuro: la paleta oscura es el mapa `$dark` de `_tokens.scss`, que redefine
los mismos custom properties bajo `:root[data-theme="dark"]`. Un script en
`index.html` pone `data-theme` antes del primer pintado (la elección guardada en
`radiolisp/theme`, si no la preferencia del navegador). El botón sol/luna de la
barra superior la cambia y la guarda; mientras no se pulse, la app sigue los
cambios de la preferencia del sistema. Un color nuevo necesita su valor en los dos
temas: nada de hex sueltos en los estilos.

## REPL

Con `bb dev` en marcha, shadow-cljs abre un nREPL y escribe su puerto en
`frontend/.shadow-cljs/nrepl.port`.

- **Editor** (Calva, CIDER, Conjure…): conéctate a ese nREPL y elige el build
  `:app`. Con la app abierta en el navegador, lo que evalúes se ejecuta allí.
- **Terminal**: `cd frontend && npx shadow-cljs cljs-repl app`.

Para curiosear el estado en vivo:

```clojure
(require '[radiolisp.frontend.store :as store])
(:player @store/state)
(:favorites @store/state)
(radiolisp.frontend.main/dispatch! [:i18n/set :es])
```

## Tests

- **`bb test`**: tests unitarios con `cljs.test`, compilados con el target
  `:node-test` de shadow-cljs. Cubren la lógica pura: normalización de emisoras,
  la lista de favoritas (orden, teclas, filtro, EDN, M3U, `.list` y Radiolise), el encaje de las
  imágenes, i18n, todas las acciones y el mapa de teclas. Cualquier namespace que
  acabe en `-test`, en `common/test` o `frontend/test`, entra solo.
- **`bb e2e`**: maneja Chromium por CDP contra la app en marcha y comprueba unas 80
  cosas: añadir desde la paleta, una sola petición `byuuid` al recargar,
  reproducción real, teclas asignadas, menús, arrastrar y soltar, filtro, quitar con
  confirmación, exportar (M3U y `.list`) e importar (M3U, `.list` y Radiolise), HLS con hls.js, ayuda, idioma, tema y el layout
  de móvil. Siembra `localStorage` y bloquea `/json/url` y `/json/vote`, así que no
  altera las estadísticas de Radio Browser. Hay que
  tener `bb dev` corriendo; para otra dirección, por ejemplo un build de release,
  usa `URL=http://127.0.0.1:8090/ bb e2e`. Usa la API y los streams reales, así que
  necesita red y depende de que las emisoras respondan. Las capturas quedan en
  `frontend/target/e2e/`.

## Build de release

```sh
bb build
```

Deja una web estática en `frontend/resources/public/`: `index.html`, `css/`, `js/`
y `fonts/`. Se sirve con cualquier servidor de ficheros estáticos, por ejemplo:

```sh
cd frontend/resources/public && python3 -m http.server 8090
```

## Limitaciones conocidas

- **Streams `http://`.** Muchas emisoras solo emiten por http. Servida por `https`,
  el navegador bloquea esos streams (contenido mixto). La app prueba la versión
  `https://` de cada URL y, si no existe, avisa de que la emisora solo emite por
  http. Lo resolverá un proxy en el futuro backend
  ([ideas de backend](docs/ideas/backend.md)).
- **Bloqueos por región o VPN.** Algunas emisoras (RTVE, por ejemplo) contestan
  `451` a ciertas IP. La app lo detecta cuando el stream permite CORS y lo dice.
- **HLS (`.m3u8`)** suena de forma nativa donde el navegador sabe (Safari, Chrome
  reciente) y con hls.js en el resto. hls.js (unos 600 KB) va en su propio módulo y
  solo se descarga la primera vez que hace falta. Sin Media Source Extensions, la
  app avisa de "formato no soportado".
- **Las favoritas viven en este navegador** (`localStorage`). Para llevarlas a otro,
  exporta e importa el M3U o el `.list`; los atajos no viajan.
- **Lo que suena** (metadatos ICY) necesita backend, y llegará con él.

## Créditos

- Inspirado en [radiolise](https://gitlab.com/radiolise/radiolise.gitlab.io).
- Datos de emisoras: [Radio Browser](https://www.radio-browser.info/), un proyecto
  comunitario.
- Tipografía: [DM Sans](https://github.com/googlefonts/dm-fonts), bajo SIL Open Font
  License 1.1 (`frontend/resources/public/fonts/OFL.txt`).
