# radiolisp — iteración 2: favoritas

- Fecha: 2026-10-20
- Estado: aprobado (el usuario delegó la aprobación; revisión al final)
- Parte de: el MVP (`2026-10-20-mvp-design.md`). Esta iteración cambia el layout y
  añade la gestión de favoritas. Lo que no se dice aquí sigue como en el MVP:
  arquitectura, i18n y teclado vim.
- Referencia visual: `docs/design/prototype-2/index.html` (variantes elegidas:
  búsqueda **A · paleta**, nombre **superpuesto**, salud **etiqueta**).

## 1. Qué cambia

- La pantalla principal pasa a ser una **cuadrícula de favoritas** a todo el ancho,
  sin huecos entre casillas.
- El reproductor pasa a ser una **barra fija abajo**.
- **`/` filtra las favoritas.** **`a`** (o «+ Añadir emisora») abre una **paleta**
  que busca en todo Radio Browser.
- Nuevo: favoritas persistentes, reordenables, con atajos de teclado asignados a
  mano. Exportar e importar M3U. Votar. Contar clics. HLS. Media Session.

## 2. Datos guardados

Solo lo imprescindible. Todo lo demás se pide fresco a Radio Browser en cada
arranque, de modo que el stream, la imagen y la salud estén siempre al día.

```clojure
{:version   1
 :favorites [{:id   "960e57c5-…"   ; stationuuid: estable entre servidores
              :name "France Info"  ; respaldo, si la API falla o la emisora desaparece
              :key  "f"            ; atajo opcional (nil)
              :custom-name "FI"}   ; opcional: el nombre que le puso el usuario
             …]}                   ; el orden del vector es el orden de la cuadrícula
```

- **Nombre propio:** `:custom-name` solo existe si el usuario renombró la
  emisora. Manda sobre el de Radio Browser en la cuadrícula, el reproductor, los
  menús, los diálogos, los avisos, Media Session y la exportación. El filtro
  busca en los dos. La paleta enseña siempre el de Radio Browser. Un nombre
  vacío o igual al de Radio Browser quita `:custom-name`. Es un campo opcional:
  la `:version` sigue en 1, y uno vacío o que no sea texto se ignora al leer.

- **Dónde:** `localStorage`, clave `radiolisp/favorites`, serializado en EDN. Se
  escribe tras cada cambio. Al arrancar se pide `navigator.storage.persist()`.
- **Lectura defensiva:** un valor ilegible o con otra `:version` no rompe la app.
  Se trata como lista vacía y se avisa en la consola.
- **Al arrancar:** `GET /json/stations/byuuid?uuids=a,b,…` (una sola petición) llena
  un mapa `:stations` (id → emisora normalizada). Un id que no vuelve se marca como
  desaparecido y la casilla muestra el nombre guardado con «Ya no está en Radio
  Browser».
- **Caché de emisoras:** `localStorage`, clave `radiolisp/station-cache`, JSON
  `{id: {name, favicon}}` con las favoritas tal como se vieron por última vez.
  Se lee antes que las favoritas, así la primera pintura ya tiene nombre e
  imagen sin esperar a la API. Solo dibuja: la casilla no se puede reproducir
  hasta que llega `byuuid`, porque la URL del stream sale siempre de la API. La
  respuesta sustituye a la caché (lo que Radio Browser ya no conoce sale) y se
  reescribe solo si cambia; añadir, quitar o importar favoritas también la
  actualiza. Un valor ilegible es una caché vacía.
- **Al reproducir:** `GET /json/url/{id}`, sin esperar la respuesta. Así se cuenta el
  clic, como pide Radio Browser.

### Emisora normalizada (`common/station.cljc`)

Se amplía con `:homepage`, `:favicon`, `:votes`, `:check-ok?` (de `lastcheckok`),
`:last-check-at` y `:last-ok-at` (ISO 8601). Las cadenas vacías pasan a `nil`.

