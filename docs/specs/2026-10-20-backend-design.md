# radiolisp — backend: proxy http y lo que suena

- Fecha: 2026-10-20
- Estado: aprobado e implementado (rama `feat/backend`); desviaciones en §13
- Parte de: `2026-10-20-mvp-design.md` §11 y `docs/ideas/backend.md` (§1 «Proxy para
  emisoras http» y §2 «Metadatos ICY por WebSocket»). Compartir y persistir (§3 de
  las ideas) queda fuera: no hay base de datos ni cuentas.
- Referencia: `packages/api` y `packages/metadata-client` de radiolise
  (`~/src/radiolise.gitlab.io`).

## 1. Qué se construye

1. **Proxy de streams `http://`.** Servida por https, la app ya no se rinde con
   `:error/http-only`: si la versión `https://` de la URL falla, pide el stream a
   `/api/stream/<uuid>` y el backend reenvía los bytes de la emisora.
2. **Lo que suena.** El backend lee los metadatos ICY (`StreamTitle`) de la emisora
   que suena y los empuja por WebSocket. El reproductor muestra el título y lo pasa a
   Media Session (pantalla de bloqueo, notificaciones del sistema).
3. **Un solo proceso** sirve el build del frontend, la API y el WebSocket en el
   mismo origen.

Sin backend (el frontend servido como estático, o `bb dev` en el 8080) la app
funciona exactamente como hoy: sin títulos y con `:error/http-only`.

## 2. Qué aprendemos de radiolise

`packages/api` de radiolise (Node, Express y `ws`):

- **Un lector por stream, compartido.** `streamDataStore[url]` guarda una conexión a
  la emisora y el conjunto de sockets suscritos. El último que se va la cierra.
- **Lector ICY.** Pide el stream con `Icy-MetaData: 1`. Si la respuesta trae
  `icy-metaint: N`, el cuerpo alterna N bytes de audio, un byte de longitud L y
  `L×16` bytes de metadatos (`StreamTitle='…';StreamUrl='…';`). Sin `icy-metaint`
  la emisora no tiene metadatos y se informa del error. Ignora los bloques
  repetidos y los anuncios (`adw_ad`, `AdCreativeId`). Al acabar el stream, lo
  reabre.
- **Protocolo.** El cliente manda `subscribe {url}` y `unsubscribe`; el servidor,
  `setTitle {title}` y `reportError {type}`. Un socket sigue una sola emisora: un
  `subscribe` nuevo sustituye al anterior. Ping cada 30 s y cierre del socket que
  no contesta.
- **El cliente** se reconecta tras 2 s y, al reconectar, vuelve a suscribirse.

Lo que cambiamos:

- **Uuid de Radio Browser, nunca una URL.** radiolise acepta cualquier URL: es un
  lector abierto de cualquier recurso de la red. radiolisp resuelve el uuid en el
  servidor (§5), y así no hay proxy ni lector abierto.
- **Una sesión por emisora sirve audio y títulos.** Si alguien escucha por el proxy,
  la misma conexión de subida da los títulos a los suscritos.
- **EDN en el WebSocket**, y los mensajes del servidor son ya acciones del
  frontend (§6).
- **Defensa contra SSRF** (§5.3): una emisora de Radio Browser puede apuntar a
  `http://127.0.0.1:…`.

## 3. Arquitectura

```
navegador                                backend (JVM 21, un proceso)
─────────                                ─────────────────────────────
<audio> ── https://emisora (directo) ──▶ emisora
<audio> ── GET /api/stream/<uuid> ────▶  handler ─┐
WebSocket ─ /api/ws ──────────────────▶  socket ──┤
                                                  ▼
                                        relay: una sesión por uuid
                                        ┌──────────────────────────┐
                                        │ lector (virtual thread)  │── GET url, Icy-MetaData: 1 ──▶ emisora
                                        │ icy/split: audio | título│
                                        │ oyentes:   chan por cada │──▶ bytes a cada GET /api/stream
                                        │ suscritos: sockets       │──▶ [:now-playing/title …]
                                        └──────────────────────────┘
```

- **Stack:** Clojure 1.12 en JDK 21. Ring 1.15 con `ring-jetty-adapter` (Jetty 12)
  y `ring.websocket`. El pool de Jetty usa virtual threads, así que cada oyente
  ocupa un hilo virtual que escribe bloqueando y la contrapresión sale sola.
  Conexión de subida con un cliente HTTP/1.0 propio sobre socket (§5.2). core.async para la cola de
  cada oyente. Sin router: cuatro rutas en un `case`.
