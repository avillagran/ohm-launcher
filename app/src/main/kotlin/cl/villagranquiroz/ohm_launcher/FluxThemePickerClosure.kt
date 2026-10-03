package cl.villagranquiroz.ohm_launcher

import java.util.concurrent.atomic.AtomicBoolean

/** Per-picker, exact-session close; terminal callbacks may arrive after dismissal. */
internal class FluxThemePickerClosure<S : Any>(
    private val original: S,
    private val closeAfter: Boolean,
    private val close: (S) -> Unit,
) {
    private val finished = AtomicBoolean(false)
    fun finish() {
        if (closeAfter && finished.compareAndSet(false, true)) close(original)
    }
}
