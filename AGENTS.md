# OhmLauncher — AGENTS.md

Guía para agentes que trabajen en este proyecto.

## Qué es esto

**OhmLauncher** es un launcher de Android escrito en Flutter cuya interfaz se
genera de forma **reactiva desde JSON** almacenado en el almacenamiento externo
(`/sdcard/OhmLauncher/widgets_config.json`), con recarga en caliente al guardar.
Incluye un **bridge QML** que interpreta en Android los plugins escritos para el
ecosistema Omarchy (Quickshell/QtQuick), un marketplace de plugins
(omarchyplugins.com), apps instaladas reales, favoritos, multi-escritorio y
edición de widgets estilo launcher (borde, arrastrar, reordenar, redimensionar).

> Nota de marca: el proyecto se llamaba "Omarchy Launcher". Se renombró a
> **OhmLauncher** porque DHH no autorizó el uso de la marca. El código sigue
> siendo *compatible con el contrato de plugins de Omarchy*, pero toda la marca
> visible usa "Ohm".

## Identificadores (paquetes / IDs)

| Concepto | Valor |
|---|---|
| Paquete Dart (pubspec) | `ohm_launcher` |
| namespace / applicationId Android | `com.ohm.ohm_launcher` |
| MethodChannel nativo | `com.ohm/ohm` |
| Etiqueta Android (launcher) | `Ohm Launcher` |
| Ruta pública de datos | `/sdcard/OhmLauncher` |
| Ruta legacy (se migra una vez) | `/sdcard/OmarchyLauncher` |
| Plugin semilla | `io.github.ohm.demo.clock` |

Al renombrar, **no** tocar: `omarchyplugins.com` (servicio externo), la ruta
legacy de migración `kLegacyPublicRoot`, y nombres de componentes QML de
terceros (`OmarchyMenuScan`, etc.) que matchean tipos reales de plugins.

## Estructura

```
lib/
  main.dart                     # esqueleto: StorageService + DynamicWidgetEngine +
                                # discovery de plugins + `part` de lib/parts/*.dart
  parts/
    home_screen.dart            # estado principal de la home (escritorios, cajas,
                                # cajón, config, drag & drop de cajas)
    edge_boxes.dart             # _EdgeBox: cajas de borde + interacción 1s/3s/5s
    dynamic_engine.dart         # parser JSON -> árbol de widgets + helpers de color
    settings.dart               # hojas de configuración (escritorio / launcher)
    pickers.dart                # pickers de apps, widgets, plugins y cajas
    ...                         # storage, watcher, barras, radial, drawer, etc.
  qml_bridge/
    qml_parser.dart             # tokenizer + parser de QML a AST
    qml_runtime.dart            # evaluación de expresiones/propiedades QML
    qml_widgets.dart            # AST -> widgets Flutter
  plugin_network.dart           # HttpUtil + PluginRemoteFetcher (Dart puro)
  clock_widget.dart             # ClockText + formatClock (tokens tipo "dddd")
  clock_styles.dart             # estilos del reloj: "particles" (arena, tipo
                                # Arrival) y "ticker" (dígitos rodantes)
android/
  app/src/main/kotlin/com/ohm/ohm_launcher/MainActivity.kt  # canal nativo:
                                # apps instaladas, iconos, batería, launcher por defecto
examples/
  plugins/io.github.ohm.demo.clock/  # plugin de ejemplo sembrado en el dispositivo
test/
  widget_test.dart              # smoke test de la app
  qml_real_test.dart            # 4 tests con QML real de plugins
  fixtures/*.qml                # QML reales usados por los tests
tool/
  qml_parse_check.dart          # CLI: parsea un .qml y reporta
  installer_check.dart          # CLI: prueba el fetcher del marketplace
```

## Flujo de datos

1. `StorageService.ensureInitialized()` → migra `/sdcard/OmarchyLauncher` si
   existe, intenta la ruta pública `/sdcard/OhmLauncher` (con
   `MANAGE_EXTERNAL_STORAGE`); si no hay permisos, degrada a almacenamiento
   externo privado de la app.
2. `ensureConfigFile()` crea `widgets_config.json` por defecto si falta.
3. `FileWatcherEngine` (debounce 400 ms) observa el JSON y la carpeta de plugins
   y dispara `_reloadConfig()` / `_rescanPlugins()`.
4. `DynamicWidgetEngine.parseDesktop()` construye el árbol de widgets
   (`desktops[]` → `_DesktopPager`; nodos: container, text, clock,
   tiling_layout, spacer, apps_grid, battery, plugin_widget; soporte de `span`).