## 3. Exportar e importar (M3U, `.list` y Radiolise)

```
#EXTM3U
#EXTINF:-1 radiobrowser-uuid="960e57c5-…" tvg-logo="https://…",France Info
https://icecast.radiofrance.fr/franceinfo-midfi.mp3
```

- Es válido en VLC y otros reproductores; los atributos que no entienden se ignoran.
- **Lista de uuids (`.list`):** un uuid de Radio Browser por línea, todas las
  favoritas (también las que ya no tienen stream). Si la emisora tiene nombre
  propio, va detrás del uuid: `960e57c5-… Mi radio`. Al importar se ignoran las
  líneas vacías, los comentarios `#` y las que no empiecen por un uuid.
- **Nombres:** el título de M3U es el nombre que enseña la cuadrícula. Al
  importar, el nombre que trae el fichero (título M3U, `name` de Radiolise o el
  texto tras el uuid) pasa a nombre propio si no coincide con el de Radio
  Browser. Las favoritas que ya estaban conservan el suyo.
- **Exportar:** un diálogo elige el formato (M3U o `.list`) y el nombre del
  fichero (por defecto `radiolisp-favoritas`; la extensión la pone el formato).
- **Importar:** se elige un `.m3u`, `.m3u8`, `.list` o el `.txt` que exporta
  Radiolise (YAML con `name`, `url` e `id` por emisora). El formato sale de la
  extensión; cualquier otra se rechaza con un aviso.
  - Las entradas con uuid (`radiobrowser-uuid`, `id` o la línea del `.list`) se
    añaden por id.
  - Las demás se buscan por URL (`/json/stations/byurl`). Si hay duplicados se
    elige el de más votos, y las que no se encuentran se descartan.
  - Las que ya son favoritas no se duplican.
  - Un aviso resume el resultado: «Importadas 7 · 2 no encontradas».
  - También se importa soltando el fichero en cualquier parte de la página: un
    diálogo dice cuántas emisoras trae y pide confirmación. Un fichero de otro
    tipo o sin emisoras solo da el aviso.
  - Sin favoritas, el estado vacío ofrece «Importar una lista» junto a «Añadir
    emisora».
- Los atajos no se exportan: son personales del navegador.

## 4. Layout

### Barra superior (56 px, a todo el ancho)

- Marca.
- Campo «Filtrar favoritas» (`/`).
- Botón «+ Añadir emisora» (`a`).
- Menú ⋯ de la lista: «Exportar…» (diálogo) e «Importar…»; el cambio de tema
  (claro u oscuro); «Idioma», que abre en el mismo menú una página con cada
  idioma nombrado en su propia lengua (español, English, português, français,
  italiano, català, galego, euskara, română, български, русский, 中文) y «‹ Idioma»
  para volver (también Esc); y «Atajos de teclado».

### Cuadrícula

- `repeat(auto-fill, minmax(min(180px, 50%), 1fr))`, sin huecos ni bordes. La
  misma regla en todos los anchos: las columnas bajan de una en una y nunca
  quedan menos de 2. No hay título visible; el `h1` «Favoritas» existe pero está oculto
  visualmente.
- **Casilla:** imagen cuadrada con el nombre superpuesto en una banda blanca al
  pie.
  - **Imagen grande y cuadrada** (lado menor ≥ 150 px, proporción entre 0,8 y 1,25):
    ocupa toda la casilla (`cover`).
  - **Imagen pequeña o apaisada:** centrada y contenida sobre un tinte del matiz.
  - **Sin imagen o rota:** el nombre entero, centrado, sobre un tinte estable
    derivado del id, y sin banda al pie. El tamaño va de 14 a 40 px: es el
    mayor (en `cqi`, el ancho de la casilla) al que el nombre cabe sin partir
    palabras (`art/name-size`, puro, con anchos de letra medidos para Roboto
    Black). Si ni al mínimo cabe, se parte donde haga falta. La miniatura del
    reproductor y la paleta siguen con las iniciales.