- **Módulo nuevo `backend/`** (`radiolisp.backend.*`), con `deps.edn` propio y
  `common/` como dependencia local, como el frontend.

### 3.1 Espacios de nombres

| ns | Qué hace | Puro |
|---|---|---|
| `radiolisp.common.protocol` (cljc) | Mensajes del WebSocket: constructores, `encode`, `decode` y validación de la forma | sí |
| `radiolisp.backend.icy` | Separa audio y metadatos de un stream ICY; lee `StreamTitle` | sí |
| `radiolisp.backend.config` | Lee la configuración del entorno, con valores por defecto | sí |
| `radiolisp.backend.limits` | Límites por IP y globales (contadores y ventana de peticiones) | sí, sobre un átomo |
| `radiolisp.backend.guard` | ¿Esta URL apunta a una dirección pública? (SSRF) | casi: resuelve DNS |
| `radiolisp.backend.radio-browser` | Resuelve un uuid a emisora normalizada, con caché | no |
| `radiolisp.backend.upstream` | Abre la conexión a la emisora siguiendo redirecciones con `guard` | no |
| `radiolisp.backend.relay` | Sesiones por uuid: lector, oyentes, suscritos, reconexión, cierre | no |
| `radiolisp.backend.stream` | Handler de `GET /api/stream/<uuid>` | no |
| `radiolisp.backend.socket` | Handler del WebSocket | no |
| `radiolisp.backend.http` | Rutas, ficheros estáticos, IP del cliente | no |
| `radiolisp.backend.main` | Arranca Jetty con la configuración; `-main` | no |

En el frontend:

| ns | Cambio |
|---|---|
| `radiolisp.frontend.socket` (nuevo) | El WebSocket: conexión perezosa, reconexión, resuscripción. Impuro, como `audio` |
| `radiolisp.frontend.stream-url` (nuevo) | Qué URLs probar, en orden (puro) |
| `radiolisp.frontend.audio` | El proxy como último candidato en páginas https |
| `radiolisp.frontend.actions` | `:now-playing/*`; suscribir al sonar, darse de baja al parar |
| `radiolisp.frontend.effects` | `:socket/subscribe`, `:socket/unsubscribe`; `:media/update` con artista |
| `radiolisp.frontend.views.player` | El título bajo el nombre de la emisora |

## 4. Proxy de streams

### 4.1 Cuándo lo usa el frontend

`audio/candidates` recibe también el uuid de la emisora:

| Página | URL de la emisora | Candidatos, en orden |
|---|---|---|
| cualquiera | `https://…` | la URL |
| `https` | `http://…`, no HLS | `https://…` (misma URL), `/api/stream/<uuid>` |
| `https` | `http://…`, HLS | `https://…`; si falla, `:error/http-only` como hoy |
| `http` (desarrollo) | `http://…` | `https://…`, la URL original |

El paso de un candidato al siguiente es el de hoy: error del `<audio>` o
`https-attempt-ms` sin arrancar. HLS no pasa por el proxy: habría que reescribir la
lista `.m3u8` y cada segmento, y queda fuera.

Cuando falla el proxy, `report-failure!` lee el estado con un `fetch` (mismo
origen, así que siempre se puede leer) y despacha `[:audio/failed status
{:proxy? true}]`:

| Respuesta del proxy | Error |
|---|---|
| sin respuesta, 404 o 405 (no hay backend) | `:error/http-only`, como hoy |
| 429 o 503 | `:error/busy` (nuevo: «El servidor de radiolisp está ocupado. Prueba en un rato.») |
| otro | `:error/stream` |

### 4.2 `GET /api/stream/<uuid>`

1. El uuid tiene que casar con `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`;
   si no, 400.
2. Límites (§7): si se superan, 429 (por IP) o 503 (globales).
3. Resuelve la emisora (§5). Desconocida: 404. HLS: 422.
4. Se une a la sesión del relay (la crea si no existe) y espera a que la conexión
   de subida conteste, como mucho `upstream-connect-ms` (10 s). Si falla: 502.
5. Responde 200 con:
   - `Content-Type` de la emisora (`audio/mpeg` si no lo da);
   - `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`;
   - el cuerpo: primero la **ráfaga inicial** (los últimos `burst-bytes`, 64 KiB, que
     guarda la sesión; a 128 kbps son 4 s de audio y el navegador arranca enseguida,
     como en Icecast) y luego el audio según llega, **sin los metadatos ICY**.
