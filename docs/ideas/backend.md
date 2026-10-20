# Ideas que necesitan backend

Lista viva de funcionalidades que el frontend solo no puede dar. No es un plan:
cada idea necesitará su spec antes de implementarse. La arquitectura prevista
está en `docs/specs/2026-10-20-mvp-design.md` §11 (Clojure en la JVM, WebSocket,
core.async, transit, malli y el dominio compartido en `common/`).

Leyenda de coste: **bajo** (CPU y red casi nulas), **medio** (estado o base de
datos), **alto** (ancho de banda o CPU por oyente).

## 1. Reproducción

### Proxy para emisoras http · alto · **hecho**

Implementado según `docs/specs/2026-10-20-backend-design.md`, con fan-out.

Servida por https, la app no puede cargar un stream `http://`: el navegador lo
bloquea o lo pasa a https, y en los puertos que solo hablan HTTP (`:8000`,
`:8001`…) esa conexión se queda colgada. Hoy la app avisa con
`:error/http-only` tras `https-attempt-ms`. En las favoritas de prueba eran 4 de
32 (Radio Eustachio, Radio Malva, Contrabanda FM, Radio Mai).

- El backend sirve `https://…/stream/<uuid>` y reenvía los bytes del stream
  original.
- Solo se usa cuando la versión `https://` falla. El resto de emisoras siguen
  yendo directas del navegador a la emisora.
- El coste es de ancho de banda, no de CPU: unos 58 MB por hora y oyente a
  128 kbps; 100 oyentes a la vez son unos 12,8 Mbps de salida. Las conexiones
  duran horas, así que el servidor tiene que ser asíncrono (http-kit, Aleph o
  virtual threads).
- **Fan-out**: una sola conexión de subida por emisora, repartida entre todos sus
  oyentes, como un relay de Icecast. La entrada queda en una por emisora; la
  salida sigue siendo una por oyente.
- Que no sea un proxy abierto: acepta un uuid de Radio Browser y resuelve la URL
  en el servidor, nunca una URL arbitraria. Con límite de peticiones (rate limit).
- Elegir un hosting con tráfico incluido (por ejemplo Hetzner); el tráfico de
  salida de AWS, GCP o Fly sale caro.
- No sirve para saltarse bloqueos por región: el 451 de RTVE con VPN se queda
  como está (`:error/blocked`).

### Transcodificación · alto

Algunos formatos no suenan en todos los navegadores (OGG Vorbis en Safari de iOS,
por ejemplo). El proxy podría transcodificar a AAC o MP3. Cuesta CPU por emisora
(con fan-out, no por oyente). Solo si los datos de uso lo justifican.

### Comprobación de streams propia · medio

Radio Browser comprueba cada emisora desde sus servidores, y su `lastcheckok` no
ve lo que ve el navegador. El backend podría probar periódicamente las emisoras
que la gente usa y guardar:

- si existe la versión `https://` (y así saltarse la espera de `https-attempt-ms`);
- si contesta 451 o 403;
- el tiempo hasta el primer byte;
- la URL final tras las redirecciones.

El frontend lo recibe junto con los datos de la emisora y elige el camino
directamente.

## 2. Metadatos y "lo que suena"

### Metadatos ICY por WebSocket · medio · **hecho**

Implementado según `docs/specs/2026-10-20-backend-design.md` (sin ID3 de HLS).

Como `packages/api` de radiolise: el backend abre el stream con
`Icy-MetaData: 1`, lee los `StreamTitle` y los empuja por WebSocket a los
clientes suscritos a esa emisora. Un solo lector por emisora sirve para todos sus
oyentes. Llega al frontend como acción (`[:now-playing/received {...}]`). Con
HLS, los metadatos van en ID3 dentro de los segmentos.

Encima de eso se pueden construir:

- **Historial por emisora**: las últimas canciones de cada una.
- **Scrobbling** a ListenBrainz o Last.fm, con la cuenta del usuario.
- **"¿Quién pone esto ahora?"**: buscar una canción o un artista entre las
  emisoras que se están leyendo.
- **Avisos**: avisar cuando empieza un programa o suena un artista.
- **Carátulas**: buscar la portada de la canción (MusicBrainz o Cover Art
  Archive) a partir del título.

## 3. Compartir y persistir

### Permalinks cortos · medio

- Una emisora: `<dominio>/s/<id-corto>` abre la app con esa emisora lista
  para sonar.
- Una lista: `<dominio>/l/<id-corto>` muestra la lista y deja importarla
  (añadir a favoritas o sustituirlas).
- Las listas compartidas se guardan en la base de datos del servidor. Se pueden
  borrar, y conviene que sean inmutables (una versión nueva genera un enlace
  nuevo).
- Vista previa al pegar el enlace en un chat (Open Graph: nombre, logo, número
  de emisoras).

### Favoritas en el servidor y sincronización · medio

Hoy viven en `localStorage` y viajan exportando un fichero. Con cuentas (o un
enlace mágico sin contraseña), las favoritas, los atajos y el orden se sincronizan
entre dispositivos. Los atajos de teclado podrían ser por dispositivo.

### Recientes entre dispositivos · bajo

"Recientes" puede hacerse en el frontend; con cuentas, viaja con las favoritas.

## 4. Radio Browser

### Caché o espejo de la API · medio

Hoy Radio Browser tiene un solo servidor vivo (`all`, `de1` y `de2` resuelven a
la misma IP) y a veces resetea conexiones. El backend podría:

- cachear búsquedas, el top y `byuuid` unos minutos;
- mantener un espejo de las emisoras usadas, para que la app funcione aunque
  Radio Browser no conteste;
- agrupar los clics (`/json/url`) y enviarlos sin exponer la IP de cada oyente.

### Datos corregidos · medio

Correcciones propias encima de Radio Browser (una URL que cambió, un logo roto,
la versión https de un stream) y, si procede, enviarlas a Radio Browser.

### Imágenes · bajo

Los logos `http://` también son contenido mixto en https, y muchos son enormes o
no existen. Un proxy de imágenes que las pase a https, las redimensione y las
cachee arregla las tres cosas.

## 5. Escuchar con otros

- **Escucha compartida**: una sala por WebSocket donde todos oyen la misma
  emisora y quien la crea cambia de emisora para todos.
- **Contadores en vivo**: cuánta gente de radiolisp escucha cada emisora ahora
  (agregado y anónimo).

## Riesgos y condiciones

- **Retransmisión**: reenviar un stream es retransmitirlo. Revisar los términos
  de las emisoras, sobre todo para el fan-out y la transcodificación.
- **Privacidad**: el historial, el scrobbling y los contadores son datos de
  escucha. Que sean opcionales, mínimos y sin rastreo de terceros.
- **Abuso**: un proxy o un acortador abiertos se usan para otras cosas. Siempre
  con uuids de emisoras conocidas y límite de peticiones.
