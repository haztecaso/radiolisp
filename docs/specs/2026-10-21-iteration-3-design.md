# radiolisp — iteración 3: imágenes, personalizar y barra del reproductor

- Fecha: 2026-10-21
- Estado: borrador, pendiente de revisión del usuario
- Parte de: la iteración 2 (`2026-10-20-iteration-2-design.md`). Lo que no se
  dice aquí sigue como allí.
- Referencia visual: `docs/design/prototype-3/index.html` (contenido del panel
  **1 · actual**, placa clara en oscuro).

## 1. Qué cambia

- La marca deja de recargar la página.
- Las imágenes de las emisoras se ven enteras: sin recortes, y con margen las
  que tienen fondo transparente. Un modo automático decide; el usuario puede
  cambiarlo.
- «Personalizar…» sustituye a la edición del nombre sobre la casilla: un modal
  con vista previa, el nombre y cómo se ve la emisora.
- La barra del reproductor enseña siempre lo que suena bajo el nombre. La
  calidad, los tags y las acciones de la emisora pasan a un panel «sonando
  ahora».
- En móvil: la barra cabe, el nombre de la app se ve, y el audio aguanta los
  cortes con la pantalla bloqueada.
- «Editar en radio-browser.info» pasa a «Ver en radio-browser.info» (ya hecho).

Fuera: reordenar en móvil (se retoma en otra iteración).

## 2. Marca

`radiolisp` (icono y texto) es un botón, no un enlace a `/`: lleva la cuadrícula
arriba (scroll suave, o inmediato con `prefers-reduced-motion`) y vacía el
filtro. En móvil se ve también el texto «radiolisp».

## 3. Imágenes

### Modos

Cada emisora con imagen se ve de una de tres formas:

| Modo | Cómo se ve |
|---|---|
| `:image` | Imagen casi cuadrada (proporción entre 0,8 y 1,25): cubre la casilla (`cover`). Apaisada o vertical: contenida (`contain`) a todo el lado mayor, sobre el color de su borde si se conoce, o sobre la placa. |
| `:pad` | Contenida con un margen del 14 % por lado, sobre la placa. |
| `:text` | El nombre entero, como una emisora sin imagen (iteración 2, §4). |

- Con margen (`:pad`), una imagen pequeña (lado mayor < 96 px) nunca se amplía
  más del doble. Como imagen (`:image`) llena la casilla igual que las grandes,
  aunque se vea borrosa: es lo que pide quien elige ese modo.
- Sin imagen, o con la imagen rota, el modo es siempre `:text` y no se puede
  cambiar.
- Las mismas reglas valen para la casilla, la miniatura de la barra, la del
  panel y la vista previa de «Personalizar». La miniatura en `:text` sigue
  mostrando las iniciales.

### La placa

El fondo detrás de toda imagen sin color de borde (se ve a través de sus
píxeles transparentes):

- Tema claro: `--surface` (blanco).
- Tema oscuro: un gris claro (`--plate`, `#ece6e9`), para que se vean los
  logos negros sobre transparente. Si la detección sabe que el logo es claro,
  la placa es el tinte oscuro de la casilla.

### Modo automático (la detección)

Decide el modo por defecto. Si el usuario elige uno a mano, ese manda para
siempre (no hay vuelta a automático).

1. Cuando la imagen visible carga (`:art/sized`), y si la caché no tiene ya un
   resultado para esa URL, se lanza `:art/probe`: una segunda `Image` con
   `crossOrigin = "anonymous"`. La visible no lleva ese atributo, porque sin
   CORS dejaría de cargar.
2. **Con CORS** (medido: ~38 % de los favicons de las 300 emisoras más
   escuchadas). Se dibuja en un canvas de 32 × 32 y se leen los píxeles:
   - **transparente** si al menos la mitad de los píxeles del borde (el anillo
     exterior) tienen alfa < 128 (lo semitransparente cuenta);
   - **oscuro** si la luminancia media de los píxeles opacos (alfa > 128) es
     < 0,35;
   - **color de borde**: la media de los píxeles del borde cuando todos son
     opacos y parecidos (desviación pequeña); si no, ninguno.
3. **Sin CORS** (el canvas queda bloqueado y `getImageData` lanza): se decide
   por la extensión de la URL. `.jpg`/`.jpeg` es opaca; PNG, ICO, SVG, WebP,
   GIF o desconocida, transparente. Oscuro y color de borde: desconocidos.
4. Modo automático: `:pad` si es transparente, `:image` si no.