6. Cuando el navegador cierra, la escritura falla y el oyente sale de la sesión.

Un oyente lento no frena a los demás: cada uno tiene una cola de
`listener-queue-chunks` (64 trozos, unos 8 s de audio a 128 kbps). Si la cola está
llena cuando llega un trozo, el oyente se desconecta (el navegador lo ve como un
corte y reintenta el usuario). Unirse a media canción no es problema: los
decodificadores MP3 y AAC se sincronizan solos con la cabecera de trama siguiente.

## 5. Resolver una emisora

### 5.1 Radio Browser

`radio-browser/station` pide `GET https://all.api.radio-browser.info/json/stations/byuuid/<uuid>`
con `User-Agent: radiolisp/<versión>` y la normaliza con
`radiolisp.common.station/normalize` (`:url` es `url_resolved`, o `url`). La
respuesta se guarda en memoria `station-ttl-ms` (10 min), también la de «no
existe» (para que un uuid falso no genere una petición cada vez). Un error de red
o un 5xx de Radio Browser no se guarda y responde 502.

### 5.2 Conexión de subida

`upstream/open!` hace `GET <url>` con `Icy-MetaData: 1` y el `User-Agent` con un
cliente **HTTP/1.0 propio sobre socket** (TLS con verificación del nombre para
`https`). `java.net.http.HttpClient` no sirve: SHOUTcast v1, muy común en las
emisoras solo http del puerto 8000, contesta `ICY 200 OK`, que ese cliente rechaza.
HTTP/1.0 además descarta los cuerpos `chunked`. Conecta en `upstream-connect-ms` a
la dirección que devolvió `guard`; las redirecciones se siguen a mano (hasta 5),
cada una por `guard`. Una respuesta que no es 2xx es un fallo. Devuelve `{:url
:status :headers :content-type :metaint :body :socket}`, con el tiempo de lectura
del socket en `upstream-stall-ms`.

### 5.3 Guardia (SSRF)

Antes de conectar, y en cada redirección:

- solo `http` y `https`;
- el host se resuelve y **todas** sus direcciones tienen que ser públicas: ni
  loopback, ni privadas (10/8, 172.16/12, 192.168/16, fc00::/7), ni link-local
  (169.254/16, fe80::/10), ni CGNAT (100.64/10), ni `0.0.0.0`, ni multicast;
- los tests la desactivan con `:allow-private? true` (servidor de pruebas local).

`guard/check!` devuelve la dirección comprobada y el cliente conecta a esa misma,
así que el DNS no puede cambiar entre la comprobación y la conexión (DNS
rebinding).

## 6. Metadatos por WebSocket

### 6.1 Protocolo (`radiolisp.common.protocol`)

Un mensaje es un vector EDN en un frame de texto. Los del servidor son ya
acciones del frontend, con nombres de su espacio `:now-playing/*`.

Cliente → servidor:

```clojure
[:now-playing/subscribe {:id "960e57c5-…"}]   ; sustituye a la suscripción anterior
[:now-playing/unsubscribe {}]
```

Servidor → cliente:

```clojure
[:now-playing/title {:id "960e57c5-…" :title "Artista - Canción"}]  ; :title nil = sin título ahora
[:now-playing/unavailable {:id "960e57c5-…" :reason :no-metadata}]
;; :reason ∈ #{:no-metadata :hls :unknown-station :unreachable :busy}
[:now-playing/rejected {:reason :malformed}]                         ; mensaje ilegible
```

- `encode` es `pr-str`. `decode` lee con el lector EDN (`clojure.edn` en la JVM,
  `cljs.reader` en el navegador) y devuelve nil si el texto no se lee o no es uno
  de los mensajes de la dirección esperada (`client-message?`,
  `server-message?`: tipo conocido, mapa con `:id` uuid y `:title` cadena o nil,
  un `:reason` conocido). Un frame de más de 1 KiB se descarta sin leerlo.
- El frontend despacha solo lo que `server-message?` acepta: el servidor no puede
  inyectar acciones arbitrarias.
- Sin malli: las formas son tres y una función por dirección basta. malli llegará
  cuando haya más mensajes (desviación de §11 del MVP).

### 6.2 Servidor

