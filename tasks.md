# Tareas — OhmLauncher

> Registro persistente por proyecto. Al completar un bloque coherente de tareas,
> muévelo a tasks-completed.md con nota de verificación. IDs: OHM-NNN.

## Pendientes
- Traducir comentarios de android/** (Kotlin, MainActivity.kt) a inglés.
- Extraer el resto de strings hardcodeadas en español de la UI a los .arb
  (i18n completo: solo las strings clave del menú radial/diálogo/SnackBar
  viajan por l10n hoy).
- Verificación visual en dispositivo de:
  - OHM-017: frames de pantalla en vivo por WS (requiere autorizar captura en
    el teléfono y que el plugin QML decodifique los JPEG).
  - OHM-020: que el plugin QML de Omarchy exponga el contrato y muestre su QR
    `omarchy://<ip-linux>:8753?id=<nombre>` para que el teléfono lo escanee.
- AGENTS.md: se mantuvo en español (protegido). Traducción EN en AGENTS.en.md.
