# radiolisp — MVP design

- Fecha: 2026-10-20
- Estado: borrador para revisión
- Referencia visual: `docs/design/prototype/index.html` (prototipo HTML/JS; abrir con un servidor local)

## 1. Qué es

radiolisp es una web app para escuchar emisoras de radio online, inspirada en
[radiolise](https://gitlab.com/radiolise/radiolise.gitlab.io). Usa el directorio
comunitario [Radio Browser](https://www.radio-browser.info/) para buscar emisoras.

Dos objetivos, con el mismo peso:

1. Una interfaz más minimalista y agradable que la de radiolise.
2. Aprender Clojure y ClojureScript, organizando el repositorio a la manera de Penpot.

## 2. Alcance

### Dentro del MVP

- Búsqueda **incremental** de emisoras por nombre en Radio Browser.
- "Más escuchadas hoy" cuando no hay búsqueda.
- Reproducir y pausar, volumen y silencio.
- Navegación completa con teclado: flechas y atajos estilo vim (§7).
- Internacionalización de **todos** los textos, errores incluidos: español e inglés (§8).
- Layout adaptado a escritorio, tablet y móvil (§6).
- Estados honestos: cargando, sin resultados, error de red y stream caído.

### Fuera del MVP (más adelante)

- Backend (§11).
- Metadatos de lo que suena (requieren backend).
- Favoritos y cualquier persistencia: no se usa localStorage.
- Filtros por país, idioma o tag.
- Streams HLS (`.m3u8`).
- Modo oscuro.
- Visualizador.
- Más idiomas.

## 3. Repositorio y herramientas

```
radiolisp/
├── AGENTS.md                  # instrucciones canónicas para agentes de IA
├── CLAUDE.md                  # "Lee AGENTS.md"
├── .agents/skills/            # skills del proyecto (design-taste-frontend)
├── .claude/skills -> ../.agents/skills
├── flake.nix  flake.lock      # devShell de Nix
├── bb.edn                     # tareas de babashka
├── deps.edn                   # agrupa los módulos solo para clojure-lsp
├── .clj-kondo/config.edn  .cljfmt.edn  .gitignore
├── docs/
│   ├── specs/                 # este documento
│   └── design/prototype/      # prototipo de referencia
├── common/
│   ├── deps.edn
│   ├── src/radiolisp/common/
│   │   ├── station.cljc       # normaliza el JSON de Radio Browser a mapas de emisora
│   │   └── format.cljc        # formato de bitrate, reloj hh:mm:ss
│   └── test/radiolisp/common/
└── frontend/
    ├── deps.edn  shadow-cljs.edn  package.json
    ├── resources/
    │   ├── public/index.html  # css/ y js/ son salida de compilación (ignorados en git)
    │   ├── styles/            # main.scss + parciales
    │   └── i18n/              # es.edn  en.edn
    ├── src/radiolisp/frontend/
    │   ├── main.cljs          # init: store, dispatch, render, teclado, audio
    │   ├── store.cljs         # el atom y el estado inicial
    │   ├── actions.cljs       # handle: estado × acción → {:state :effects}  (puro)
    │   ├── effects.cljs       # ejecuta efectos: fetch, audio, temporizadores, foco
    │   ├── api.cljs           # Radio Browser: servidores, búsqueda, más escuchadas
    │   ├── audio.cljs         # el <audio> y sus eventos → acciones
    │   ├── keymap.cljs        # tecla + contexto → acción (puro)
    │   ├── i18n.cljs          # t, plurales, números
    │   └── views/             # shell  player  search  help  station  icons
    └── test/radiolisp/frontend/
```

- **Namespaces** `radiolisp.*`.
- **Dependencias**: `deps.edn` y la CLI `clojure`. El `deps.edn` de la raíz solo agrupa `common` y `frontend` con `:local/root`, como en Penpot.
- **ClojureScript**: shadow-cljs (`:deps true`, con lo que lee `frontend/deps.edn`) y un build `:app` con target `:browser`.
- **UI**: [Replicant](https://github.com/cjohansen/replicant), hiccup puro sin React.
- **Estilos**: SCSS compilado con **dart-sass** de nixpkgs. `frontend/resources/styles/main.scss` incluye los parciales `_tokens`, `_base`, `_shell`, `_player`, `_search` y `_help`, y la salida va a `resources/public/css/main.css`.
- **Tareas** (`bb.edn`):

  | Tarea | Qué hace |
  |---|---|
  | `bb dev` | `shadow-cljs watch app` y `sass --watch` en paralelo |
  | `bb build` | build de release y CSS comprimido |
  | `bb test` | tests de `common` y `frontend` (shadow `:node-test`) |
  | `bb lint` | `clj-kondo --lint common/src frontend/src` |
  | `bb fmt` / `bb fmt:check` | `cljfmt fix` / `cljfmt check` |

- **Entorno** (`flake.nix`, `devShell`): JDK 21, `clojure`, `babashka`, `nodejs`, `dart-sass`, `clj-kondo`, `cljfmt` y `clojure-lsp`.
- **IA**: `AGENTS.md` describe los módulos, las tareas `bb` y las reglas del repositorio: lint y formato antes de dar algo por terminado, y no hacer push. `CLAUDE.md` solo remite a él. Serena y las memorias por módulo se dejan para cuando el repositorio crezca.

## 4. Arquitectura del frontend

Es un flujo en un solo sentido con datos puros:

```
evento DOM / tecla / <audio> / timer / fetch
        │
        ▼
   acción (vector)            [:search/typed "jaz"]
        │
        ▼
   actions/handle             estado × acción → {:state s' :effects [...]}     PURA
        │                             │
        ▼                             ▼
   store (atom) ──watch──► render     effects/run!  → pueden emitir nuevas acciones
                          (Replicant)
```

- **Store**: un único `atom`, creado en `store.cljs`.
- **Vistas**: funciones puras de estado a hiccup. Los manejadores son datos (`{:on {:click [[:player/toggle]]}}`) que recibe un único `dispatch`, registrado con `replicant.dom/set-dispatch!`.
- **`actions/handle`**: un `defmulti` sobre el primer elemento de la acción. Devuelve `{:state … :effects […]}` y es la única lógica de negocio. No hace I/O.
- **`effects/run!`**: el único sitio con efectos secundarios. Cada efecto es un vector y los resultados vuelven como acciones.
- **`audio.cljs`**: crea un `<audio>` fuera del árbol de Replicant y traduce sus eventos (`playing`, `pause`, `waiting`, `error`) en acciones.
- **`keymap.cljs`**: una función pura que recibe el contexto (modo, foco, panel abierto, ayuda abierta) y la tecla, y devuelve la acción o `nil`. El listener global de `keydown` solo llama a esa función y despacha el resultado.

### 4.1 Forma del estado

```clojure
{:locale  :es                          ; :es | :en
 :help?   false
 :player  {:station    nil             ; mapa de emisora (radiolisp.common.station) o nil
           :status     :stopped        ; :stopped | :connecting | :playing | :paused | :error
           :error      nil             ; [:error/stream {}] | [:error/unsupported {}]
           :started-at nil             ; ms, para el reloj
           :volume     70              ; 0–100
           :muted?     false}
 :search  {:open?      true            ; panel abierto
           :query      ""
           :mode       :insert         ; :insert (input) | :normal (lista)
           :status     :loading        ; :loading | :done | :error
           :seq        0               ; número de la última petición lanzada
           :retried?   false           ; ya se reintentó con otro servidor
           :results    []              ; la lista visible: búsqueda o "más escuchadas hoy"
           :top        []              ; caché de "más escuchadas hoy"
           :highlight  0               ; índice marcado en :results
           :error      nil}            ; [:error/network {:at <ms>}]
 :api     {:servers [] :current 0}     ; nombres de host y el índice en uso
 :keys    {:last-g 0}                  ; para detectar `gg`
 :now     0}                           ; ms; lo actualiza [:clock/tick] cada segundo mientras suena
```

El estado **nunca contiene textos traducidos**: guarda claves y datos (§8).

### 4.2 Acciones principales

| Acción | Efecto en el estado | Efectos emitidos |
|---|---|---|
| `[:app/init]` | — | `[:api/servers]` `[:api/top]` `[:dom/focus :search]` |
| `[:search/open]` / `[:search/close]` | `:open?`, `:mode :insert` | `[:dom/focus :search]` / `[:dom/focus :opener]` |
| `[:search/typed q]` | `:query q`, `:highlight 0`; con menos de 2 caracteres `:status :idle` | con 2 o más: `[:timer/debounce :search 300 [:search/run]]` |
| `[:search/run]` | `:status :loading`, `:seq` + 1 | `[:api/fetch {:server s :query q-o-nil :seq n}]` (sin query, "más escuchadas") |
| `[:search/loaded seq stations]` | si `seq` coincide con el actual: `:results`, `:status :done`; si no, se ignora | — |
| `[:search/failed seq err]` | si `seq` coincide: `:status :error`, `:error` | — |
| `[:search/move n]` / `[:search/edge :first\|:last]` | `:highlight` acotado a la lista visible | `[:dom/scroll-into-view :highlight]` |
| `[:search/mode :insert\|:normal]` | `:mode` | `[:dom/focus …]` |
| `[:search/choose i]` | `:player/station`, `:status :connecting`, `:open? false` | `[:audio/play url]` `[:dom/focus :toggle]` |
| `[:player/toggle]` | `:playing` ↔ `:paused` | `[:audio/pause]` / `[:audio/play url]` |
| `[:player/volume v]` / `[:player/volume-step ±5]` / `[:player/mute]` | `:volume`, `:muted?` | `[:audio/volume 0.7]` |
| `[:audio/playing]` `[:audio/paused]` `[:audio/waiting]` `[:audio/failed kind]` | `:status`, `:started-at`, `:error` | `[:timer/start :connect-timeout 10000 …]` mientras está en `:connecting` |
| `[:i18n/set :en]` | `:locale` | `[:dom/lang :en]` |
| `[:help/open]` / `[:help/close]` | `:help?` | — |

### 4.3 Búsqueda incremental

- Debounce de **300 ms** desde la última tecla. **Enter** lanza la búsqueda de inmediato.
- Con menos de **2 caracteres** no se busca y se muestra "Más escuchadas hoy".
- **Las respuestas antiguas se descartan**: cada búsqueda incrementa `:seq`, y una respuesta con un `seq` anterior se ignora. Además, `[:api/fetch …]` aborta el fetch anterior con un `AbortController`.
- Endpoint: `GET /json/stations/search?name=<q>&hidebroken=true&order=clickcount&reverse=true&limit=50`.
- "Más escuchadas hoy": `GET /json/stations/topclick/20?hidebroken=true`.
- **Servidor**: al iniciar se pide `https://all.api.radio-browser.info/json/servers` y se elige uno al azar. Si falla, se usa una lista fija de respaldo (`de1`, `de2`). Ante un error de red o un 5xx se reintenta una vez con el siguiente servidor antes de pasar a `:error`.
- Todas las peticiones llevan `X-Requested-With: radiolisp/<versión>`. Radio Browser pide identificarse, y por CORS solo admite esa cabecera, no `User-Agent` ni una propia.

### 4.4 `radiolisp.common.station`

Es una función pura `(normalize m)` que convierte el JSON de Radio Browser en:

```clojure
{:id "stationuuid" :name "nombre recortado" :url "url_resolved"
 :country "US" :codec "MP3" :bitrate 192 :clicks 560 :tags ["jazz" "soul"] :hls? false}
```

Reglas:
- `bitrate` 0 o `codec` `"UNKNOWN"` se convierten en `nil`, y la vista los muestra como "—".
- Como mucho 3 tags.
- Los nombres se recortan (`trim`) pero no se truncan: el truncado lo hace la vista con CSS.

Es `.cljc` porque el backend la reutilizará.

## 5. Diseño visual

Generado con el skill `design-taste-frontend`: estilo `accent-ladder`, seed 396668359.

- **Matiz único: raspberry.** El tono pleno `#b8336a` va solo en marcas pequeñas (botón de play/pausa, relleno del volumen, el cuadrado de la emisora que suena, la barra de la fila marcada y el subrayado del input con foco). Sus tintes cubren las áreas, y el fondo tira un paso hacia el matiz.
- **Tokens** (`_tokens.scss`):

  ```scss
  $accent: #b8336a;   $accent-soft: #fdf2f7;  $accent-track: #f8dbe8;  $accent-ink: #6b1d3e;
  $ground: #fbf8f9;   $surface: #ffffff;      $raised: #f5f1f3;        $active: #f1ebee;
  $border: #ebe4e7;   $rule: #f3eef0;         $control: #d8cfd3;
  $text: #181214;     $muted: #665a5f;        $faint: #72656b;         $negative: #c4242a;
  ```

  Se exponen como custom properties en `:root`, para que un modo oscuro futuro solo tenga que redefinirlas.
- **Radio 0 en todo.**
- **Sin degradados, sin sombras en reposo** (solo el diálogo de ayuda, que flota) y sin iconos en recuadros tintados.
- **Tipografía**: DM Sans (400, 500, 600 y 700), una sola familia, con cifras tabulares (`tabular-nums`) en el reloj, el bitrate y las escuchas.
  - Tamaños: título de emisora 28 px (24 en móvil), cuerpo 14 px, textos secundarios 13 px.
- **Marca**: los paréntesis de Lisp dibujados como ondas de radio, `((•))`. En el reproductor, esas ondas laten mientras suena, y respetan `prefers-reduced-motion`.
- **Movimiento**:
  - La columna se desplaza al abrir la búsqueda (280 ms) y el panel aparece con una transición de 200 ms.
  - Todo lo demás es feedback: press, hover y focus.
  - Sin entradas animadas.

## 6. Layout

Una sola columna centrada, de **560 px como máximo**. Esquinas fijas:

- Arriba a la izquierda, la marca y "radiolisp".
- Arriba a la derecha, "Atajos ?" y el selector ES · EN.

### Escritorio y tablet (≥ 640 px)

**Reproductor solo** (hay emisora y la búsqueda está cerrada):

- La tarjeta del reproductor va centrada en la pantalla. De arriba abajo contiene:
  1. Estado: ondas, "En directo" y el reloj, o "En pausa".
  2. Nombre de la emisora como `h1`, con un máximo de 2 líneas.
  3. Datos: país · formato · tags.
  4. Controles: play/pausa (56 px) · silencio · slider de volumen · porcentaje.
- **Debajo de la tarjeta**, separada 8 px y fuera de ella, la barra "Buscar emisora", que se abre con un clic o con `/`.

**Buscando**:

- La columna sube a la parte alta de la pantalla.
- La barra de búsqueda se convierte en el panel: el input queda justo debajo del reproductor y, debajo, la cabecera de la lista ("12 emisoras · por escuchas en 24 h", más el modo) y los resultados.
- La lista tiene scroll propio hasta el borde inferior de la pantalla.
- La ✕ dentro del input cierra la búsqueda. Solo aparece si hay una emisora elegida.

**Primera visita** (sin emisora):

- La tarjeta muestra "Nada sonando · Busca una emisora para empezar", con el play deshabilitado.
- La búsqueda está abierta con "Más escuchadas hoy" y el foco en el input.

**Filas de la lista**:

- Línea 1: nombre, truncado con elipsis, y a la derecha el formato.
- Línea 2: país · escuchas en 24 h · tags.
- La fila marcada lleva el fondo `active`; en modo navegar, también una barra izquierda de 3 px en el color de acento.
- La emisora que suena lleva un cuadrado de acento delante del nombre.
- Al pasar el ratón, la fila muestra un contorno de 1 px.

**Elegir una emisora** cambia el reproductor, empieza a sonar, cierra el panel y lleva el foco al botón de play/pausa.

### Móvil (< 640 px)

**Reproductor solo**:
- La misma tarjeta a ancho completo, con 16 px de margen.
- Se ocultan el slider y el porcentaje de volumen; queda el silencio, porque el volumen lo controla el sistema.
- La barra de búsqueda va debajo.
- Las esquinas solo muestran la marca, sin el texto "radiolisp".

**Buscando**: una hoja a pantalla completa.
- Arriba, una tira compacta con play/pausa (44 px) y el nombre de la emisora en una línea.
- Debajo, el input a 16 px (para que iOS no haga zoom) con la ✕.
- La lista ocupa el resto. Las filas miden al menos 56 px.
- Las esquinas se ocultan.

**En general**: objetivos táctiles de al menos 44 px, y la página nunca hace scroll horizontal.

## 7. Teclado

El teclado es modal, como vim. **Insertar** significa escribir en el input. **Normal** significa navegar por la lista. Las teclas globales funcionan fuera del input y con la ayuda cerrada.

| Contexto | Tecla | Acción |
|---|---|---|
| Global | `/` | Abrir la búsqueda; si ya está abierta, volver al input |
| | `espacio` | Reproducir o pausar (salvo con el foco en un botón) |
| | `h` / `l` | Volumen −5 / +5 |
| | `m` | Silenciar o activar el sonido |
| | `?` | Abrir la ayuda de atajos |
| | `esc` | Cerrar la búsqueda (si hay emisora) |
| Insertar | `↓` `↑`, `ctrl-j` `ctrl-k`, `ctrl-n` `ctrl-p` | Mover la marca |
| | `enter` | Elegir la emisora marcada (si la búsqueda está pendiente, la lanza ya) |
| | `esc` | Pasar a modo normal |
| Normal | `j` `k`, `↓` `↑` | Mover la marca |
| | `gg` / `G` | Primera o última emisora (`gg` con 500 ms entre pulsaciones) |
| | `enter` | Elegir la emisora marcada |
| | `i`, `/` | Volver a insertar |
| | `esc` | Cerrar la búsqueda |
| Ayuda abierta | `esc` | Cerrarla (diálogo nativo) |

- **Ratón y teclado se combinan**: escribir en el input pasa a insertar y hacer clic en la lista elige.
- **Accesibilidad**:
  - La lista es un `role="listbox"` con `aria-activedescendant`, y las filas son `role="option"`.
  - Los botones tienen `aria-label` traducido, con el nombre de la emisora.
  - El estado del reproductor se anuncia con `aria-live="polite"`.
  - El foco siempre es visible.

## 8. Internacionalización

- **Diccionarios** EDN en `frontend/resources/i18n/{es,en}.edn`, cargados en tiempo de compilación con una macro (`shadow.resource/inline`) y leídos con `cljs.reader`. Las claves son keywords con namespace (`:player/live`, `:search/results`, `:error/network`…).
- **Interpolación**: `(t :search/empty {:query q})` sobre `"Ninguna emisora se llama «{query}»."`.
- **Plurales**: un valor puede ser un mapa `{:one "…" :other "…"}`, y la forma se elige con `Intl.PluralRules`.
- **Números** con `Intl.NumberFormat` del idioma activo.
- **El estado guarda claves y datos, nunca textos.** Los errores son vectores como `[:error/network {:at 1729420320000}]` y la vista los traduce, formateando la hora con `Intl.DateTimeFormat`.
- **Idioma**: se detecta de `navigator.languages` (el primero que sea `es` o `en`, y si no, `en`). Se cambia con el selector ES · EN y no se persiste. `<html lang>` se actualiza con cada cambio.
- **Una prueba comprueba** que `es.edn` y `en.edn` tienen exactamente las mismas claves.
- **Alcance**: todo el texto visible, `aria-label`, `title`, la ayuda y los errores. El contenido de Radio Browser (nombres, tags) no se traduce.

## 9. Errores y estados

| Situación | Dónde | Qué se ve |
|---|---|---|
| Buscando | Lista | "Buscando…" y 5 filas esqueleto con la geometría real, sin shimmer |
| Sin resultados | Lista | Caja discontinua: "Ninguna emisora se llama «q». Prueba con una parte del nombre." |
| Radio Browser caído (tras reintentar con otro servidor) | Lista | Caja con un punto rojo: "No se pudo conectar con Radio Browser a las HH:MM." y "Reintentar" |
| Conectando con el stream | Reproductor | "Conectando…", sin reloj; a los 10 s pasa a error |
| El stream falla (`error` del `<audio>`, CORS o 404) | Reproductor | "No se pudo reproducir: el stream no responde." y el play vuelve a ▶ |
| Emisora HLS (`:hls? true` o `.m3u8`) | Reproductor | "Esta emisora usa un formato que aún no se puede reproducir." Se sigue mostrando en los resultados |
| Contenido mixto (`http://` en una página `https://`) | Reproductor | Se trata como error del stream |

- **Limitación conocida**: muchas emisoras emiten por `http://`. En `http://localhost` funcionan, pero desplegado en `https` el navegador las bloqueará. Se resolverá con un proxy en el backend (§11).
- **Valores ausentes**: un bitrate o un codec desconocido se muestra como "—", y una emisora sin tags muestra "sin etiquetas".

## 10. Tests y criterios de terminado

**Tests** (`bb test`, `cljs.test` en Node):
- `radiolisp.common.station`: normalización, nulos, recorte y tags.
- `radiolisp.common.format`: bitrate y reloj.
- `radiolisp.frontend.actions`: cada acción de §4.2, comprobando el estado y los efectos devueltos; en especial descartar respuestas viejas (`seq`), el debounce y los 2 caracteres mínimos.
- `radiolisp.frontend.keymap`: la tabla de §7, contexto por contexto.
- `radiolisp.frontend.i18n`: interpolación, plurales y paridad de claves entre `es.edn` y `en.edn`.

Las vistas, los efectos y el audio se comprueban a mano en el navegador.

**Hecho cuando**:
1. `nix develop` y luego `bb dev` levantan la app en `http://localhost:8080`.
2. Escribir "jazz" muestra resultados mientras se escribe, sin un fetch por tecla.
3. Una emisora MP3 suena, se pausa, cambia de volumen y se silencia, con ratón y con teclado.
4. Todo el flujo funciona solo con teclado: abrir la búsqueda, escribir, moverse con flechas y con `j`/`k`, elegir, pausar y cerrar.
5. Cambiar a EN traduce toda la interfaz, errores incluidos.
6. El layout es correcto a 1440, 768 y 390 px.
7. `bb test`, `bb lint` y `bb fmt:check` pasan sin errores.
8. `inspect.mjs --app` del skill de diseño da 0 FAIL a 1440, 768 y 390 px.

## 11. Después del MVP: backend

Esto no forma parte del MVP. Se documenta para que las decisiones de ahora no lo bloqueen.

- Un módulo `backend/` en Clojure (JVM), reactivo y basado en eventos, que se comunica con el frontend por **WebSocket**. Candidatos: http-kit o Ring + Jetty con `ring.websocket`, core.async como bus de eventos y transit sobre el WebSocket.
- Un mensaje entrante será otra acción para `actions/handle` (`[:now-playing/received {...}]`). Enviar un mensaje será otro efecto (`[:ws/send ...]`). La arquitectura de §4 no cambia.
- `common/` compartirá el dominio (`station.cljc`) y los schemas de eventos (malli, que entra en esa fase).
- Primeras funcionalidades: metadatos ICY de lo que suena (como `packages/api` de radiolise) y un proxy de streams `http` para servirlos en `https`.
