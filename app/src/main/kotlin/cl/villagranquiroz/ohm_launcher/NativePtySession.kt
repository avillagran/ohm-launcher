package cl.villagranquiroz.ohm_launcher

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal object NativePtyBridge {
    init {
        System.loadLibrary("ohmpty")
    }

    external fun spawn(
        shellPath: String,
        workingDirectory: String,
        environment: Array<String>,
        rows: Int,
        columns: Int,
    ): IntArray

    external fun resize(fd: Int, rows: Int, columns: Int)

    external fun terminate(pid: Int)
}

class NativePtySession private constructor(
    private val pid: Int,
    private val descriptor: ParcelFileDescriptor,
) : Closeable {
    private val closed = AtomicBoolean()
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)

    @Synchronized
    fun write(text: String) {
        check(!closed.get()) { "PTY is closed" }
        output.write(text.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    fun resize(rows: Int, columns: Int) {
        require(rows > 0 && columns > 0)
        if (!closed.get()) NativePtyBridge.resize(descriptor.fd, rows, columns)
    }

    fun startReading(onChunk: (String) -> Unit, onClosed: () -> Unit = {}): Thread =
        Thread({
            val buffer = ByteArray(4096)
            try {
                while (!closed.get()) {
                    val count = try {
                        input.read(buffer)
                    } catch (_: java.io.IOException) {
                        break
                    }
                    if (count < 0) break
                    onChunk(String(buffer, 0, count, StandardCharsets.UTF_8))
                }
            } finally {
                onClosed()
            }
        }, "ohm-pty-stream").apply {
            isDaemon = true
            start()
        }

    fun readUntilExit(timeoutMillis: Long): String {
        require(timeoutMillis > 0)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ohm-pty-reader").apply { isDaemon = true }
        }
        val future = executor.submit<String> {
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (_: java.io.IOException) {
                    break
                }
                if (count < 0) break
                result.write(buffer, 0, count)
            }
            result.toString(StandardCharsets.UTF_8.name())
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            close()
            future.get(1, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        NativePtyBridge.terminate(pid)
        runCatching { descriptor.close() }
    }

    companion object {
        fun open(
            shellPath: String,
            workingDirectory: File,
            environment: Map<String, String>,
            rows: Int,
            columns: Int,
        ): NativePtySession {
            require(File(shellPath).isFile) { "Shell does not exist: $shellPath" }
            require(workingDirectory.isDirectory) { "Working directory does not exist" }
            require(rows > 0 && columns > 0)
            val spawned = NativePtyBridge.spawn(
                shellPath,
                workingDirectory.absolutePath,
                environment.map { (name, value) -> "$name=$value" }.toTypedArray(),
                rows,
                columns,
            )
            check(spawned.size == 2 && spawned[0] > 0 && spawned[1] >= 0) {
                "Unable to create PTY (errno=${spawned.getOrElse(1) { -1 }})"
            }
            return NativePtySession(spawned[0], ParcelFileDescriptor.adoptFd(spawned[1]))
        }
    }
}