- `GET /api/ws` con `Upgrade: websocket`; sin él, 426. Límite de sockets por IP (§7).
- Cada socket sigue como mucho una emisora (como radiolise). `subscribe` lo quita
  de la sesión anterior y lo une a la de `:id`: si esa sesión ya tiene título, se lo
  manda enseguida.
- Resolver la emisora falla → `unavailable` con `:unknown-station` (404), `:hls`,
  `:unreachable` (Radio Browser o la emisora no contestan) o `:busy` (límite global
  de sesiones).
- La emisora contesta sin `icy-metaint` → `unavailable :no-metadata`. Si la sesión
  no tiene oyentes del proxy, se cierra: no tiene sentido descargar audio que nadie
  oye.
- Ping cada 30 s; el socket que no contesta en el siguiente ping se cierra.
- Al cerrar, el socket sale de su sesión.

### 6.3 Lectura ICY (`radiolisp.backend.icy`)

Una máquina de estados pura:

```clojure
(icy/initial-state metaint)                ; {:metaint n :phase :audio :remaining n …}
(icy/feed state bytes)                     ; → {:state s' :audio [byte-array …] :metadata [byte-array …]}
(icy/stream-title metadata-bytes)          ; → "Artista - Canción" | "" | nil
```

- `feed` acepta trozos de cualquier tamaño y en cualquier corte (a mitad del byte de
  longitud o del bloque de metadatos).
- `stream-title` decodifica en UTF-8 y, si no es UTF-8 válido, en ISO-8859-1 (muchas
  emisoras viejas). Busca `StreamTitle='…';` (el valor puede contener `'`, así que
  corta en el último `';` antes del siguiente `Clave='`, o al final). Recorta
  espacios y quita los `\0` de relleno. Devuelve nil si es un anuncio (`adw_ad`,
  `AdCreativeId`) o si no hay `StreamTitle`.
- La sesión publica solo cuando el título cambia. Un título vacío (`""`) se publica
  como `:title nil`: la emisora dice que ahora no hay nada.

### 6.4 Frontend

Estado nuevo en el reproductor:

```clojure
:player {…
         :now-playing nil}   ; nil | {:id uuid :title "…" | nil}
```

- **Suscribirse:** al llegar `:audio/playing` de una emisora no HLS cuyo
  `:now-playing` no es ya de esa `:id`: `:now-playing {:id id :title nil}` y el
  efecto `[:socket/subscribe id]`.
- **Darse de baja:** al pausar, al fallar, al parar y al cambiar de emisora:
  `:now-playing nil` y `[:socket/unsubscribe]`. Así el servidor solo lee las
  emisoras que alguien oye de verdad.
- **`[:now-playing/title {:id :title}]`:** si `:id` es la de `:now-playing`, guarda
  el título y emite `[:media/update {:title título :artist emisora :artwork favicon}]`
  (sin título: `{:title emisora :artist "radiolisp"}`, como hoy). Si no, se ignora
  (llegó tarde).
- **`[:now-playing/unavailable …]`** y **`[:now-playing/rejected …]`:** se dejan
  `:title nil`; no se muestra nada. No es un error para quien escucha.
- **Vista:** con estado `:playing` y título, la línea bajo el nombre de la emisora
  muestra el título (`.now-title`, una línea con elipsis y `title` con el texto
  completo) en lugar de calidad y tags. El `aria-live="polite"` que ya tiene esa
  línea lo anuncia.

`radiolisp.frontend.socket`:

- Conecta a `ws(s)://<host>/api/ws` **la primera vez** que hay una suscripción, no
  al cargar (el que solo mira la lista no abre nada).
- Guarda la suscripción actual. Al abrir (o reabrir) la manda.
- Si el socket se cierra con una suscripción viva, reintenta tras 2, 4, 8… hasta
  60 s. Sin suscripción, no reintenta. Sin backend, esto son unos pocos intentos
  fallidos en la consola de red mientras suena una emisora, y nada más.
- `unsubscribe` manda el mensaje y deja el socket abierto (la próxima emisora llega
  antes).
- Mensaje recibido → `protocol/decode` → si `server-message?`, `dispatch`.

## 7. Límites y configuración

Variables de entorno (`radiolisp.backend.config`), con sus valores por defecto:

