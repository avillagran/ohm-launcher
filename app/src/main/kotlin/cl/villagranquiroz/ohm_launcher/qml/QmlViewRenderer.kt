package cl.villagranquiroz.ohm_launcher.qml

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import cl.villagranquiroz.ohm_launcher.OmarchyThemeShapeState
import cl.villagranquiroz.ohm_launcher.R
import java.io.File
import kotlin.math.roundToInt

/** Converts the pure QML render model into an Android Views hierarchy. */
class QmlViewRenderer(
    private val context: Context,
    private val converter: QmlRenderModelConverter = QmlRenderModelConverter(),
) {
    private val iconTypeface by lazy {
        ResourcesCompat.getFont(context, R.font.symbols_nerd_font_mono_regular)
    }

    fun render(source: String, originDirectory: File? = null, bindings: Map<String, Any?> = emptyMap()): View =
        render(QmlParser(source).parse(), originDirectory, bindings)

    fun render(document: QmlDocument, originDirectory: File? = null, bindings: Map<String, Any?> = emptyMap()): View {
        if (document.error != null) return Space(context)
        val activeConverter = if (bindings.isEmpty()) {
            converter
        } else {
            QmlRenderModelConverter.withBindings(bindings)
        }
        val model = document.elements.firstNotNullOfOrNull(activeConverter::convert) ?: return Space(context)
        return render(model, originDirectory)
    }

    fun render(element: QmlElement, originDirectory: File? = null): View =
        converter.convert(element)?.let { render(it, originDirectory) } ?: Space(context)

    fun render(model: QmlRenderNode, originDirectory: File? = null): View =
        build(model, originDirectory).also { it.layoutParams = frameLayoutParams(model.layout) }

    private fun build(node: QmlRenderNode, originDirectory: File?): View {
        val view = when (node.type) {
            QmlVisualType.TEXT -> textView(node)
            QmlVisualType.IMAGE -> imageView(node, originDirectory)
            QmlVisualType.ROW -> linearLayout(node, LinearLayout.HORIZONTAL, originDirectory)
            QmlVisualType.COLUMN -> linearLayout(node, LinearLayout.VERTICAL, originDirectory)
            QmlVisualType.ITEM, QmlVisualType.RECTANGLE -> frameLayout(node, originDirectory)
        }
        view.visibility = if (node.layout.visible) View.VISIBLE else View.GONE
        view.alpha = node.style.opacity
        applyBackground(view, node.style)
        return view
    }

    private fun frameLayout(node: QmlRenderNode, originDirectory: File?): FrameLayout =
        FrameLayout(context).apply {
            node.children.forEach { child ->
                addView(build(child, originDirectory), frameLayoutParams(child.layout))
            }
        }

    private fun linearLayout(
        node: QmlRenderNode,
        direction: Int,
        originDirectory: File?,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = direction
        gravity = if (direction == LinearLayout.HORIZONTAL) Gravity.CENTER_VERTICAL else Gravity.START
        node.children.forEachIndexed { index, child ->
            val params = linearLayoutParams(child.layout)
            if (index > 0) {
                if (direction == LinearLayout.HORIZONTAL) params.leftMargin += dp(node.layout.spacing)
                else params.topMargin += dp(node.layout.spacing)
            }
            addView(build(child, originDirectory), params)
        }
    }

    private fun textView(node: QmlRenderNode): TextView = TextView(context).apply {
        text = node.text.orEmpty()
        node.style.foregroundColor?.let { setTextColor(it.argb) }
        textSize = node.style.fontSize
        letterSpacing = if (node.style.fontSize == 0f) 0f else node.style.letterSpacing / node.style.fontSize
        gravity = when (node.style.textAlignment) {
            QmlTextAlignment.START -> Gravity.START
            QmlTextAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
            QmlTextAlignment.END -> Gravity.END
        }
        val typefaceStyle = if (node.style.bold) Typeface.BOLD else Typeface.NORMAL
        typeface = if (QmlTextTypefacePolicy.requiresIconFont(text.toString())) {
            iconTypeface ?: Typeface.DEFAULT
        } else {
            node.style.fontFamily?.let { Typeface.create(it, typefaceStyle) }
                ?: Typeface.defaultFromStyle(typefaceStyle)
        }
    }

    private fun imageView(node: QmlRenderNode, originDirectory: File?): ImageView = ImageView(context).apply {
        scaleType = when (node.style.imageScale) {
            QmlImageScale.FIT -> ImageView.ScaleType.FIT_CENTER
            QmlImageScale.CROP -> ImageView.ScaleType.CENTER_CROP
            QmlImageScale.FILL -> ImageView.ScaleType.FIT_XY
            QmlImageScale.CENTER -> ImageView.ScaleType.CENTER
        }
        node.imageSource?.takeIf(String::isNotBlank)?.let { source ->
            val uri = Uri.parse(source)
            val resolved = if (uri.scheme == null && originDirectory != null) {
                Uri.fromFile(originDirectory.resolve(qmlUrlToPath(source)))
            } else {
                uri
            }
            runCatching { setImageURI(resolved) }
        }
    }

    private fun applyBackground(view: View, style: QmlStyle) {
        if (style.backgroundColor == null && style.borderColor == null && style.cornerRadius == 0f) return
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(style.backgroundColor?.argb ?: 0x00000000)
            cornerRadius = OmarchyThemeShapeState.surfaceRadiusPx(
                dpFloat(style.cornerRadius),
                context.resources.displayMetrics.density,
            )
            style.borderColor?.let { setStroke(dp(style.borderWidth).coerceAtLeast(1), it.argb) }
        }
    }

    private fun frameLayoutParams(layout: QmlLayout): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        size(layout.width, layout.fillParent),
        size(layout.height, layout.fillParent),
    ).apply {
        val margins = layout.margins
        setMargins(dp(margins.left), dp(margins.top), dp(margins.right), dp(margins.bottom))
        gravity = horizontalGravity(layout.horizontalAlignment) or verticalGravity(layout.verticalAlignment)
    }

    private fun linearLayoutParams(layout: QmlLayout): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        size(layout.width, layout.fillParent),
        size(layout.height, layout.fillParent),
    ).apply {
        val margins = layout.margins
        setMargins(dp(margins.left), dp(margins.top), dp(margins.right), dp(margins.bottom))
        gravity = horizontalGravity(layout.horizontalAlignment) or verticalGravity(layout.verticalAlignment)
    }

    private fun size(value: Float?, fillParent: Boolean): Int = when {
        fillParent -> ViewGroup.LayoutParams.MATCH_PARENT
        value == null -> ViewGroup.LayoutParams.WRAP_CONTENT
        else -> dp(value.coerceAtLeast(0f))
    }

    private fun horizontalGravity(alignment: QmlHorizontalAlignment): Int = when (alignment) {
        QmlHorizontalAlignment.START -> Gravity.START
        QmlHorizontalAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
        QmlHorizontalAlignment.END -> Gravity.END
    }

    private fun verticalGravity(alignment: QmlVerticalAlignment): Int = when (alignment) {
        QmlVerticalAlignment.TOP -> Gravity.TOP
        QmlVerticalAlignment.CENTER -> Gravity.CENTER_VERTICAL
        QmlVerticalAlignment.BOTTOM -> Gravity.BOTTOM
    }

    private fun dp(value: Float): Int = dpFloat(value).roundToInt()

    private fun dpFloat(value: Float): Float = value * context.resources.displayMetrics.density
}
