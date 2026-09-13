package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

internal enum class TerminalControl {
    ESCAPE,
    TAB,
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

internal object TerminalControlSequences {
    fun forKey(key: TerminalControl): String = when (key) {
        TerminalControl.ESCAPE -> "\u001b"
        TerminalControl.TAB -> "\t"
        TerminalControl.UP -> "\u001b[A"
        TerminalControl.DOWN -> "\u001b[B"
        TerminalControl.LEFT -> "\u001b[D"
        TerminalControl.RIGHT -> "\u001b[C"
    }
}

internal object TerminalCommandProtocol {
    const val RECORD_SEPARATOR: Char = '\u001e'
    const val UNIT_SEPARATOR: Char = '\u001f'

    fun frame(command: String, id: Long): String = buildString {
        append(command)
        append('\n')
        append("__ohm_status=${'$'}?\n")
        append("printf '\\036OHM_DONE:")
        append(id)
        append(":%s\\037\\n' \"${'$'}__ohm_status\"\n")
    }
}

internal data class TerminalCommandCompletion(val id: Long, val exitCode: Int)
internal data class TerminalDecodedChunk(
    val text: String,
    val completions: List<TerminalCommandCompletion>,
)

internal class TerminalStreamDecoder {
    private var pending = ""
    private val marker = Regex("^OHM_DONE:(\\d+):(-?\\d+)$")

    fun accept(chunk: String): TerminalDecodedChunk {
        pending += chunk
        val text = StringBuilder()
        val completions = ArrayList<TerminalCommandCompletion>()
        while (true) {
            val start = pending.indexOf(TerminalCommandProtocol.RECORD_SEPARATOR)
            if (start < 0) {
                text.append(pending)
                pending = ""
                break
            }
            text.append(pending.substring(0, start))
            val end = pending.indexOf(TerminalCommandProtocol.UNIT_SEPARATOR, start + 1)
            if (end < 0) {
                pending = pending.substring(start)
                break
            }
            val raw = pending.substring(start + 1, end)
            val match = marker.matchEntire(raw)
            if (match == null) {
                text.append(pending.substring(start, end + 1))
            } else {
                completions += TerminalCommandCompletion(
                    match.groupValues[1].toLong(),
                    match.groupValues[2].toInt(),
                )
            }
            pending = pending.substring(end + 1)
        }
        return TerminalDecodedChunk(text.toString(), completions)
    }
}

internal object NoExecShellBootstrap {
    private val functionName = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun render(binDir: File?, homeDir: File, shellPath: String, linkerPath: String): String = buildString {
        append("export HOME=").append(quote(homeDir.absolutePath)).append('\n')
        append("export TMPDIR=").append(quote(homeDir.absolutePath)).append('\n')
        append("export TERMINFO=").append(quote(File(homeDir, ".terminfo").absolutePath)).append('\n')
        append("export TMUX_TMPDIR=").append(quote(homeDir.absolutePath)).append('\n')
        append("export SHELL=").append(quote(shellPath)).append('\n')
        if (binDir != null) {
            append("export PATH=").append(quote(binDir.absolutePath))
                .append(":/system/bin:/system/xbin:/sbin:/vendor/bin:/odm/bin:/product/bin\n")
            append("export LD_LIBRARY_PATH=").append(quote(binDir.absolutePath)).append('\n')
            binDir.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isFile && functionName.matches(it.name) }
                .sortedBy { it.name }
                .forEach { binary ->
                    val interpreter = if (binary.isElf()) linkerPath else shellPath
                    append(binary.name).append("() { ")
                        .append(quote(interpreter)).append(' ')
                        .append(quote(binary.absolutePath)).append(" \"${'$'}@\"; }\n")
                }
        } else {
            append("export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/odm/bin:/product/bin\n")
        }
        append("printf '\\036OHM_BOOTSTRAP_DONE\\037\\n'\n")
    }

    private fun File.isElf(): Boolean = runCatching {
        inputStream().use { input ->
            input.read() == 0x7f && input.read() == 'E'.code &&
                input.read() == 'L'.code && input.read() == 'F'.code
        }
    }.getOrDefault(false)

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}