| Variable | Defecto | Qué es |
|---|---|---|
| `PORT` | `8090` | Puerto HTTP |
| `HOST` | `127.0.0.1` | Interfaz; en producción, detrás de Caddy o nginx con TLS |
| `PUBLIC_DIR` | `public/` junto al jar; si no, `../frontend/resources/public` | El build del frontend |
| `TRUST_PROXY` | `false` | Si `true`, la IP del cliente es la última de `X-Forwarded-For` |
| `MAX_SESSIONS` | `32` | Emisoras leídas a la vez (conexiones de subida) |
| `MAX_LISTENERS` | `200` | Oyentes del proxy a la vez, en total |
| `MAX_LISTENERS_PER_IP` | `2` | Oyentes del proxy a la vez por IP |
| `MAX_SOCKETS_PER_IP` | `4` | WebSockets abiertos por IP |
| `STREAM_REQUESTS_PER_MINUTE` | `20` | Peticiones a `/api/stream` por IP y minuto |

Constantes en código (no configurables): `burst-bytes` 64 KiB,
`listener-queue-chunks` 64, `upstream-connect-ms` 10 000, `upstream-stall-ms`
30 000, `station-ttl-ms` 600 000, `idle-session-ms` 5 000.

`limits` es un mapa en un átomo: contadores de oyentes y sockets por IP y una
ventana de un minuto con las peticiones recientes de cada IP. Las IP se olvidan
cuando sus contadores vuelven a cero y su ventana queda vacía. **No se registran
IP en los logs** (privacidad: datos de escucha).

## 8. Ciclo de vida de una sesión (`relay`)

```
          join (oyente o suscrito)
 (nada) ───────────────────────────▶ :connecting ──ok──▶ :live ──fin/error──▶ :retrying
                                         │                 ▲                     │
                                       falla               └────────ok───────────┘
                                         ▼                                       │ 3 fallos
                                      :failed ◀──────────────────────────────────┘
 última salida ─▶ espera idle-session-ms ─▶ (sin nadie) cierra la conexión y se borra
```

- Las sesiones viven en un átomo `{uuid session}`. Crear o borrar una sesión es un
  `swap!`, así que dos peticiones a la vez para el mismo uuid comparten la misma.
- **Lector:** un virtual thread por sesión lee del `InputStream` en trozos de
  8 KiB, pasa cada trozo por `icy/feed` (o lo deja entero sin `metaint`), añade el
  audio a la ráfaga, lo ofrece (`offer!`) a la cola de cada oyente y publica los
  títulos nuevos a los suscritos con `ring.websocket/send` asíncrono.
- **Atasco:** el socket tiene `upstream-stall-ms` de tiempo de lectura; si no llega
  nada, la lectura falla y el lector lo trata como fin del stream.
- **Fin o error de la conexión con miembros:** `:retrying`, reintento tras 1, 2 y
  4 s. Los oyentes siguen conectados mientras tanto (el navegador espera con
  `waiting`). Tras el tercer fallo: `:failed`, se cierran las colas de los oyentes
  (su respuesta termina) y los suscritos reciben `unavailable :unreachable`.
- **Sin miembros:** tras `idle-session-ms` sin nadie (para no reabrir la emisora
  cuando alguien recarga la página) se cierra la conexión y se borra la sesión.
- **Sin `icy-metaint` y sin oyentes:** se cierra enseguida (§6.2).

## 9. Servir el frontend y desarrollo

- `GET /` y cualquier fichero de `PUBLIC_DIR` (`index.html`, `js/`, `css/`,
  `fonts/`) con su `Content-Type`. Un fichero que no existe: 404. Nada fuera de
  `PUBLIC_DIR` (Ring `file-response` con `:root` ya lo impide).
- `GET /api/health` → 200 `ok`.
- **Desarrollo:** `bb dev` como hasta ahora (shadow-cljs y Sass) y, en otra
  terminal, `bb backend`. La app se abre en **http://localhost:8090**: el backend
  sirve la salida de `bb dev` y la recarga en caliente sigue funcionando (shadow
  conecta con su propio puerto). El 8080 sigue siendo el frontend solo, sin
  backend.
- **Producción:** `bb build` deja en `dist/` la web (`dist/public/`) y el uberjar
  (`dist/radiolisp-backend.jar`, con tools.build en `backend/build.clj`). El jar,
  sin `PUBLIC_DIR`, sirve el `public/` que tenga al lado, y va detrás de un proxy
  con TLS. El servicio NixOS queda para cuando haya dónde desplegar.

## 10. Errores

