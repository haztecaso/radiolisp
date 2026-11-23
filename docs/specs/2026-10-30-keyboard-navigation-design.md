# radiolisp: navegación de la cuadrícula con teclado

Fecha: 2026-10-30. Sustituye a los atajos asignados a favoritas (iteración 2,
§3 «Atajo» y §5) y cambia las teclas de volumen y silencio.

## 1. Qué cambia

- **Fuera los atajos por favorita.** Desaparecen el cuadradito de la esquina
  superior izquierda de cada tile, el diálogo «Atajo para…», la opción del menú
  «Asignar/Cambiar atajo» y la frase del diálogo de quitar sobre el atajo que
  queda libre. El campo `:key` deja de existir en el modelo: al leer el
  almacenamiento se descarta (los datos guardados no cambian de versión).
- **Fuera el silencio con `m`.** La acción `:player/mute` sigue (botón).
- **Volumen con `-` y `+`** (también `=`, que es `+` sin mayúsculas). `h` y `l`
  pasan a mover la selección.

## 2. Selección

- La tile seleccionada es la que tiene el **foco** del navegador: no hay estado
  nuevo en el store. Así Tab y Shift+Tab recorren la cuadrícula radio a radio, y
  las flechas y `hjkl` mueven el mismo foco.
- Cada tile es un `<button class="play" id="tile-<uuid>" data-tile="<uuid>">`;
  la tile «Añadir emisora» es `#tile-add` con `data-tile="add"` y entra en la
  navegación como última celda.
- El botón de opciones (`.more`) sale del orden de tabulación
  (`tabindex="-1"`); sigue funcionando con ratón y se abre con `s`.
- Aspecto: borde interior de 3 px en `--text` (negro o blanco según el tema),
  distinto del acento de la que suena. Si la seleccionada es la que suena,
  el borde de selección va por fuera y el acento por dentro. Solo con
  `:focus-visible`: un clic no deja la tile marcada.

## 3. Movimiento en dos dimensiones

- `main` pasa al keymap la tile con el foco (`:tile`) y cuántas columnas tiene
  la cuadrícula a ese ancho (`:columns`, leído de `grid-template-columns`). El
  keymap y las acciones siguen puros.
- `actions/grid-cells` da las celdas en orden (ids y `:add`), igual que las
  pinta `views.grid`.
- Izquierda/derecha no saltan de fila; arriba/abajo no dan la vuelta. Bajando
  desde la penúltima fila hacia una última incompleta, va a la última celda.
- Sin nada seleccionado, la primera flecha o `hjkl` selecciona la que suena (si
  se ve), si no la primera. En el filtro, `↓` baja a la primera.

## 4. Teclas

| Contexto | Teclas |
|---|---|
| Global (sin paleta) | `←↓↑→` / `hjkl` seleccionar · `/` filtrar · `a` añadir · espacio play/pausa · `-` `+` volumen · `i` abrir/cerrar «sonando ahora» · `r` seleccionar una al azar · `R` poner una favorita al azar (y seleccionarla) · `?` ayuda |
| Tile seleccionada | Enter o espacio: el clic del botón (reproducir, o pausar si ya suena) · `s` opciones · Shift + dirección (`HJKL` o Shift+flechas): mover la tile · Tab/Shift+Tab: siguiente/anterior |
| Filtro | Enter reproduce la primera · `↓` a la cuadrícula · Esc limpia |
| Paleta | como antes; de las globales solo espacio, `-` `+` y `?` |

- **Mover la tile** (`:grid/shift`): a la derecha o abajo se coloca tras su
  vecina; a la izquierda o arriba, delante. Hacia «Añadir» va al final. Se
  guarda y mantiene el foco. «Añadir» no se mueve.
- **Al azar** (`:grid/random`): `r` elige entre las tiles visibles salvo la
  seleccionada; `R` entre las que Radio Browser conoce, salvo la que suena. El
  azar está en el efecto `:random/dispatch`, que despacha `:grid/select` o
  `:grid/play` con la elegida.
- Al cerrar el menú de una emisora o «Personalizar», el foco vuelve a su tile.

## 5. Ideas para después

`gg`/`G` primera y última, `.` ir a la que suena, `n`/`p` zapping, volver a la
emisora anterior, `1`–`9` por posición, `x` quitar, `c` personalizar.
