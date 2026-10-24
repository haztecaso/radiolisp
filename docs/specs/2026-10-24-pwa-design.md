# radiolisp: PWA

Que la app se instale como una app (Chrome, Edge, Android) y abra sin red con
las favoritas guardadas. Ni el audio ni la búsqueda funcionan sin red: los
streams son en directo y Radio Browser está fuera.

## 1. Manifest e iconos

- `manifest.webmanifest` junto a la página: `id`, `start_url` y `scope` son
  `./`, para que valga igual en `/` (backend) que en `/radiolisp/` (GitHub
  Pages). `display: standalone`.
- `background_color` y `theme_color`: `--ground` claro (`#fbf8f9`). El manifest
  solo admite un color; la `<meta name="theme-color">` sí sigue el tema: la
  cambia el efecto `:dom/theme` leyendo `--ground`.
- Iconos: solo la marca (paréntesis y punto en blanco sobre frambuesa, a sangre).
  `icon-192.png` e `icon-512.png` (`any`) y `icon-maskable-512.png` (la marca al
  75 %, dentro de la zona segura). Fuentes en `frontend/resources/icons/`; los PNG
  se generan a mano (`inkscape <svg> -w N -h N -o <png>`) y se versionan.
- El backend sirve `.webmanifest` como `application/manifest+json`.

## 2. Service worker

- `frontend/resources/sw.js` es una plantilla. `bb build` y `bb build:static` la
  escriben en la raíz de la release (`scripts/service_worker.clj`) con:
  - `FILES`: todo lo de la release salvo `index.html` (va como `./`), `sw.js` y
    los `.edn` y `.json` de shadow-cljs.
  - `VERSION`: los 12 primeros hex del SHA-256 de nombres y contenidos. Misma
    release, misma versión; cualquier cambio, versión nueva.
- Solo sirve el shell, caché primero: las peticiones GET a un fichero de `FILES`
  y la navegación a `./` o `./index.html` (sin query ni hash). Todo lo demás
  (Radio Browser, `/api/*`, streams, carátulas) no pasa por él.
- Una versión nueva se instala en segundo plano y **espera**: no hay
  `skipWaiting`. Toma el control cuando se cierran todas las ventanas de la app.
  Así una página que suena nunca mezcla dos builds (`js/hls.js` se carga tarde).
  Al activarse borra las cachés `radiolisp-*` anteriores.
- Se registra con el efecto `:sw/register` al arrancar (`:app/init`), solo si
  `radiolisp.frontend.config/service-worker` (false en `:dev`: el servidor de
  desarrollo no tiene `sw.js` y un shell en caché taparía las recargas).
- `bb e2e` abre las páginas con `Network.setBypassServiceWorker`: los bloqueos
  de `/json/url` y `/json/vote` no alcanzan las peticiones de un service worker.

## 3. «Instalar la app»

- Chrome y Edge emiten `beforeinstallprompt`. `main` lo intercepta
  (`preventDefault`: sin su banner), lo guarda en `effects` y despacha
  `[:install/available true]`; `appinstalled` despacha `[:install/available false]`.
- Estado: `:installable?`. Mientras es true, el menú de la lista muestra
  «Instalar la app» (`:menu/install`) tras el idioma.
- `[:install/prompt]` pone `:installable?` a false y ejecuta el efecto
  `:install/prompt` (`event.prompt()`, que vale una vez). Si se rechaza, el
  navegador ofrece otro evento más adelante.
- Firefox y Safari no tienen el evento: no ven la entrada. En iOS se instala con
  «Añadir a pantalla de inicio».

## 4. Hecho cuando

1. Chrome no da errores de instalabilidad (`Page.getInstallabilityErrors`) en
   `/` ni en `/radiolisp/`.
2. Tras una visita, la app abre sin red y muestra las favoritas guardadas.
3. Una release nueva llega tras cerrar todas las ventanas, sin recargas solas.
4. `bb dev` no registra service worker.