| Situación | Dónde | Qué pasa |
|---|---|---|
| Página https, emisora solo http, el proxy suena | Reproductor | Suena; nada que avisar |
| Proxy sin backend (404 o red) | Reproductor | `:error/http-only`, como hoy |
| Proxy 429 o 503 | Reproductor | `:error/busy` |
| Proxy 502 (la emisora no contesta) | Reproductor | `:error/stream` |
| La emisora se corta y vuelve en menos de 7 s | Reproductor | «Conectando…» y sigue |
| La emisora no vuelve | Reproductor | El stream termina: `:error/stream` |
| Sin metadatos, HLS, WebSocket caído | Reproductor | Sin título, sin aviso |

## 11. Pruebas

- **`bb test`** corre también los tests del backend (`clojure -X:test` en
  `backend/`, con cognitect test-runner) y los de `common/` en la JVM.
- **`icy`:** trozos cortados en cada posición posible (incluido a mitad del byte de
  longitud), bloques de metadatos vacíos, títulos con `'`, UTF-8 y Latin-1,
  anuncios, sin `StreamTitle`.
- **`protocol`** (cljc, en Node y en la JVM): ida y vuelta, mensajes desconocidos,
  formas malas, frames enormes.
- **`guard`:** cada rango privado, IPv6, esquemas que no son http.
- **`limits`:** por IP y global, ventana deslizante, olvido de IP.
- **Integración (`relay`, `stream`, `socket`):** un Jetty de pruebas en un puerto
  libre sirve un stream ICY falso (títulos que cambian cada pocos trozos), y el
  backend arranca en otro puerto con un resolvedor de emisoras falso y
  `:allow-private? true`. Comprueba: dos oyentes comparten una conexión de subida;
  el audio llega sin metadatos; un suscrito recibe los títulos; el último en salir
  cierra la sesión; una emisora caída da 502; un oyente lento se desconecta sin
  afectar a otro; los límites dan 429; la ráfaga inicial llega.
- **Frontend (`actions`):** suscribir al sonar, darse de baja al pausar, fallar,
  parar o cambiar; títulos tardíos ignorados; `:media/update` con el título; el
  mapa de fallos del proxy a errores. `audio/candidates` es puro con la página
  como argumento y se prueba.
- **`bb e2e`:** si `URL` apunta al backend (`/api/health` contesta), comprueba que
  el WebSocket abre y que una emisora ICY conocida da título en menos de 30 s. Si
  no, lo salta y lo dice. Sigue bloqueando `/json/url` y `/json/vote`.

## 12. Fuera de alcance

- Compartir y persistir (permalinks, cuentas, sincronización): sin base de datos.
- HLS por el proxy y metadatos ID3 de HLS.
- Transcodificación, caché de Radio Browser para el frontend, proxy de imágenes.
- Historial, scrobbling, carátulas y demás cosas que se construyen sobre los títulos.
- Empaquetado y despliegue.

## 13. Desviaciones al implementar

- **Cliente de subida propio** (HTTP/1.0 sobre socket) en lugar de
  `java.net.http.HttpClient`, por las respuestas `ICY 200 OK` (§5.2). Con él se
  cierra también el hueco de DNS rebinding (§5.3), y el tiempo de lectura del
  socket sustituye al vigilante de atascos (§8).
- **`radiolisp.frontend.stream-url`**: `candidates` sale de `audio` a un
  espacio de nombres puro, porque `audio` usa `shadow.lazy` y no compila en el build
  de tests. El efecto `[:audio/play url hls? id]` lleva ahora el uuid.
- **Media Session** recibe `:artist` (la emisora cuando hay título, «radiolisp» si no).
- **Jetty** cierra el WebSocket tras `ws-idle-timeout-ms` (60 s) sin tráfico; el
  servidor hace ping cada 20 s, y el navegador contesta solo.
- **Logs** (añadidos tras la primera entrega): logback por consola con
  `clojure.tools.logging`, nivel con `LOG_LEVEL`. Sesiones abiertas, conectadas,
  cortadas y cerradas en `info`; títulos, oyentes, sockets y cada petición a `/api`
  en `debug`. Nunca IPs.
- **Desarrollo:** `bb dev` arranca también el backend (en `debug`), y el build de dev
  apunta el WebSocket a `http://localhost:8090` (`goog-define backend-origin`), así
  que la página del 8080 también recibe títulos (§9 decía abrir el 8090).
- **`play()` sin promesa:** algunos navegadores o extensiones devuelven `undefined`;
  `audio` lo tolera.
- **Rutas** con método distinto de GET o HEAD: 405. Jetty rechaza con 400 rutas
  ambiguas como `/../x` antes de llegar al handler.
