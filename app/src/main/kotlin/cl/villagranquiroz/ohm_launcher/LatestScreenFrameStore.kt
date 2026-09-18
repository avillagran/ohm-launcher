package cl.villagranquiroz.ohm_launcher

/** Immutable JPEG frame exposed through the launcher's pull-based screen API. */
data class CapturedScreenFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val sequence: Long,
    val capturedAt: Long,
)

data class ScreenFrameStatus(
    val frames: Long,
    val last: Long,
    val width: Int,
    val height: Int,
)

/** Thread-safe latest-frame slot; slow viewers never create an unbounded queue. */
class LatestScreenFrameStore {
    private val lock = Object()
    private var latest: CapturedScreenFrame? = null
    private var sequence = 0L

    fun update(jpeg: ByteArray, width: Int, height: Int) {
        require(jpeg.isNotEmpty() && width > 0 && height > 0)
        synchronized(lock) {
            sequence += 1
            latest = CapturedScreenFrame(jpeg.copyOf(), width, height, sequence, System.currentTimeMillis())
            // Wake long-polling viewers the instant a frame lands (push-over-HTTP).
            lock.notifyAll()
        }
    }

    /** Blocks until a frame newer than [sequence] arrives or [timeoutMs]
     *  elapses (null then). The panel chains these to get push latency with
     *  plain XMLHttpRequest — no fixed polling interval. */
    fun awaitNewerThan(sequence: Long, timeoutMs: Long): CapturedScreenFrame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var remaining = timeoutMs
        synchronized(lock) {
            while (remaining > 0L) {
                val current = latest
                if (current != null && current.sequence > sequence) {
                    return current.copy(jpeg = current.jpeg.copyOf())
                }
                lock.wait(remaining)
                remaining = deadline - System.currentTimeMillis()
            }
        }
        return null
    }

    fun snapshot(): CapturedScreenFrame? = synchronized(lock) {
        latest?.copy(jpeg = latest!!.jpeg.copyOf())
    }

    fun status(): ScreenFrameStatus = synchronized(lock) {
        latest?.let { ScreenFrameStatus(sequence, it.capturedAt, it.width, it.height) }
            ?: ScreenFrameStatus(sequence, 0, 0, 0)
    }

    fun clear() {
        synchronized(lock) {
            latest = null
            sequence = 0
            lock.notifyAll()
        }
    }
}
