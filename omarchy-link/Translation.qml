// Minimal i18n helper for the Omarchy Link plugin (no external infra needed).
// Selects a language from the system locale (es / en, fallback en) and exposes
// t(key) to resolve strings. Add keys to `strings` below; keep en as the
// canonical fallback.
import QtQuick

QtObject {
  id: root

  // es / en string tables. Keys are language-neutral.
  readonly property var strings: ({
    en: {
      title: "OhmLauncher Link",
      connect: "Connect",
      useGoogleLens: "Use Google Lens",
      notConnected: "Not connected",
      connected: "Connected: %1 (%2)",
      copyFromPhone: "Copy from phone",
      copyToPhone: "Copy to phone",
      pushFile: "Send file to phone",
      pullFile: "Get file from phone",
      applyTheme: "Apply Omarchy theme",
      backupPhotos: "Back up photos",
      startScreen: "Share screen",
      stopScreen: "Stop sharing",
      discoverHint: "Connect from the phone by scanning the QR, or push from OhmLauncher.",
      logHeader: "Link log"
    },
    es: {
      title: "Enlace OhmLauncher",
      useGoogleLens: "Usa Google Lens",
      notConnected: "No conectado",
      connected: "Conectado: %1 (%2)",
      copyFromPhone: "Copiar del teléfono",
      copyToPhone: "Copiar al teléfono",
      pushFile: "Enviar archivo al teléfono",
      pullFile: "Obtener archivo del teléfono",
      applyTheme: "Aplicar tema de Omarchy",
      backupPhotos: "Respaldar fotos",
      startScreen: "Compartir pantalla",
      stopScreen: "Detener compartir",
      discoverHint: "Conecta desde el teléfono escaneando el QR, o empuja desde OhmLauncher.",
      logHeader: "Registro del enlace"
    }
  })

  // Resolve the active language code from the LANG env (es / en, fallback en).
  readonly property string lang: {
    var raw = Quickshell.env("LANG") || ""
    raw = raw.toLowerCase()
    if (raw.indexOf("es") === 0) return "es"
    return "en"
  }

  // t(key, args...) -> localized string with %1/%2... substitution.
  function t(key, a, b, c) {
    var table = root.strings[root.lang] || root.strings.en
    var s = (table[key] !== undefined) ? table[key] : (root.strings.en[key] || key)
    if (a !== undefined) s = s.replace("%1", a)
    if (b !== undefined) s = s.replace("%2", b)
    if (c !== undefined) s = s.replace("%3", c)
    return s
  }
}