La lectura (`art/probe-result`: píxeles → `{:alpha? :dark? :edge}`) y la
decisión (`art/auto-mode`, `art/guess-by-url`) son funciones puras con tests;
solo dibujar en el canvas es efecto.

### Dónde se guarda

- **Resultado de la detección:** en la caché de emisoras
  (`radiolisp/station-cache`), junto a `name` y `favicon`: `art: {src, alpha,
  dark, edge}`. `src` es la URL analizada; si el favicon cambia, se vuelve a
  analizar. La primera pintura ya usa el modo bueno.
- **Elección manual:** en la favorita, `:display` (`:image`, `:pad` o
  `:text`). Es opcional como `:custom-name`: la `:version` sigue en 1, y un
  valor desconocido se ignora al leer. No se exporta (como los atajos).

## 4. Personalizar

### Menú de la emisora

Queda así (sin marcas de «nuevo»):

- Visitar la web
- Votar (con el número)
- Asignar o cambiar el atajo
- **Personalizar…** (icono de deslizadores)
- Mover al principio
- Copiar el enlace del stream
- **Ver en radio-browser.info** (icono «abrir fuera»)
- Línea de salud
- Quitar de favoritas

Salen «Cambiar nombre» y «Recuperar el nombre original»: los dos viven ahora en
el modal. Sale también la edición del nombre sobre la casilla (el
contenteditable), con sus acciones `:rename/*`.

### El modal

- `<dialog>` nativo, título «Personalizar».
- **Escritorio:** a la izquierda la vista previa de la casilla, **al mismo ancho
  que las casillas de la cuadrícula** en ese momento; a la derecha los
  controles. **Móvil (≤ 760 px):** hoja abajo a todo el ancho, la vista previa
  arriba y centrada, los controles debajo, los botones a todo el ancho.
- **Vista previa:** la casilla tal como quedará (imagen o nombre, banda del
  nombre, tinte), sin botones. Sigue al borrador en vivo.
- **Nombre:** un `<input>` (no contenteditable: así se abre el teclado en
  Firefox Android). Llega con el foco y el texto seleccionado. Solo si la
  emisora tiene nombre propio, debajo: «En Radio Browser: «X» · Usar el
  original». Ningún otro texto de ayuda.
- **En la cuadrícula:** un selector de tres botones, «Imagen · Con margen ·
  Texto», con el modo efectivo marcado (el automático si no hay elección
  manual). Ningún texto de ayuda. Sin imagen, el selector no aparece.
- **Color del nombre** (añadido después, prototipo en `docs/design/prototype-4`):
  nueve muestras cuadradas «Aa», cada una con su tinta sobre su fondo.
  La primera, «Automático», es el aspecto de siempre (tinte de la casilla y
  tinta de la marca) y quita la elección. Las otras ocho son una paleta fija,
  nunca un selector libre: rojo, naranja, ocre, verde, turquesa, azul,
  violeta y rosa. Cada una es un par (claro y oscuro) de hex fijos en
  `_tokens.scss` (`$inks`), ajustados a mano tono a tono al estilo del paso 11
  de Radix Colors. Todas las tintas llegan a 4,5:1 sobre su fondo, los cuatro
  tintes y el velo de la banda. La tinta colorea el nombre entero (900), el
  nombre de la banda sobre la imagen (700) y las iniciales de la miniatura;
  sin imagen, además, la casilla toma el fondo del mismo tono. El front no
  hace cuentas de color: la favorita guarda la clave y el CSS elige el par
  según el tema.
  Una favorita nueva (añadida o importada) llega ya con color: uno de los
  menos usados entre las favoritas, elegido por su id (`favs/new-color`,
  puro), así que parece aleatorio y no se repite mientras quede alguno libre.
- **Botones:** «Cancelar» y «Guardar». Enter en el campo guarda; Esc o clic
  fuera cancela. Al cerrar, el foco vuelve al ⋯ de la casilla.
- **Guardar:** el nombre sigue las reglas de la iteración 2 (vacío o igual al
  de Radio Browser quita `:custom-name`). El modo se guarda en `:display` solo
  si el usuario tocó el selector; el color, en `:color` (sin clave si es
  automático). Nada se guarda al cancelar.

## 5. Barra del reproductor

- **Izquierda:** miniatura y texto forman un solo botón que abre o cierra el
  panel «sonando ahora» (`aria-expanded`), con un chevron pequeño junto al
  nombre.
  - Línea 1: ondas (sonando) y el nombre.
  - Línea 2, siempre lo que pasa ahora:
    - el título que llega del backend;
    - si la emisora no da títulos: los tags, en gris;
    - «Conectando…»; «Se cortó · reconectando (n/4)…» (§7); el error en rojo;
    - sin emisora: la pista de siempre.
