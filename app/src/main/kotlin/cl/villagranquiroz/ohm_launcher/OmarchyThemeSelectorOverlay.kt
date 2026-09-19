package cl.villagranquiroz.ohm_launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Native port of Omarchy's ImagePicker.qml geometry and interaction model. */
internal class OmarchyThemeSelectorOverlay(
    context: Context,
    private val catalog: OmarchyThemeCatalog,
    private val colors: Colors,
    private val previewLoader: (OmarchyThemeChoice) -> ByteArray?,
    private val onApply: (OmarchyThemeChoice) -> Unit,
    private val onDismissed: () -> Unit,
) : FrameLayout(context) {
    data class Colors(
        val foreground: Int,
        val background: Int,
        val scrim: Int,
        val selectedBorder: Int,
        val unselectedBorder: Int,
    )

    private var dismissed = false
    private val picker = PickerView(context)

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { close() }
        addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        post { picker.requestFocus() }
    }

    fun close() {
        if (dismissed) return
        dismissed = true
        picker.release()
        (parent as? FrameLayout)?.removeView(this)
        onDismissed()
    }

    private inner class PickerView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val path = Path()
        private val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "omarchy-theme-preview").apply { priority = Thread.MIN_PRIORITY }
        }
        private val main = Handler(Looper.getMainLooper())
        private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
        private val pending = mutableSetOf<String>()
        private var selectedIndex = catalog.themes.indexOfFirst { it.id == catalog.current }.coerceAtLeast(0)
        private var downX = 0f
        private var downY = 0f
        private var dragOffsetX = 0f
        private var dragging = false
        private var velocityTracker: VelocityTracker? = null
        private var settleAnimator: ValueAnimator? = null
        private var filterText = ""

        init {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = catalog.themes.getOrNull(selectedIndex)?.label
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(colors.scrim)
            val matching = matchingIndexes()
            if (matching.isEmpty()) {
                drawText(canvas, context.getString(R.string.theme_selector_empty), height / 2f, 24f, colors.foreground)
                return
            }
            if (selectedIndex !in matching) selectedIndex = matching.first()

            val selectedWidth = minOf(width * 0.72f, width - dp(40f))
            val selectedHeight = selectedWidth * 475f / 768f
            val sliceWidth = selectedWidth * 108f / 768f
            val sliceHeight = selectedHeight * 432f / 475f
            val step = selectedWidth * 78f / 768f
            val skew = selectedWidth * 28f / 768f
            val top = (height - selectedHeight) / 2f - dp(24f)
            val selectedLeft = (width - selectedWidth) / 2f + dragOffsetX
            val selectedPosition = matching.indexOf(selectedIndex)

            for (position in matching.indices) {
                val relative = position - selectedPosition
                if (abs(relative) > 16 || relative == 0) continue
                val left = if (relative < 0) {
                    selectedLeft + relative * step
                } else {
                    selectedLeft + selectedWidth - selectedWidth * 30f / 768f + (relative - 1) * step
                }
                val itemTop = top + (selectedHeight - sliceHeight) / 2f
                drawPreview(canvas, matching[position], RectF(left, itemTop, left + sliceWidth, itemTop + sliceHeight), skew, false)
            }
            drawPreview(canvas, selectedIndex, RectF(selectedLeft, top, selectedLeft + selectedWidth, top + selectedHeight), skew, true)

            val choice = catalog.themes[selectedIndex]
            contentDescription = choice.label
            drawText(canvas, choice.label, top + selectedHeight + dp(48f), 28f, colors.foreground)
            if (filterText.isNotEmpty()) {
                drawText(canvas, filterText, top + selectedHeight + dp(82f), 20f, alpha(colors.foreground, 0.85f))
            }
            preloadNearby(matching, selectedPosition)
        }

        private fun drawPreview(canvas: Canvas, index: Int, bounds: RectF, skew: Float, selected: Boolean) {
            val effectiveSkew = minOf(skew, bounds.width() * 0.35f)
            path.reset()
            path.moveTo(bounds.left + effectiveSkew, bounds.top)
            path.lineTo(bounds.right, bounds.top)
            path.lineTo(bounds.right - effectiveSkew, bounds.bottom)
            path.lineTo(bounds.left, bounds.bottom)
            path.close()

            canvas.save()
            canvas.clipPath(path)
            canvas.drawColor(colors.background)
            cache.get(catalog.themes[index].id)?.let { bitmap -> drawCenterCrop(canvas, bitmap, bounds) }
            if (!selected) {
                shadePaint.color = alpha(colors.background, 0.42f)
                canvas.drawPath(path, shadePaint)
            }
            canvas.restore()

            borderPaint.color = if (selected) colors.selectedBorder else colors.unselectedBorder
            borderPaint.strokeWidth = dp(if (selected) 3f else 1f)
            canvas.drawPath(path, borderPaint)
        }

        private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, bounds: RectF) {
            val scale = max(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
            val sourceWidth = bounds.width() / scale
            val sourceHeight = bounds.height() / scale
            val source = Rect(
                ((bitmap.width - sourceWidth) / 2f).roundToInt(),
                ((bitmap.height - sourceHeight) / 2f).roundToInt(),
                ((bitmap.width + sourceWidth) / 2f).roundToInt(),
                ((bitmap.height + sourceHeight) / 2f).roundToInt(),
            )
            canvas.drawBitmap(bitmap, source, bounds, imagePaint)
        }

        private fun drawText(canvas: Canvas, text: String, baseline: Float, sizeSp: Float, color: Int) {
            textPaint.textSize = sizeSp * density * resources.configuration.fontScale
            textPaint.color = color
            textPaint.style = Paint.Style.FILL
            textPaint.setShadowLayer(dp(3f), 0f, dp(1f), alpha(colors.background, 0.7f))
            canvas.drawText(text, width / 2f, baseline, textPaint)
            textPaint.clearShadowLayer()
        }

        private fun preloadNearby(indexes: List<Int>, selectedPosition: Int) {
            for (position in (selectedPosition - 4).coerceAtLeast(0)..(selectedPosition + 4).coerceAtMost(indexes.lastIndex)) {
                loadPreview(indexes[position])
            }
        }

        private fun loadPreview(index: Int) {
            val choice = catalog.themes[index]
            if (cache.get(choice.id) != null || !pending.add(choice.id)) return
            executor.execute {
                val bitmap = previewLoader(choice)?.let(::decodeSampled)
                main.post {
                    pending.remove(choice.id)
                    if (bitmap != null) cache.put(choice.id, bitmap)
                    invalidate()
                }
            }
        }

        private fun decodeSampled(bytes: ByteArray): Bitmap? {
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PREVIEW_PIXELS) return null
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1200) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    settleAnimator?.cancel()
                    downX = event.x
                    downY = event.y
                    dragOffsetX = 0f
                    dragging = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    var dx = event.x - downX
                    if (abs(dx) > dp(6f)) dragging = true
                    if (dragging) {
                        val step = width * 0.18f
                        val crossedItems = (abs(dx) / step).toInt()
                        if (crossedItems > 0) {
                            val movingLeft = dx < 0f
                            selectAdjacent(if (movingLeft) 1 else -1, crossedItems)
                            downX += (if (movingLeft) -1f else 1f) * step * crossedItems
                            dx = event.x - downX
                            dragOffsetX = if (movingLeft) step else -step
                        }
                        val target = dx.coerceIn(-width * 0.42f, width * 0.42f)
                        dragOffsetX += (target - dragOffsetX) * DRAG_SMOOTHING
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val selectedWidth = minOf(width * 0.72f, width - dp(40f))
                    val selectedHeight = selectedWidth * 475f / 768f
                    val top = (height - selectedHeight) / 2f - dp(24f)
                    if (event.y < top - dp(20f) || event.y > top + selectedHeight + dp(100f)) {
                        close()
                    } else if (dragging) {
                        settleDrag(dx, velocityX)
                    } else {
                        dragOffsetX = 0f
                        val left = (width - selectedWidth) / 2f
                        if (event.x in left..(left + selectedWidth) && event.y <= top + selectedHeight) {
                            catalog.themes.getOrNull(selectedIndex)?.let(onApply)
                        } else {
                            selectAdjacent(if (event.x < width / 2f) -1 else 1)
                        }
                    }
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    animateDragOffset(0f, 150L)
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick().also { }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> true.also { selectAdjacent(-1) }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB -> true.also { selectAdjacent(1) }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> true.also {
                catalog.themes.getOrNull(selectedIndex)?.let(onApply)
            }
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> true.also {
                if (filterText.isNotEmpty()) {
                    filterText = ""
                    invalidate()
                } else close()
            }
            else -> {
                val character = event.unicodeChar.takeIf { it >= 32 }?.toChar()
                if (character != null) {
                    filterText += character
                    val first = matchingIndexes().firstOrNull()
                    if (first != null) selectedIndex = first
                    invalidate()
                    true
                } else super.onKeyDown(keyCode, event)
            }
        }

        private fun settleDrag(distance: Float, velocity: Float) {
            val projected = distance + velocity * 0.12f
            val threshold = dp(32f)
            if (abs(projected) < threshold) {
                animateDragOffset(0f, 150L)
                return
            }
            val direction = if (projected < 0f) 1 else -1
            val flingSteps = if (abs(velocity) >= 900f) {
                (1 + abs(velocity) / 1_100f).roundToInt().coerceIn(2, 7)
            } else {
                (abs(projected) / (width * 0.32f)).roundToInt().coerceIn(1, 2)
            }
            animateFlingSteps(direction, flingSteps, flingSteps)
        }

        private fun animateFlingSteps(direction: Int, remaining: Int, total: Int) {
            if (remaining <= 0) return
            val target = if (direction > 0) -width * 0.18f else width * 0.18f
            val completed = total - remaining
            val duration = 90L + completed * 42L
            animateDragOffset(target, duration) {
                selectAdjacent(direction)
                dragOffsetX = 0f
                invalidate()
                animateFlingSteps(direction, remaining - 1, total)
            }
        }

        private fun animateDragOffset(target: Float, duration: Long, finished: (() -> Unit)? = null) {
            settleAnimator?.cancel()
            settleAnimator = ValueAnimator.ofFloat(dragOffsetX, target).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    dragOffsetX = it.animatedValue as Float
                    invalidate()
                }
                if (finished != null) doOnEnd(finished)
                start()
            }
        }

        private fun ValueAnimator.doOnEnd(action: () -> Unit) {
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!canceled) action()
                }
            })
        }

        private fun selectAdjacent(direction: Int, count: Int = 1) {
            val indexes = matchingIndexes()
            if (indexes.isEmpty()) return
            val position = indexes.indexOf(selectedIndex).coerceAtLeast(0)
            selectedIndex = indexes[(position + direction * count).mod(indexes.size)]
            invalidate()
        }

        private fun matchingIndexes(): List<Int> {
            val needle = filterText.trim().lowercase(Locale.ROOT)
            return catalog.themes.indices.filter { index ->
                needle.isEmpty() || catalog.themes[index].id.lowercase(Locale.ROOT).contains(needle) ||
                    catalog.themes[index].label.lowercase(Locale.ROOT).contains(needle)
            }
        }

        fun release() {
            settleAnimator?.cancel()
            velocityTracker?.recycle()
            executor.shutdownNow()
            main.removeCallbacksAndMessages(null)
            cache.evictAll()
        }

        private fun dp(value: Float): Float = value * density
    }

    private fun alpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val DRAG_SMOOTHING = 0.42f
        const val MAX_PREVIEW_PIXELS = 24_000_000L
    }
}
