package cl.villagranquiroz.ohm_launcher

import org.junit.Test
import java.io.InputStream
import java.io.InterruptedIOException

class TtfxProcessTest {
    @Test
    fun interruptedErrorPipeDoesNotCrashTheProcess() {
        val interrupted = object : InputStream() {
            override fun read(): Int = throw InterruptedIOException("closed")
        }

        drainTtfxErrors(interrupted)
    }
}
