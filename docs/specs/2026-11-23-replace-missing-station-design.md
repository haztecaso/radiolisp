# radiolisp: reemplazar una emisora que ya no existe

Cuando Radio Browser deja de conocer una favorita (borrada de su base de datos),
el tile muestra «Ya no está en Radio Browser» y no se puede reproducir. Hoy la
única salida es buscarla a mano, añadir otra y quitar la vieja, perdiendo
posición, tecla, color y nombre propio. Esto añade «Buscar reemplazo»: elegir
otra emisora que **ocupe el lugar** de la perdida.

Aprobado por el usuario el 2026-11-23.

## 1. Comportamiento

- **Intercambio en el sitio.** La emisora elegida toma la posición, la tecla,
  el color, el modo de carátula y el nombre propio del tile perdido. El id viejo
  desaparece. Una sola acción, nada que limpiar.
- **Cómo se empieza.** En un tile perdido:
  - un elemento «Buscar reemplazo» en su menú ⋯, el primero, solo si el id está
    en `(:missing favorites)`;
  - el aviso «Ya no está en Radio Browser» pasa a ser un botón que hace lo mismo.
- **La paleta de búsqueda en modo reemplazo.**
  - Se abre con la consulta rellena: el nombre de Radio Browser guardado en la
    caché (o en la favorita) y, si no hay, el nombre propio. El nombre propio
    puede ser un apodo que no encuentre nada.
  - Una franja «Reemplazando «nombre»» arriba de los resultados. Cerrar la
    paleta cancela.
  - Clic o Intro en un resultado **reemplaza y cierra**. No se reproduce nada
    en este modo y las filas pierden los botones de reproducir y de corazón.
  - Un resultado que ya es favorita sale atenuado con la etiqueta «Ya es
    favorita», con `aria-disabled`, y no se puede elegir. No hay duplicados ni
    se borra nada en silencio.
- Tras reemplazar: aviso «Reemplazada X por Y», el id viejo sale de
  `:missing`, se guarda y la caché de emisoras se rehace.

## 2. Datos (`common/favorites.cljc`, puro)

`replace [favorites old-id station]`:
- sustituye en el sitio `:id` y `:name` por los de `station`; conserva `:key`,
  `:color`, `:display` y `:custom-name`;
- devuelve `favorites` sin cambios si `old-id` no está o si `station` ya es
  favorita (la interfaz lo impide; esto es la red de seguridad).

## 3. Estado y acciones (`actions.cljs`, puras)

- `:search` gana `:replacing`: el id viejo o `nil`.
- `:favorites/find-replacement id`: cierra el menú, abre la búsqueda con
  `:replacing id` y la consulta del §1.
- `:search/choose`: con `:replacing`, si la elegida ya es favorita no hace nada;
  si no, despacha `:favorites/replace old new`. Sin `:replacing`, como hoy.
- `:favorites/replace old new`: aplica `favs/replace`, quita `old` de
  `:missing`, cierra la búsqueda (limpia `:replacing`), guarda, rehace la caché
  y avisa con `[:toast/replaced {:old … :new …}]`.
- `:search/toggle-favorite` no hace nada con `:replacing`. `:search/close`
  limpia `:replacing`. `:search/open` desde otro sitio también lo limpia.

## 4. Vistas

- `views/grid.cljs`: `span.flag` → `button.flag` en el caso `missing?`, con
  `aria-label` que dice qué hace. El aviso «sin señal» sigue siendo un `span`.
- `views/menu.cljs`: elemento «Buscar reemplazo» (icono de búsqueda) primero en
  `station-items` cuando el id está en `:missing`.
- `views/search.cljs`: franja de reemplazo; filas sin acciones en modo
  reemplazo; fila ya favorita atenuada y no elegible.
- Estilos en `frontend/resources/styles/`, siguiendo las reglas de pantallas de
  app de `design-taste-frontend`. El botón del aviso conserva el aspecto del
  `.flag` actual y gana foco visible.

## 4b. Idiomas

Solo `es.edn` y `en.edn` (regla de AGENTS.md; el resto se traduce aparte, si el
usuario lo pide). Claves nuevas: `:menu/find-replacement`, `:search/replacing`,
`:search/already-favorite`, `:toast/replaced`. El botón del aviso reutiliza
`:menu/find-replacement`: su nombre accesible es el texto del aviso seguido de
esa etiqueta, así contiene lo que se ve y dice qué hace.

## 5. Pruebas

- Unitarias de `favs/replace`: conserva tecla, color, `:display` y nombre
  propio; id desconocido; duplicada.
- De acciones: flujo de reemplazo, `:replacing` limpio al cerrar y al abrir la
  búsqueda normal, elegir una duplicada no hace nada, el toast, `:missing`.
- `bb e2e`: ampliar el caso «Gone FM»: aviso → paleta → reemplazo conserva la
  posición. `/json/url` y `/json/vote` siguen bloqueados.

## 6. Fuera de alcance

- Sugerir reemplazos automáticamente.
- Un botón «quitar todas las perdidas».
- El enlace «Radio Browser» del menú de una perdida apunta a una página muerta;
  queda como está.
