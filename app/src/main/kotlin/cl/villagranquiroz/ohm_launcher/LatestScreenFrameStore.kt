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
    private var latest: CapturedScreenFrame? = null
    private var sequence = 0L

    @Synchronized
    fun update(jpeg: ByteArray, width: Int, height: Int) {
        require(jpeg.isNotEmpty() && width > 0 && height > 0)
        sequence += 1
        latest = CapturedScreenFrame(jpeg.copyOf(), width, height, sequence, System.currentTimeMillis())
    }

    @Synchronized
    fun snapshot(): CapturedScreenFrame? = latest?.copy(jpeg = latest!!.jpeg.copyOf())

    @Synchronized
    fun status(): ScreenFrameStatus = latest?.let {
        ScreenFrameStatus(sequence, it.capturedAt, it.width, it.height)
    } ?: ScreenFrameStatus(sequence, 0, 0, 0)

    @Synchronized
    fun clear() {
        latest = null
        sequence = 0
    }
}
