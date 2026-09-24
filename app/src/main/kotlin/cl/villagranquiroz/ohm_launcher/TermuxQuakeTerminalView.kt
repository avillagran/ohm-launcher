package cl.villagranquiroz.ohm_launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/** Quake panel backed by Termux's production ANSI/VT terminal emulator. */
class TermuxQuakeTerminalView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
) : LinearLayout(context, attributes) {
    private val terminal = TerminalView(context, null)
    private var session: TerminalSession? = null
    private var binDirectory = File(context.filesDir, "bin")
    private var homeDirectory = context.filesDir
    private var shellPath = "/system/bin/sh"
    private var linkerPath = if (File("/system/bin/linker64").isFile) "/system/bin/linker64" else "/system/bin/linker"
    private var ctrl = false
    private var alt = false
    private lateinit var ctrlButton: TextView
    private lateinit var altButton: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var toolbarScroll: HorizontalScrollView

    var onClose: (() -> Unit)? = null

    private val swipeDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onFling(first: MotionEvent?, second: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = first ?: return false
                val close = QuakeSwipePolicy.shouldClose(
                    second.x - start.x,
                    second.y - start.y,
                    velocityY,
                )
                if (close) onClose?.invoke()
                return close
            }
        },
    )

    init {
        orientation = VERTICAL
        configureTerminal()
        addView(terminal, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        buildHeader()
        submitTheme()
        isFocusableInTouchMode = true
    }

    fun submitTheme() {
        val background = OmarchyUiTheme.color("background", BACKGROUND)
        val surface = OmarchyUiTheme.color("dark_background", HEADER)
        val key = OmarchyUiTheme.color("lighter_background", KEY_BACKGROUND)
        val foreground = OmarchyUiTheme.color("foreground", FOREGROUND)
        val accent = OmarchyUiTheme.color("accent", ACCENT)
        setBackgroundColor(background)
        terminal.setBackgroundColor(background)
        toolbarScroll.setBackgroundColor(surface)
        for (index in 0 until toolbar.childCount) {
            (toolbar.getChildAt(index) as? TextView)?.apply {
                setTextColor(foreground)
                setBackgroundColor(key)
            }
        }
        ctrlButton.setTextColor(if (ctrl) background else foreground)
        altButton.setTextColor(if (alt) background else foreground)
        if (ctrl) ctrlButton.setBackgroundColor(accent)
        if (alt) altButton.setBackgroundColor(accent)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    fun configure(
        binDirectory: File? = this.binDirectory,
        homeDirectory: File = this.homeDirectory,
        shellPath: String = this.shellPath,
        linkerPath: String = this.linkerPath,
    ) {
        val nextBin = binDirectory ?: File(context.filesDir, "bin")
        val changed = this.binDirectory != nextBin || this.homeDirectory != homeDirectory ||
            this.shellPath != shellPath || this.linkerPath != linkerPath
        this.binDirectory = nextBin
        this.homeDirectory = homeDirectory
        this.shellPath = shellPath
        this.linkerPath = linkerPath
        if (changed && session != null) {
            release()
            startSession()
        }
    }

    fun startSession() {
        if (session?.isRunning == true) return
        homeDirectory.mkdirs()
        binDirectory.mkdirs()
        val rc = homeDirectory.resolve(".ohm_shellrc")
        rc.writeText(InteractiveShellBootstrap.render(binDirectory, homeDirectory, shellPath, linkerPath))
        val environment = arrayOf(
            "HOME=${homeDirectory.absolutePath}",
            "TMPDIR=${homeDirectory.absolutePath}",
            "TMUX_TMPDIR=${homeDirectory.absolutePath}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "TERMINFO=${homeDirectory.resolve(".terminfo").absolutePath}",
            "SHELL=$shellPath",
            "ENV=${rc.absolutePath}",
            "PATH=${binDirectory.absolutePath}:/system/bin:/system/xbin:/sbin:/vendor/bin:/odm/bin:/product/bin",
            "LD_LIBRARY_PATH=${binDirectory.absolutePath}",
            "HERDR_EXECUTABLE=${binDirectory.resolve("herdr").absolutePath}",
        )
        val created = TerminalSession(
            shellPath,
            homeDirectory.absolutePath,
            arrayOf(shellPath, "-i"),
            environment,
            4000,
            sessionClient,
        )
        session = created
        terminal.attachSession(created)
    }

    fun focusInputAndShowKeyboard() {
        terminal.post {
            terminal.requestFocus()
            val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(windowToken, 0)
        terminal.clearFocus()
        requestFocus()
    }

    fun release() {
        session?.finishIfRunning()
        session = null
        terminal.attachSession(null)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun configureTerminal() {
        val scaledDensity = resources.displayMetrics.density * resources.configuration.fontScale
        terminal.setTextSize((14f * scaledDensity).toInt())
        terminal.setTypeface(Typeface.MONOSPACE)
        terminal.setTerminalViewClient(createViewClient())
        terminal.isFocusableInTouchMode = true
    }

    private fun buildHeader() {
        toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        ctrlButton = keyButton("Ctrl") { ctrl = !ctrl; refreshModifiers(); focusInputAndShowKeyboard() }
        altButton = keyButton("Alt") { alt = !alt; refreshModifiers(); focusInputAndShowKeyboard() }
        toolbar.addView(ctrlButton)
        toolbar.addView(altButton)
        toolbar.addView(keyButton("Esc") { write("\u001b") })
        toolbar.addView(keyButton("Tab") { write("\t") })
        toolbar.addView(keyButton("↑") { write("\u001b[A") })
        toolbar.addView(keyButton("↓") { write("\u001b[B") })
        toolbar.addView(keyButton("←") { write("\u001b[D") })
        toolbar.addView(keyButton("→") { write("\u001b[C") })
        toolbar.addView(keyButton("Enter") { write("\r") })
        toolbar.addView(keyButton("×") { onClose?.invoke() })
        toolbarScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(toolbar)
        }
        addView(toolbarScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun write(value: String) {
        startSession()
        session?.write(value)
        focusInputAndShowKeyboard()
    }

    private fun refreshModifiers() {
        submitTheme()
    }

    private fun keyButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        contentDescription = label
        gravity = Gravity.CENTER
        setTextColor(FOREGROUND)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(6), dp(8), dp(6), dp(8))
        setBackgroundColor(KEY_BACKGROUND)
        isClickable = true
        isFocusable = false
        setOnClickListener { action() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(2) }
    }

    private val sessionClient by lazy { object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = terminal.onScreenUpdated()
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = terminal.onScreenUpdated()
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.let { session.write(it.toString()) }
        }
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = terminal.onScreenUpdated()
        override fun onTerminalCursorStateChange(state: Boolean) = terminal.onScreenUpdated()
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, error: Exception) { android.util.Log.e(tag, message, error) }
        override fun logStackTrace(tag: String, error: Exception) { android.util.Log.e(tag, "Terminal error", error) }
    } }

    private fun createViewClient(): TerminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1f
        override fun onSingleTapUp(event: MotionEvent) = focusInputAndShowKeyboard()
        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = terminal.hasFocus()
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = ctrl.also { ctrl = false; post(::refreshModifiers) }
        override fun readAltKey(): Boolean = alt.also { alt = false; post(::refreshModifiers) }
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            if (codePoint != '\n'.code) return false
            session.write("\r")
            return true
        }
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, error: Exception) { android.util.Log.e(tag, message, error) }
        override fun logStackTrace(tag: String, error: Exception) { android.util.Log.e(tag, "Terminal view error", error) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BACKGROUND = 0xFF05080B.toInt()
        private const val HEADER = 0xFF0C1218.toInt()
        private const val KEY_BACKGROUND = 0xFF18232D.toInt()
        private const val FOREGROUND = 0xFFE7F4F8.toInt()
        private const val ACCENT = 0xFF4FDDF8.toInt()
    }
}