- **Esquina superior izquierda:** el cuadradito del atajo. Muestra la tecla
  asignada; si no hay, aparece «+» punteado al pasar el ratón. Al pulsarlo se abre
  el diálogo del atajo.
- **Esquina superior derecha:** ⋯ (visible al pasar el ratón o con foco; siempre
  visible en móvil). Abre el menú de la emisora:
  - Visitar la web.
  - Votar (con el número de votos).
  - Asignar o cambiar el atajo.
  - Cambiar nombre: el nombre se vuelve editable sobre la propia casilla, con
    el texto seleccionado. Enter o salir del campo guarda; Esc cancela.
  - Recuperar el nombre original (solo si tiene uno propio).
  - Mover al principio.
  - Copiar el enlace del stream.
  - Ver en radio-browser.info (`https://www.radio-browser.info/history/{id}`).
  - Una línea de salud.
  - Quitar de favoritas.
- **Salud:** si `:check-ok?` es falso, la etiqueta «Sin señal» aparece sobre la
  imagen. El menú detalla el último fallo y el último OK.
- **Sonando:** contorno interior de 3 px en el color de acento. **Hover:** contorno
  de 2 px.
- **Reordenar:** arrastrar y soltar. Una barra de acento indica dónde caerá.
  También «Mover al principio» desde el menú.
- **Última casilla:** «+ Añadir emisora», oculta mientras se filtra.
- **Clic en una casilla:** la reproduce, o la pausa si ya suena.
- **Vacía:** «Todavía no tienes favoritas», con un botón para añadir.
- **Filtro sin coincidencias:** «Ninguna favorita se llama «x»» y un enlace «Buscar
  «x» en Radio Browser» que abre la paleta con esa búsqueda.

### Barra del reproductor (72 px, fija abajo)

- Miniatura (con la misma lógica de imagen que las casillas), nombre y ondas
  animadas mientras suena.
- **Línea de datos:** la calidad en una chapa con borde (`MP3 128 kbps`, o `HLS`) y
  los tags aparte, en gris. Sin país ni «En directo».
- **Controles, de izquierda a derecha:**
  - Web de la emisora ↗.
  - ♥: añade o quita. Quitar pide confirmación.
  - Volumen: un icono; el slider se despliega al pasar el ratón o con foco.
  - Play/pausa (48 px, en el color de acento).
- **Errores:** sustituyen a la línea de datos.

### Paleta (buscar en Radio Browser)

- Un modal de 680 px, arriba y centrado, sobre la cuadrícula atenuada. Se cierra
  con Esc o clicando fuera.
- Arriba el campo; debajo los resultados, o «Más escuchadas hoy» si la búsqueda
  está vacía.
- **Cada resultado:** miniatura de 40 px, nombre, chapa de calidad, país y tags, y
  los botones ▶ y ♥.
- **Reproducir no cierra la paleta**, para poder escuchar varias antes de elegir.

### Diálogos (nativos, minimalistas)

- **Quitar de favoritas:** «¿Quitar de favoritas?», el nombre y si libera un atajo.
  Botones «Cancelar» y «Quitar».
- **Atajo:** «Atajo para «X»». Se pulsa una tecla y se previsualiza.
  - **Teclas reservadas:** `/ ? a h j k l m g i G f`, espacio, Enter, Esc y Tab.
    Si se pulsa una, lo indica.
  - Si la tecla ya es de otra emisora, avisa de que pasará a esta.
  - Botones: «Quitar atajo» (si tenía uno), «Cancelar» y «Guardar».

### Avisos (toast)

Una línea abajo a la derecha, encima de la barra, durante 2,5 s. Por ejemplo:
«Añadida a favoritas», «Voto enviado», «Enlace copiado», «Exportadas 12».

## 5. Teclado

