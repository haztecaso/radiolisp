# radiolisp

Reproductor de radio por internet, minimalista, escrito en ClojureScript.
Busca emisoras en el directorio comunitario [Radio Browser](https://www.radio-browser.info/)
y las reproduce en el navegador. Está inspirado en
[radiolise](https://gitlab.com/radiolise/radiolise.gitlab.io), y nació también
para aprender Clojure.

- Búsqueda incremental por nombre y "Más escuchadas hoy" cuando no hay búsqueda.
- Reproducir y pausar, volumen y silencio.
- Todo se maneja con teclado, con flechas o con atajos estilo vim (pulsa `?`).
- Interfaz en español e inglés.

Por ahora es solo frontend, sin backend. El diseño completo está en
[`docs/specs/2026-10-20-mvp-design.md`](docs/specs/2026-10-20-mvp-design.md), y la
referencia visual en [`docs/design/prototype/`](docs/design/prototype/).

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
bb setup         # instala shadow-cljs desde npm (una sola vez)
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
│   ├── src/radiolisp/common/      #   station (normaliza Radio Browser), format (reloj)
│   └── test/
├── frontend/
│   ├── deps.edn  shadow-cljs.edn  package.json
│   ├── resources/
│   │   ├── public/                # index.html, fonts/; css/ y js/ son salida de compilación
│   │   ├── styles/                # main.scss y parciales (_tokens, _player, _search…)
│   │   └── i18n/                  # es.edn, en.edn
│   ├── src/radiolisp/frontend/
│   │   ├── main.cljs              # conexión: dispatch, render, teclado, reloj
│   │   ├── store.cljs             # el estado inicial y el átomo
│   │   ├── actions.cljs           # toda la lógica: estado × acción → estado + efectos (puro)
│   │   ├── keymap.cljs            # tecla + contexto → acción (puro)
│   │   ├── effects.cljs           # ejecuta efectos: fetch, audio, temporizadores, foco
│   │   ├── api.cljs  audio.cljs   # Radio Browser y el elemento <audio>
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
  `[:api/fetch {...}]`, `[:audio/play url]` o
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
`frontend/resources/i18n/es.edn` y `en.edn`, con las mismas claves en los dos (un
test lo comprueba). Desde las vistas: `(t :search/empty {:query q})`. Los plurales
son un mapa `{:one "…" :other "…"}`, y se eligen con `{:count n}`.

### Estilos

Son SCSS en `frontend/resources/styles/`. Los tokens de color, como la escala
*raspberry*, están en `_tokens.scss` y se exponen como custom properties. Reglas
del diseño: radio 0, una sola familia tipográfica (DM Sans, autoalojada), el color
pleno solo en marcas pequeñas, y nada de degradados ni sombras en reposo. Para
cambios visuales sigue el skill `.agents/skills/design-taste-frontend` (reglas de
*app screens*).

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
(radiolisp.frontend.main/dispatch! [:i18n/set :es])
```

## Tests

- **`bb test`**: tests unitarios con `cljs.test`, compilados con el target
  `:node-test` de shadow-cljs. Cubren la lógica pura: normalización de emisoras,
  formato, i18n, todas las acciones y el mapa de teclas. Cualquier namespace que
  acabe en `-test`, en `common/test` o `frontend/test`, entra solo.
- **`bb e2e`**: maneja Chromium por CDP contra la app en marcha y comprueba unas 47
  cosas: búsqueda con una sola petición por pausa, teclado, reproducción real,
  ayuda, cambio de idioma, error sin red y reintento, y el layout de móvil. Hay que
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
  el navegador bloquea esos streams (contenido mixto). La app prueba primero la
  versión `https://` de cada URL, pero no todas existen. Lo resolverá un proxy en
  el futuro backend.
- **HLS (`.m3u8`)** todavía no se reproduce: la app avisa de "formato no
  soportado".
- **Lo que suena** (metadatos ICY) necesita backend, y llegará con él.
- Sin favoritos ni persistencia, sin modo oscuro, y con dos idiomas.

## Créditos

- Inspirado en [radiolise](https://gitlab.com/radiolise/radiolise.gitlab.io).
- Datos de emisoras: [Radio Browser](https://www.radio-browser.info/), un proyecto
  comunitario.
- Tipografía: [DM Sans](https://github.com/googlefonts/dm-fonts), bajo SIL Open Font
  License 1.1 (`frontend/resources/public/fonts/OFL.txt`).