5. Los nodos `plugin_widget` se resuelven vía `PluginSnapshot.latest` y se
   renderizan con el bridge QML.

## Cajón de apps (swipe up)

- La lista de apps **NO** es un escritorio. Aparece al deslizar desde la barra
  inferior hacia arriba (`_AppDrawer`).
- El gesto sigue el dedo: `AnimationController` actualizado directamente en
  `onVerticalDragUpdate`; si se invierte antes de soltar, el cajón vuelve a
  ocultarse.
- Una vez abierto, se puede cerrar tocando el fondo oscurecido o arrastrando el
  *handle* superior hacia abajo.
- Las apps se cargan sin iconos al inicio (`getInstalledApps` ligero) y los
  iconos PNG se obtienen bajo demanda (`OhmPlatform.getAppIcon`) para evitar
  bloquear el hilo principal.

## Edición de widgets

- Long-press sobre un widget → modo edición (`_editDesktop`/`_editWidget` en
  `_OhmHomeScreenState`), cada widget se envuelve en `_EditableWidgetTile`
  (borde, arrastrar para reordenar, redimensionar ciclando `span` 1→2→4→1,
  eliminar).
- Long-press sobre el fondo → menú radial (`_RadialDesktopMenu`): agregar
  escritorio izquierda/derecha, agregar widget (`_WidgetPickerSheet`, incluye
  plugins bar-widget instalados) y configuración (`_LauncherSettingsSheet`:
  tipografía, escala de texto, color de fondo, launcher por defecto).
- Los ajustes se persisten en `settings.json` vía
  `StorageService.loadSettings/saveSettings` y se aplican con `MediaQuery`
  (textScaler) + `Theme` (fontFamily) en el `build` de la home.

## Drag & drop (interacción estándar, sistema-wide)

Todo elemento arrastrable (items de cajas de borde, cajas, y en el futuro los
widgets del escritorio) usa la **escalera de 3 fases** con long-press:

- **1s sin mover** → se **destaca el item** bajo el dedo (fase *item*). Arrastrar
  reordena el item dentro de su contenedor en vivo; si el dedo sale del
  contenedor, un preview sigue al dedo y al mantenerlo **1s dentro de otro
  contenedor** el item se mueve ahí (suelta confirma).
- **3s sin mover** → el resaltado pasa del item al **borde del contenedor**
  (fase *box*). Arrastrar mueve el contenedor por su polo (reordena el grupo) o,
  manteniendo **1s sobre otro polo**, el contenedor se engancha y sigue al dedo
  hasta soltar.
- **5s sin mover** → abre la **configuración** del elemento.

