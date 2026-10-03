package cl.villagranquiroz.ohm_launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FluxDocumentPickerRegistryTest {
    private class Registry : ActivityResultRegistry() {
        val requestCodes = mutableMapOf<String, Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                     options: androidx.core.app.ActivityOptionsCompat?) {
            requestCodes[(input as Array<*>).single().toString()] = requestCode
        }
    }

    private fun result(uri: String) = Intent().setData(Uri.parse(uri))

    @Test fun lateDuplicateIsNotDeliveredToNewLaunch() {
        val registry = Registry()
        val received = mutableListOf<String>()
        val a = registry.register("unique-A", ActivityResultContracts.OpenDocument()) {
            received.add("A:$it")
        }
        a.launch(arrayOf("A"))
        val aCode = registry.requestCodes.getValue("A")
        assertTrue(registry.dispatchResult(aCode, Activity.RESULT_OK, result("content://a")))
        val b = registry.register("unique-B", ActivityResultContracts.OpenDocument()) {
            received.add("B:$it")
        }
        b.launch(arrayOf("B"))
        val bCode = registry.requestCodes.getValue("B")
        assertTrue(registry.dispatchResult(aCode, Activity.RESULT_OK, result("content://late-a")))
        assertEquals(listOf("A:content://a"), received)
        assertTrue(registry.dispatchResult(bCode, Activity.RESULT_OK, result("content://b")))
        assertEquals(listOf("A:content://a", "B:content://b"), received)
        a.unregister()
        b.unregister()
    }

    @Test fun oldPendingResultAfterRecreationIsDiscardedWithoutBlockingFreshLaunch() {
        val oldRegistry = Registry()
        val old = oldRegistry.register("unique-old", ActivityResultContracts.OpenDocument()) {
            fail("Old result must not reach destroyed owner: $it")
        }
        old.launch(arrayOf("old"))
        val oldCode = oldRegistry.requestCodes.getValue("old")
        val state = Bundle()
        oldRegistry.onSaveInstanceState(state)
        old.unregister()

        val restored = Registry()
        restored.onRestoreInstanceState(state)
        val discard = restored.register("unique-old", ActivityResultContracts.OpenDocument()) { _: Uri? -> }
        val received = mutableListOf<Uri?>()
        val fresh = restored.register("unique-new", ActivityResultContracts.OpenDocument()) {
            received.add(it)
        }
        fresh.launch(arrayOf("new"))
        assertTrue(restored.dispatchResult(oldCode, Activity.RESULT_OK, result("content://old")))
        assertTrue(received.isEmpty())
        assertTrue(restored.dispatchResult(restored.requestCodes.getValue("new"),
            Activity.RESULT_OK, result("content://new")))
        assertEquals(listOf(Uri.parse("content://new")), received)
        discard.unregister()
        fresh.unregister()
    }
}
