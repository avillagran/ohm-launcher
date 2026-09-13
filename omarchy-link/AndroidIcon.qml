// Android head glyph drawn with Canvas (no icon font dependency).
// Used as the `iconComponent` of the bar's BarIconButton so the Omarchy Link
// widget shows an Android icon instead of text.
import QtQuick

Item {
  id: root
  // Tint color (set by the button via `color`, defaults to white).
  property color color: "#ffffff"

  Canvas {
    id: canvas
    anchors.fill: parent
    onPaint: {
      const ctx = getContext("2d")
      ctx.clearRect(0, 0, width, height)
      ctx.fillStyle = root.color
      ctx.strokeStyle = root.color
      ctx.lineWidth = Math.max(1, width * 0.04)
      ctx.lineCap = "round"

      const w = width, h = height
      const cx = w / 2

      // Head (rounded dome)
      ctx.beginPath()
      ctx.arc(cx, h * 0.42, w * 0.30, Math.PI, 0)
      ctx.lineTo(w * 0.80, h * 0.42)
      ctx.lineTo(w * 0.80, h * 0.52)
      ctx.arc(cx, h * 0.52, w * 0.30, 0, Math.PI, false)
      ctx.lineTo(w * 0.20, h * 0.42)
      ctx.closePath()
      ctx.fill()

      // Antennae
      ctx.beginPath()
      ctx.moveTo(w * 0.32, h * 0.16)
      ctx.lineTo(w * 0.24, h * 0.05)
      ctx.moveTo(w * 0.68, h * 0.16)
      ctx.lineTo(w * 0.76, h * 0.05)
      ctx.stroke()

      // Eyes
      ctx.fillStyle = "#10161C"
      ctx.beginPath()
      ctx.arc(w * 0.40, h * 0.40, w * 0.045, 0, 2 * Math.PI)
      ctx.arc(w * 0.60, h * 0.40, w * 0.045, 0, 2 * Math.PI)
      ctx.fill()

      // Mouth (small rounded rect in the head cutout)
      ctx.fillStyle = "#10161C"
      ctx.beginPath()
      const mw = w * 0.22, mh = h * 0.07
      const mx = cx - mw / 2, my = h * 0.30
      const mr = mh / 2
      // Rounded rect via arcTo (compatible with Quickshell's Canvas).
      ctx.moveTo(mx + mr, my)
      ctx.lineTo(mx + mw - mr, my)
      ctx.arcTo(mx + mw, my, mx + mw, my + mh, mr)
      ctx.lineTo(mx + mw, my + mh - mr)
      ctx.arcTo(mx + mw, my + mh, mx + mw - mr, my + mh, mr)
      ctx.lineTo(mx + mr, my + mh)
      ctx.arcTo(mx, my + mh, mx, my + mh - mr, mr)
      ctx.lineTo(mx, my + mr)
      ctx.arcTo(mx, my, mx + mr, my, mr)
      ctx.closePath()
      ctx.fill()
    }
  }
}