internal object InteractiveShellBootstrap {
    fun render(binDir: File, homeDir: File, shellPath: String, linkerPath: String): String = buildString {
        append("export HOME=").append(quote(homeDir.absolutePath)).append('\n')
        append("export TMPDIR=").append(quote(homeDir.absolutePath)).append('\n')
        append("export TMUX_TMPDIR=").append(quote(homeDir.absolutePath)).append('\n')
        append("export TERMINFO=").append(quote(homeDir.resolve(".terminfo").absolutePath)).append('\n')
        append("export SHELL=").append(quote(shellPath)).append('\n')
        append("export PATH=").append(quote(binDir.absolutePath))
            .append(":/system/bin:/system/xbin:/sbin:/vendor/bin:/odm/bin:/product/bin\n")
        append("export LD_LIBRARY_PATH=").append(quote(binDir.absolutePath)).append('\n')
        append("export HERDR_EXECUTABLE=").append(quote(binDir.resolve("herdr").absolutePath)).append('\n')
        append("PS1='~ $ '\n")
        binDir.listFiles().orEmpty().asSequence()
            .filter { it.isFile && Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(it.name) }
            .sortedBy(File::getName)
            .forEach { binary ->
                val interpreter = if (binary.inputStream().use { it.read() == 0x7f && it.read() == 'E'.code }) linkerPath else shellPath
                append(binary.name).append("() { ").append(quote(interpreter)).append(' ')
                    .append(quote(binary.absolutePath))
                if (binary.name == "ssh") append(" -y")
                append(" \"${'$'}@\"; }\n")
            }
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