internal object TerminalStreamReader {
    fun consume(reader: Reader, onChunk: (String) -> Unit) {
        val buffer = CharArray(2048)
        while (true) {
            val count = try {
                reader.read(buffer)
            } catch (_: IOException) {
                break
            }
            if (count < 0) break
            onChunk(String(buffer, 0, count))
        }
    }
}

internal object TerminalBackendPolicy {
    fun useNativePty(runtimeName: String?): Boolean = runtimeName?.contains("Android", ignoreCase = true) == true
}

internal class TerminalBootstrapFilter {
    private var ready = false
    private var pending = ""

    fun accept(chunk: String): String {
        if (ready) return chunk
        pending += chunk
        val marker = "${TerminalCommandProtocol.RECORD_SEPARATOR}OHM_BOOTSTRAP_DONE${TerminalCommandProtocol.UNIT_SEPARATOR}"
        val index = pending.indexOf(marker)
        if (index < 0) {
            if (pending.length > marker.length) pending = pending.takeLast(marker.length)
            return ""
        }
        ready = true
        return pending.substring(index + marker.length).also { pending = "" }
    }
}

internal object QuakeSwipePolicy {
    fun shouldClose(deltaX: Float, deltaY: Float, velocityY: Float): Boolean =
        deltaY < -120f && velocityY < -500f && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)
}