| Contexto | Teclas |
|---|---|
| Global | `/` filtrar · `a` añadir (paleta) · espacio play/pausa · `h` `l` volumen · `m` silencio · `?` ayuda · **teclas asignadas**: reproducir esa favorita |
| Campo de filtro | Enter reproduce la primera coincidencia · Esc vacía el filtro y sale |
| Paleta, escribiendo | ↓ ↑ · `ctrl-j`/`ctrl-k`/`ctrl-n`/`ctrl-p` mover · Enter reproducir · Shift+Enter añadir o quitar de favoritas · Esc pasar a navegar |
| Paleta, navegando | `j` `k` · `gg` `G` · Enter reproducir · `f` añadir o quitar · `i` o `/` volver a escribir · Esc cerrar |
| Diálogo de atajo | cualquier tecla la propone · Esc cancelar |
| Cambiando el nombre | Enter guardar · Esc cancelar · el resto se escribe |

Media Session: las teclas multimedia del sistema hacen play/pausa, y siguiente o
anterior saltan a la favorita siguiente o anterior.

## 6. Audio

- **HLS:** hls.js 1.7, cargado bajo demanda en un módulo aparte de shadow-cljs
  (`:hls`). Solo se descarga al reproducir una emisora HLS en un navegador sin HLS
  nativo; Safari usa el nativo. Si el navegador no tiene MSE, se muestra «formato
  no soportado».
- Se mantienen el intento `https://` primero y los timeouts del MVP.
- **Media Session:** título (nombre de la emisora), artista «radiolisp», la imagen
  como artwork y el estado reproduciendo o en pausa.

## 7. Arquitectura

Se mantiene el flujo acción → `handle` (puro) → efectos. Lo nuevo:

- **`common/favorites.cljc`** (puro, con tests):
  - `add`, `remove`, `move-before`, `move-to-front`.
  - `assign-key`, `clear-key`, `key-owner`, `reserved-key?`.
  - `matching` (filtro).
  - `serialize`, `deserialize`.
  - `->m3u`, `parse-m3u`, `->list`, `parse-list`, `parse-radiolise`.
  - `file-format`, `export-filename`.
- **`common/station.cljc`:** nuevos campos (§2).
- **`frontend/views/art.cljs`:** `fit` (puro: anchura y altura → `:cover`,
  `:contain` o `:small`) y la vista de la imagen.
- **Estado:**
  - Nuevas claves: `:favorites` (lista y estado de carga), `:stations` (id →
    emisora), `:art` (id → encaje o `:broken`), `:filter`, `:menu`, `:dialog` y
    `:toast`.
  - `:search` empieza cerrado.
- **Efectos nuevos:**
  - `:storage/load`, `:storage/save`, `:storage/persist`, `:cache/load`,
    `:cache/save`.
  - `:api/stations-by-id`, `:api/click`, `:api/vote`, `:api/lookup-urls`.
  - `:file/download`, `:file/pick`, `:clipboard/write`.
  - `:media/update`.
  - `:dom/show-dialog`, `:dom/close-dialog`.
- **Imágenes:** `:on {:load [[:art/sized id :event.target/natural-width
  :event.target/natural-height]] :error [[:art/failed id]]}`.

## 8. Fuera de esta iteración

Recientes, compartir por enlace, temporizador, modo oscuro, PWA y "lo que suena"
(necesita backend).

## 9. Hecho cuando

1. Añadir, quitar (con confirmación), reordenar y asignar atajos persiste entre
   recargas.
2. Al recargar, los datos de las emisoras llegan en una sola petición `byuuid`.
3. Exportar e importar M3U da la misma lista (sin los atajos).
4. Una emisora HLS suena en Chromium (con hls.js).
5. El teclado de §5 funciona, y las teclas asignadas cambian de emisora.
6. `bb test`, `bb lint` y `bb fmt:check` pasan. `bb e2e`, adaptado al nuevo layout,
   pasa. El inspector del skill da 0 FAIL.
7. El e2e no altera las estadísticas de Radio Browser: bloquea `/json/url` y
   `/json/vote`.
