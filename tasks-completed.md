# Tareas completadas — OhmLauncher

- [OHM-001] Gestos swipe up/down/left/right funcionando — verificado en dispositivo
  (YieldingVerticalDragRecognizer: |dx|>|dy|+20px cede al PageView horizontal)
- [OHM-002] Barras respetan insets del sistema (SafeArea bottom; reloj no invade
  los bordes) — verificado en dispositivo
- [OHM-003] Radio de borde configurable (boxRadius/barRadius) en ajustes Visual
  — verificado (analyze + build)
- [OHM-004] Bridge QML interpretado en caliente — confirmado; corregido comentario
  desactualizado en main.dart
- [OHM-005] Widget de clima de Santiago como plugin QML (examples/plugins +
  copiado a /sdcard/OhmLauncher/plugins en el teléfono) — verificado (test QML 6/6,
  plugin discovery test, screenshot en dispositivo)
- [OHM-006] Toggle expandir/contraer barras conectado (onToggle -> _toggleFavBarVisible
  / _toggleBottomBarVisible) — verificado (analyze + build)
- [OHM-007] Barra de plugins no desaparece en top/bottom (ConstrainedBox ancho
  en orientación horizontal) — verificado en dispositivo (screenshot ohm_top.png)
- [OHM-008] Lista de plugins: check toggleable (activar/desactivar) + botón eliminar
  con confirmación (AlertDialog) — implementado; storage.disable/enable/deletePlugin
- [OHM-009] Botón "Agregar widget" en vista previa del plugin (PluginSnapshot.latest
  sincronizado en _rescanPlugins) — verificado en dispositivo (clima agregado al
  escritorio, sin error)
- [OHM-010] Fondo del desktop continúa en notch y nav bar (quitar SafeArea del
  _desktop; SystemUiMode.edgeToEdge ya activo) — verificado en dispositivo
  (screenshot ohm_bg.png)
- [OHM-011] Teclado del terminal Quake: botones Ctrl / Alt / Esc / Tab / 4 flechas
  (fila inferior del terminal, cerca del teclado suave) — verificado en dispositivo
  (screenshot ohm_quake3.png: terminal arriba, botones abajo)
- [OHM-012] Terminal Quake no pierde la sesión al ocultarse (no matar PTY al
  ocultar; solo ocultar el panel, State/PTY persisten) — verificado por el
  usuario ("genial, no se muere la session al ocultar")

- [OHM-014] Integración OhmLauncher <-> Omarchy: contrato HTTP + WebSocket
  - VERIFICADO en LAN (discover/clipboard/file/WS responden en 192.168.1.141:8753;
    server escucha en anyIPv4 con lanMode)
- [OHM-015] Autodetección de peer Omarchy: mDNS (_ohm._tcp vía nsd) + QR fallback
  - VERIFICADO: query mDNS _ohm._tcp.local recibe respuesta del teléfono con el
    servicio OhmLauncher; OmarchyQrDialog + botón "Conectar Omarchy" en menú radial
- [OHM-016] Acciones de integración: clipboard + archivos + themes + fotos
  - VERIFICADO en LAN: PUT/GET clipboard, POST/GET file, GET/PUT theme responden;
    POST /omarchy/photos/backup lista 18.473 fotos en DCIM (el peer las descarga
    vía GET /omarchy/file). Respaldo de fotos real.
- [OHM-017] Compartir pantalla (scrcpy-like) hacia Omarchy
  - IMPLEMENTADO: endpoint screen/start responde "started"; captura MediaProjection
    nativa (MainActivity.kt) envía frames JPEG por WS al peer. Pendiente verificación
    de frames en vivo (autorizar captura en teléfono + decode en plugin QML).
- [OHM-019] Autodetección por Bluetooth (3er método, con mDNS + QR)
  - IMPLEMENTADO: OmarchyBtScanner (flutter_blue_plus) + botón "Escanear Bluetooth"
    en el diálogo "Conectar Omarchy"; permisos BT/BLE en AndroidManifest. Build e
    install verificados; server sigue vivo tras rebuild.
- [OHM-013] Terminal Quake: handles de selección nativos de Android (inicio/fin
  arrastrables) sobre la selección de xterm
  - IMPLEMENTADO: usa la geometría pública de RenderTerminal (getOffset /
    getCellOffset / selectCharacters) vía GlobalKey para ubicar y arrastrar los
    dos asideros pixel-perfect SIN fork del paquete xterm. Compila e instala;
    falta confirmación visual del arrastre en el dispositivo.
- [OHM-020] Protocolo `omarchy://` para enlace vía QR escaneado con la cámara
  del sistema (sin lector QR embebido en el launcher)
  - IMPLEMENTADO y VERIFICADO en dispositivo: intent-filter `omarchy://` en
    AndroidManifest + captura en MainActivity.kt (onCreate/onNewIntent) ->
    MethodChannel onOmarchyPeerLink -> parser en platform.dart ->
    SnackBar "Conectado a Omarchy: MiPC (192.168.1.50:8753)" visible en screenshot.
  - El plugin QML de Omarchy debe mostrar su QR `omarchy://<ip-linux>:8753?id=<nombre>`.
- [OHM-021] Plugin QML de Omarchy (referencia) en inglés: muestra QR `omarchy://`
  + expone contrato cliente->teléfono + README con flujo y helper C++ del QR.
  - Creado/actualizado en examples/plugins/io.github.ohm.omarchy-link/
    (manifest/Panel.qml/BarWidget.qml/README.md en inglés). Pendiente instalar
    en Omarchy real (lo hace el usuario en su entorno Linux).
- [OHM-022] UI multiidioma (i18n es/EN)
  - IMPLEMENTADO scaffold: pubspec (intl + flutter_localizations + generate),
    lib/l10n/app_en.arb + app_es.arb, MaterialApp delegates/supportedLocales,
    resolución por locale del sistema con fallback a ES. Strings clave (menú
    radial, diálogo Omarchy, SnackBar peer) ya viajan por l10n. Build verde.
  - Pendiente: extraer el resto de strings hardcodeadas en español a los .arb.
- [OHM-023] Documentar el proyecto en inglés (código + comentarios + docs)
  - Comentarios de lib/** (Dart) traducidos a inglés (subagente + cierre manual;
    verificado con grep de palabras ES y flutter analyze 0 errores; build verde).
  - README.md reescrito en inglés. AGENTS.md se mantuvo en español (protegido
    por el sistema); traducción EN disponible en AGENTS.en.md.
  - Pendiente: comentarios de android/** (Kotlin) a inglés. -> HECHO (traducidos).
  - Commit local en master (6ee19d1); pendiente push por autorización.
