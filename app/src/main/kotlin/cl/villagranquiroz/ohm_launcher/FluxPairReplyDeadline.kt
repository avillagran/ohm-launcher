package cl.villagranquiroz.ohm_launcher

import java.io.InputStream
import java.net.SocketTimeoutException

/** One monotonic deadline covers all frames and slow partial frames, rather than each read. */
internal object FluxPairReplyDeadline {
    fun await(input: InputStream, setReadTimeout: (Int) -> Unit,
              elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
              timeoutMs: Long = 30_000,
              dialect: FluxDialect = FluxDialect.LEGACY_KDE): Boolean {
        require(timeoutMs in 1..Int.MAX_VALUE.toLong())
        val started = elapsedMs()
        fun remaining(): Int {
            val elapsed = elapsedMs() - started
            if (elapsed < 0 || elapsed >= timeoutMs) throw SocketTimeoutException("Flux pairing timed out")
            return (timeoutMs - elapsed).toInt()
        }
        while (true) {
            val frame = FluxWire.readLine(input, beforeRead = { setReadTimeout(remaining()) })
            val reply = FluxWire.pairReply(frame, dialect)
            remaining() // A completed read cannot accept a reply after the deadline.
            if (reply != null) return reply
        }
    }
}
