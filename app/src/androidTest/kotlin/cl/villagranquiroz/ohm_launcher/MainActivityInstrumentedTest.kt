package cl.villagranquiroz.ohm_launcher

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference


@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @Test
    fun launchesTheNativeHomeViewUnderThePreservedPackage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("cl.villagranquiroz.ohm_launcher", activity.packageName)
                assertNotNull(findDescendant(activity.window.decorView, NativeLauncherView::class.java))
            }
        }
    }

    @Test
    fun nativePtyProvidesATerminalWithConfiguredDimensions() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        NativePtySession.open(
            shellPath = "/system/bin/sh",
            workingDirectory = context.filesDir,
            environment = mapOf("TERM" to "xterm-256color"),
            rows = 24,
            columns = 80,
        ).use { session ->
            session.write("stty size; if [ -t 0 ]; then echo PTY_OK; fi; exit\n")
            val output = session.readUntilExit(5_000)
            org.junit.Assert.assertTrue(output.contains("24 80"))
            org.junit.Assert.assertTrue(output.contains("PTY_OK"))
        }
    }

    @Test
    fun longPressOpensOmarchyMenuWithoutAnOrbitalMenu() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val launcherRef = AtomicReference<NativeLauncherView>()
            val downTime = SystemClock.uptimeMillis()
            scenario.onActivity { activity ->
                val launcher = findDescendant(activity.window.decorView, NativeLauncherView::class.java) as NativeLauncherView
                launcherRef.set(launcher)
                launcher.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 600f, 1800f, 0))
            }
            Thread.sleep(700)
            scenario.onActivity {
                launcherRef.get().dispatchTouchEvent(
                    MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 600f, 1800f, 0),
                )
            }
            scenario.onActivity {
                assertNotNull(findDescendant(launcherRef.get(), OmarchyMenuOverlay::class.java))
                assertEquals(null, findDescendant(launcherRef.get(), OrbitalActionMenu::class.java))
            }
        }
    }

    private fun findDescendant(root: View, type: Class<out View>): View? {
        if (type.isInstance(root)) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findDescendant(root.getChildAt(index), type)?.let { return it }
        }
        return null
    }

}