- **Derecha, escritorio:** volumen y play/pausa. La web y el ♥ pasan al panel.
- **Derecha, móvil (≤ 760 px):** solo play/pausa. El nombre y el título tienen
  todo el ancho que queda.

- **Título de la pestaña:** mientras suena (también conectando o reconectando),
  «Nombre - radiolisp», con el nombre que enseña la cuadrícula; si no,
  «radiolisp».

## 6. Panel «sonando ahora»

- **Escritorio:** popover de 380 px sobre la barra, alineado a la izquierda.
  Se cierra con Esc, ×, clic fuera o tocando otra vez la barra.
- **Móvil:** hoja a todo el ancho justo encima de la barra (la barra sigue
  visible), con velo detrás.
- **Contenido** (variante 1 del prototipo):
  - Imagen de 96 px (112 en móvil), nombre, «Sonando» y el título; sin título,
    «La emisora no lo dice».
  - Chapa de calidad (`MP3 128 kbps`, `HLS`) y país.
  - Tags en chapas.
  - Cuatro acciones: Web ↗, Favorita (♥, añade o quita; quitar confirma),
    Votar (con el número), Copiar stream.
- Al abrir, el foco va al ×; al cerrar, vuelve al botón de la barra.

## 7. Audio con la pantalla bloqueada (Firefox Android)

Hoy, si el stream se atasca mientras suena, `:audio/waiting` pasa a
`:connecting` y, a los 15 s, `:audio/timeout` lo da por fallido y para el
audio. Con la pantalla bloqueada (red que se duerme, timers frenados) es la
causa más probable de que se pare. Cambia así:

- **Reconexión:** si la emisora ya estaba sonando y el stream falla o se
  atasca más de 15 s, no se da error: el estado pasa a `:reconnecting` y se
  vuelve a cargar el stream desde cero (no se reanuda el elemento) tras 1, 2, 4
  y 8 s. Al sonar otra vez, el contador vuelve a 0. Tras 4 intentos, el error
  de siempre.
- Solo para emisoras que llegaron a sonar; el primer intento de conexión sigue
  como ahora.
- **Verificación en el dispositivo:** depurar con `about:debugging` por USB en
  Firefox Android y bloquear la pantalla. Si el corte viene de otra parte (un
  `pause` del sistema, el WebSocket de títulos), se anota aquí y se corrige en
  la misma iteración.

## 8. Arquitectura

- **`views/art.cljs`:** `mode` (efectivo: `:display`, si no automático, si no
  `:text`), `auto-mode`, `guess-by-url`, `probe-result` (puro) y la vista con
  los tres modos. `fit` se queda para lo cuadrado y lo pequeño.
- **Efectos nuevos:** `:art/probe` (la `Image` con CORS y el canvas).
- **Acciones nuevas:** `:art/probed`, `:customize/open`, `:customize/name`,
  `:customize/display`, `:customize/color`, `:customize/use-original`, `:customize/save`,
  `:customize/cancel`, `:panel/toggle`, `:panel/close`, `:brand/top`,
  `:audio/reconnect`.
- **Estado:** `:customize` (`{:id :name :display :color}`, el borrador), `:panel?`,
  `[:player :retries]`, y `:art` guarda también el resultado de la detección.
- **`common/favorites.cljc`:** `set-display`, `colors`, `set-color` y la
  lectura defensiva de `:display` y `:color`.
- **i18n**, en los 12 idiomas: claves nuevas para el menú, el modal, el panel y
  la reconexión; salen `:menu/rename`, `:menu/reset-name` y `:rename/label`.

## 9. Hecho cuando

1. Ninguna imagen se recorta salvo las casi cuadradas en `:image`; los logos
   negros sobre transparente se ven en tema oscuro.
2. Una favorita con CORS y fondo transparente sale en `:pad` sin tocar nada; el
   modo elegido en «Personalizar» persiste entre recargas y gana al automático.
3. «Personalizar» cambia nombre y modo con vista previa en vivo, a escritorio y
   móvil; en Firefox Android abre el teclado.
4. En un móvil de 360 px la barra muestra nombre y título sin cortar el botón
   de play; el panel enseña calidad, país, tags y las cuatro acciones.
5. Clicar la marca no recarga.
6. Con la pantalla bloqueada, un corte de red de hasta medio minuto no para la
   radio: vuelve a sonar sola.
7. `bb test`, `bb lint` y `bb fmt:check` pasan; `bb e2e`, adaptado, pasa; el
   inspector del skill da 0 FAIL.