internal class PersistentShellSession(
    private val shellPath: String,
    private val linkerPath: String,
    private val workingDirectory: File,
    private val homeDir: File,
    private val binDir: File?,
    private val onOutput: (String) -> Unit,
    private val onCommandFinished: (Long, Int) -> Unit = { _, _ -> },
    private val onExit: (Int) -> Unit = {},
) : AutoCloseable {
    private val nextCommandId = AtomicLong()
    private val decoder = TerminalStreamDecoder()
    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var pty: NativePtySession? = null
    private var writer: BufferedWriter? = null

    val isAlive: Boolean
        get() = pty != null || process?.isAlive == true

    fun start() {
        synchronized(lock) {
            if (isAlive) return
            workingDirectory.mkdirs()
            homeDir.mkdirs()
            if (TerminalBackendPolicy.useNativePty(System.getProperty("java.runtime.name"))) {
                startNativePty()
                return
            }
            val child = ProcessBuilder(shellPath)
                .directory(workingDirectory)
                .apply {
                    environment().apply {
                        clear()
                        put("HOME", homeDir.absolutePath)
                        put("TMPDIR", homeDir.absolutePath)
                        put("SHELL", shellPath)
                        put("TERM", "xterm-256color")
                        put(
                            "PATH",
                            listOfNotNull(binDir?.absolutePath, "/system/bin", "/system/xbin", "/sbin", "/vendor/bin", "/odm/bin", "/product/bin")
                                .joinToString(":"),
                        )
                        binDir?.let { put("LD_LIBRARY_PATH", it.absolutePath) }
                    }
                }
                .start()
            process = child
            writer = BufferedWriter(OutputStreamWriter(child.outputStream, StandardCharsets.UTF_8)).also {
                it.write(NoExecShellBootstrap.render(binDir, homeDir, shellPath, linkerPath))
                it.flush()
            }
            readStdout(child)
            readStderr(child)
            Thread({
                val exitCode = runCatching { child.waitFor() }.getOrDefault(-1)
                if (process === child) {
                    process = null
                    writer = null
                    onExit(exitCode)
                }
            }, "ohm-terminal-waiter").apply { isDaemon = true; start() }
        }
    }

    fun execute(command: String): Long {
        val id = nextCommandId.incrementAndGet()
        write(TerminalCommandProtocol.frame(command, id))
        return id
    }

    fun write(sequence: String) {
        synchronized(lock) {
            pty?.let {
                it.write(sequence)
                return
            }
            check(process?.isAlive == true) { "Shell session is not running" }
            writer!!.apply {
                write(sequence)
                flush()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { writer?.close() }
            writer = null
            val native = pty
            pty = null
            runCatching { native?.close() }
            process?.destroy()
            process = null
        }
    }

    private fun startNativePty() {
        val bootstrapFilter = TerminalBootstrapFilter()
        val native = NativePtySession.open(
            shellPath = shellPath,
            workingDirectory = workingDirectory,
            environment = mapOf(
                "HOME" to homeDir.absolutePath,
                "TMPDIR" to homeDir.absolutePath,
                "SHELL" to shellPath,
                "TERM" to "xterm-256color",
                "PATH" to listOfNotNull(
                    binDir?.absolutePath,
                    "/system/bin",
                    "/system/xbin",
                    "/sbin",
                    "/vendor/bin",
                    "/odm/bin",
                    "/product/bin",
                ).joinToString(":"),
            ) + (binDir?.let { mapOf("LD_LIBRARY_PATH" to it.absolutePath) } ?: emptyMap()),
            rows = 24,
            columns = 80,
        )
        pty = native
        native.startReading(
            onChunk = { chunk ->
                val visible = bootstrapFilter.accept(chunk)
                if (visible.isEmpty()) return@startReading
                val decoded = decoder.accept(visible)
                if (decoded.text.isNotEmpty()) onOutput(decoded.text)
                decoded.completions.forEach { onCommandFinished(it.id, it.exitCode) }
            },
            onClosed = {
                synchronized(lock) {
                    if (pty === native) {
                        pty = null
                        onExit(-1)
                    }
                }
            },
        )
        native.write(NoExecShellBootstrap.render(binDir, homeDir, shellPath, linkerPath))
    }

    private fun readStdout(child: Process) {
        Thread({
            child.inputStream.reader(StandardCharsets.UTF_8).use { reader ->
                TerminalStreamReader.consume(reader) { chunk ->
                    val decoded = decoder.accept(chunk)
                    if (decoded.text.isNotEmpty()) onOutput(decoded.text)
                    decoded.completions.forEach { onCommandFinished(it.id, it.exitCode) }
                }
            }
        }, "ohm-terminal-stdout").apply { isDaemon = true; start() }
    }

    private fun readStderr(child: Process) {
        Thread({
            child.errorStream.reader(StandardCharsets.UTF_8).use { reader ->
                TerminalStreamReader.consume(reader, onOutput)
            }
        }, "ohm-terminal-stderr").apply { isDaemon = true; start() }
    }
}

internal class TerminalModifierState {
    var ctrlActive: Boolean = false
        private set
    var altActive: Boolean = false
        private set

    fun toggleCtrl() {
        ctrlActive = !ctrlActive
    }

    fun toggleAlt() {
        altActive = !altActive
    }

    fun consume(character: Char): String? {
        if (!ctrlActive && !altActive) return null
        if (character.code !in 0x20 until 0x7f) return null
        val encoded = if (ctrlActive) {
            (character.code and 0x1f).toChar().toString()
        } else {
            "\u001b${character.lowercaseChar()}"
        }
        ctrlActive = false
        altActive = false
        return encoded
    }
}

internal class TerminalCommandHistory(private val capacity: Int = 100) {
    private val commands = ArrayList<String>()
    private var cursor = 0
    private var draft = ""

    fun record(command: String) {
        if (command.isBlank()) return
        if (commands.lastOrNull() != command) commands += command
        while (commands.size > capacity.coerceAtLeast(0)) commands.removeAt(0)
        cursor = commands.size
        draft = ""
    }

    fun previous(current: String): String {
        if (commands.isEmpty()) return current
        if (cursor == commands.size) draft = current
        cursor = (cursor - 1).coerceAtLeast(0)
        return commands[cursor]
    }

    fun next(): String {
        if (commands.isEmpty()) return draft
        cursor = (cursor + 1).coerceAtMost(commands.size)
        return if (cursor == commands.size) draft else commands[cursor]
    }
}

/**
 * Reusable, dependency-free terminal panel backed by one long-lived system shell.
 *
 * The shell is pipe-backed rather than PTY-backed, so full-screen TUI programs cannot negotiate
 * terminal size. Ordinary commands, `cd`, exports, aliases, and shell functions persist until the
 * view is detached or [release] is called.
 */
class QuakeTerminalView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
) : LinearLayout(context, attributes) {
    private val history = TerminalCommandHistory()
    private val modifiers = TerminalModifierState()
    private val output = TextView(context)
    private val outputScroll = ScrollView(context)
    private val input = EditText(context)
    private lateinit var ctrlButton: TextView
    private lateinit var altButton: TextView
    private var session: PersistentShellSession? = null
    private var binDirectory: File? = File(context.filesDir, "bin")
    private var homeDirectory: File = context.filesDir
    private var shellPath = "/system/bin/sh"
    private var linkerPath = if (File("/system/bin/linker64").isFile) {
        "/system/bin/linker64"
    } else {
        "/system/bin/linker"
    }

    /** Called when the close affordance is pressed. The live session is intentionally retained. */
    var onClose: (() -> Unit)? = null
    private val swipeDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onFling(
                first: MotionEvent?,
                second: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val start = first ?: return false
                val close = QuakeSwipePolicy.shouldClose(
                    deltaX = second.x - start.x,
                    deltaY = second.y - start.y,
                    velocityY = velocityY,
                )
                if (close) onClose?.invoke()
                return close
            }
        },
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(BACKGROUND)
        isFocusable = true
        buildHeader()
        buildOutput()
        buildControls()
        buildInput()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    /** Changes shell locations. An existing session is restarted with the new configuration. */
    fun configure(
        binDirectory: File? = this.binDirectory,
        homeDirectory: File = this.homeDirectory,
        shellPath: String = this.shellPath,
        linkerPath: String = this.linkerPath,
    ) {
        val changed = this.binDirectory != binDirectory ||
            this.homeDirectory != homeDirectory ||
            this.shellPath != shellPath ||
            this.linkerPath != linkerPath
        this.binDirectory = binDirectory
        this.homeDirectory = homeDirectory
        this.shellPath = shellPath
        this.linkerPath = linkerPath
        if (changed && session != null) {
            release()
            startSession()
        }
    }

    /** Starts the backing shell if needed; repeated calls preserve the existing process. */
    fun startSession() {
        if (session?.isAlive == true) return
        val created = PersistentShellSession(
            shellPath = shellPath,
            linkerPath = linkerPath,
            workingDirectory = homeDirectory,
            homeDir = homeDirectory,
            binDir = binDirectory?.takeIf { it.isDirectory },
            onOutput = ::appendOutput,
            onCommandFinished = { _, exitCode ->
                appendOutput(if (exitCode == 0) "\n$ " else "\n[exit $exitCode]\n$ ")
            },
            onExit = { exitCode ->
                appendOutput("\n[shell exited: $exitCode]\n")
            },
        )
        session = created
        runCatching { created.start() }
            .onSuccess {
                if (output.text.isEmpty()) appendOutput("OhmLauncher :: terminal\n$ ")
            }
            .onFailure { error ->
                session = null
                appendOutput("Unable to start shell: ${error.message ?: error.javaClass.simpleName}\n")
            }
    }

    /** Executes a command in the persistent shell and returns its completion marker id. */
    fun submitCommand(command: String): Long? {
        startSession()
        val active = session ?: return null
        history.record(command)
        appendOutput("$command\n")
        input.text.clear()
        return runCatching { active.execute(command) }
            .onFailure { appendOutput("${it.message.orEmpty()}\n$ ") }
            .getOrNull()
    }

    /** Clears rendered output without changing the shell state. */
    fun clearOutput() {
        output.text = ""
    }

    /** Stops the process and releases its streams. */
    fun release() {
        session?.close()
        session = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startSession()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun buildHeader() {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            setBackgroundColor(HEADER)
        }
        header.addView(TextView(context).apply {
            text = "OHM TERMINAL"
            setTextColor(ACCENT)
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = .12f
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(keyButton("Close") { onClose?.invoke() })
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildOutput() {
        output.apply {
            setTextColor(FOREGROUND)
            setLinkTextColor(ACCENT)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        outputScroll.apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(output, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(outputScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildControls() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(HEADER)
        }
        ctrlButton = keyButton("Ctrl") {
            modifiers.toggleCtrl()
            refreshModifierButtons()
            input.requestFocus()
        }
        altButton = keyButton("Alt") {
            modifiers.toggleAlt()
            refreshModifierButtons()
            input.requestFocus()
        }
        row.addView(ctrlButton)
        row.addView(altButton)
        row.addView(controlButton("Esc", TerminalControl.ESCAPE))
        row.addView(controlButton("Tab", TerminalControl.TAB))
        row.addView(controlButton("↑", TerminalControl.UP))
        row.addView(controlButton("↓", TerminalControl.DOWN))
        row.addView(controlButton("←", TerminalControl.LEFT))
        row.addView(controlButton("→", TerminalControl.RIGHT))
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildInput() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            setBackgroundColor(HEADER)
        }
        row.addView(TextView(context).apply {
            text = "$"
            setTextColor(ACCENT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(6), 0)
        })
        input.apply {
            setSingleLine(true)
            setTextColor(FOREGROUND)
            setHintTextColor(MUTED)
            hint = "Enter command"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = rounded(0xFF101820.toInt(), dp(8).toFloat(), 0x334FDDF8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter) submitCommand(text.toString()) != null else false
            }
            setOnKeyListener { _, keyCode, event -> handleInputKey(keyCode, event) }
        }
        row.addView(input, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(keyButton("Run") { submitCommand(input.text.toString()) })
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun handleInputKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceInput(history.previous(input.text.toString()))
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceInput(history.next())
                return true
            }
        }
        val character = event.unicodeChar.takeIf { it in 0x20 until 0x7f }?.toChar() ?: return false
        val sequence = modifiers.consume(character) ?: return false
        sendControl(sequence)
        refreshModifierButtons()
        return true
    }

    private fun replaceInput(value: String) {
        input.setText(value)
        input.setSelection(value.length)
    }

    private fun controlButton(label: String, key: TerminalControl): TextView =
        keyButton(label) { sendControl(TerminalControlSequences.forKey(key)) }

    private fun sendControl(sequence: String) {
        startSession()
        runCatching { session?.write(sequence) }
            .onFailure { appendOutput("${it.message.orEmpty()}\n") }
    }

    private fun refreshModifierButtons() {
        ctrlButton.background = keyBackground(modifiers.ctrlActive)
        altButton.background = keyBackground(modifiers.altActive)
        ctrlButton.setTextColor(if (modifiers.ctrlActive) Color.BLACK else FOREGROUND)
        altButton.setTextColor(if (modifiers.altActive) Color.BLACK else FOREGROUND)
    }

    private fun keyButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        contentDescription = label
        gravity = Gravity.CENTER
        setTextColor(FOREGROUND)
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(11), dp(8), dp(11), dp(8))
        background = keyBackground(false)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(5)
        }
    }

    private fun appendOutput(value: String) {
        if (value.isEmpty()) return
        post {
            val combined = output.text.toString() + value.replace("\r\n", "\n").replace('\r', '\n')
            output.text = if (combined.length > MAX_OUTPUT_CHARS) {
                combined.takeLast(MAX_OUTPUT_CHARS)
            } else {
                combined
            }
            outputScroll.post { outputScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun keyBackground(active: Boolean): GradientDrawable = rounded(
        if (active) ACCENT else KEY_BACKGROUND,
        dp(7).toFloat(),
        if (active) ACCENT else 0x334FDDF8,
    )

    private fun rounded(color: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF05080C.toInt()
        const val HEADER = 0xFF0B1118.toInt()
        const val KEY_BACKGROUND = 0xFF16202A.toInt()
        const val FOREGROUND = 0xFFE8F1F8.toInt()
        const val MUTED = 0xFF75879A.toInt()
        const val ACCENT = 0xFF66E0FF.toInt()
        const val MAX_OUTPUT_CHARS = 200_000
    }
}