Implementación en `_EdgeBoxState` (`lib/parts/edge_boxes.dart`):
`RawGestureDetector` con un `LongPressGestureRecognizer` configurado con
**`postAcceptSlopTolerance: double.infinity`** — imprescindible: sin esto el
recognizer **cancela el long-press al mover el dedo** después de aceptarlo y el
drag "se pierde" (el borde se destaca pero al mover no pasa nada). El drag de la
caja se sigue con `onLongPressMoveUpdate` (fase box → `_handleBoxMove`), que
reporta movimientos en TODA la pantalla mientras el recognizer conserve el
pointer. **CRÍTICO para que no se pierda el gesto:** NO hacer `setState` en la
home durante el drag. El wireframe de polos usa `ValueNotifier`s
(`_barDragTarget`/`_boxDragAccent`/`_boxDragSourceEdge`) y se reconstruye con
`ValueListenableBuilder` en `_wireframeOverlay`; si se llamara `setState` en la
home en cada `onPointerMove`, se reconstruiría el `_EdgeBox` y el
`LongPressGestureRecognizer` perdería el pointer activo (la caja deja de seguir
al dedo). Solo el reorden en vivo (`_reorderBoxesLive`) usa `setState`, y solo
cuando cambia el orden. La escalera
avanza **solo con el dedo quieto** (`_scheduleLadder`,
`Timer.periodic` 100ms que acumula tiempo desde `_stillSince`; `_markMoved`
reinicia el acumulador al superar `_kTouchSlop`). Así, si el usuario mueve el
dedo, el modo actual **persiste hasta soltar** sin saltar a la fase siguiente:
mover tras 1s mantiene el drag del item hasta soltar; mover tras 3s mantiene el
drag de la caja hasta soltar. El preview que sigue al dedo es un `OverlayEntry` insertado en `Overlay.of(context)`. Todo reorden es animado:
items dentro de una caja vía `AnimatedSwitcher` con clave `_itemsKey` (+
`AnimatedContainer` en bordes/resaltados), cajas dentro de su polo vía
`AnimatedSwitcher` con clave del orden del grupo. El reordenamiento entre cajas
usa el registro global de rects `_boxRects` (`home_screen.dart`), reportado por
cada caja vía `onReportRect`. Reordenar un item dentro de una caja se commitea
con `onItemsReordered`; moverlo a otra caja con `onItemDropped`; el
reordenamiento de una caja dentro de su polo se calcula en `_onEdgeBoxDragEnd`
comparando la coordenada del dedo contra los centros (`_boxRects`) del grupo.
Durante el drag de una caja (fase box): la caja original se atenúa a 25% de
opacidad y un **preview** de la caja completa (todos sus items, con glow del
acento) sigue al dedo vía `OverlayEntry`. El seguimiento del dedo en fase box NO
depende del `LongPressGestureRecognizer` (que puede dejar de reportar
`onLongPressMoveUpdate` al salir de la caja): al entrar en modo box,
`_startBoxCapture()` inserta un **`OverlayEntry` a pantalla completa con un
`Listener`** (`behavior: HitTestBehavior.translucent`) que captura
`onPointerMove`/`onPointerUp` de forma global, garantizando que la caja siga al
dedo en toda la pantalla (incluido fuera de la caja). Los **polos** son bandas
anchas (~90px) de **degradado** a lo largo de los 4 bordes de la pantalla
(estilo borde curvo de Samsung), dibujadas en `_wireframeOverlay`/`_PoleGlow`
(`home_screen.dart`): un `LinearGradient` que se desvanece desde el borde hacia
el centro; el borde destino (bajo el dedo) se tiñe del **color de la caja
arrastrada** (`_boxDragAccent`), el origen queda en blanco y el resto muy tenue;
el brillo pulsa con un `AnimationController` repetido (neón/glow). El
`boxShadow` se recorta en los bordes de pantalla — usar `LinearGradient`, no
`boxShadow`, para el glow de borde. El drag de caja usa el patrón de la barra de
favoritos (movimiento por pan que sigue al dedo en toda la pantalla), replicado
con `onLongPressMoveUpdate` + los `ValueNotifier` del wireframe.

El **reorden de cajas dentro del mismo borde es en vivo** (mover una caja sobre
o bajo otra en el mismo lado): `_reorderBoxesLive` (home_screen.dart) recalcula
el orden del grupo según la coordenada del dedo contra los centros (`_boxRects`)
y lo guarda en `_liveBoxOrder`, que `_buildEdgeBoxes` usa temporalmente
mientras arrastra; al soltar, `_onEdgeBoxDragEnd` persiste `_liveBoxOrder` (si
el destino sigue siendo el borde de origen) o cambia de borde (si es otro).

**Regla:** NUNCA eliminar un elemento por long-press; el borrado solo ocurre
desde su configuración.

## Verificación (obligatoria tras cada cambio)

```bash
cd /home/node/Desarrollo/OhmLauncher
flutter analyze        # debe quedar limpio (0 issues)
flutter test           # 15 tests: smoke + 4 QML reales + parser/clock
dart run tool/qml_parse_check.dart <archivo.qml>   # al tocar el parser QML
```

En dispositivo (Redmi 23090RA98G por ADB inalámbrico, serial
`adb-J75T59BAZD45TG85-xfRrxx._adb-tls-connect._tcp`, conexión inestable):
`adb install`, `uiautomator dump` o `exec-out screencap -p` + análisis de
píxeles con Python/PIL.

## Convenciones

- Comentarios y textos de UI en **español**.
- Cambios mínimos; seguir el estilo existente.
- `lib/main.dart` es un esqueleto de `part` (los archivos en `lib/parts/*.dart`
  usan `part of 'package:ohm_launcher/main.dart';`); las piezas nuevas y
  autónomas van en archivos separados (`qml_bridge/`, `plugin_network.dart`…).
- Nunca renombrar referencias externas a Omarchy (marketplace, contrato,
  componentes QML de plugins); sí renombrar toda marca propia a "Ohm".

## Estado pendiente conocido

- Verificación en dispositivo del nuevo paquete `com.ohm.ohm_launcher`
  (instalación limpia; el paquete antiguo `com.omarchy.omarchy_launcher` sigue
  instalado en el teléfono).
- ADB inalámbrico inestable: a veces cae y el teléfono salta a Ajustes.
- El cajón de apps se cierra correctamente tocando el fondo oscurecido; el
  arrastre del *handle* para cerrar depende de que el gesto caiga sobre el
  área del handle/título (la cuadrícula de apps consume el scroll).
  Se puede mejorar con `NotificationListener` de overscroll para cerrar al
  hacer pull-down desde el grid.
