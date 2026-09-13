package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWidgetContractTest {
    @Test
    fun parsesRelativeProviderComponent() {
        val config = AppWidgetProviderConfig.parse("com.example.clock/.ClockWidget")

        assertEquals("com.example.clock", config?.packageName)
        assertEquals("com.example.clock.ClockWidget", config?.className)
        assertEquals("com.example.clock/.ClockWidget", config?.flattenedName)
    }

    @Test
    fun parsesFullyQualifiedProviderComponent() {
        val config = AppWidgetProviderConfig.parse(
            "com.example.clock/com.example.widgets.ClockWidget",
        )

        assertEquals("com.example.clock", config?.packageName)
        assertEquals("com.example.widgets.ClockWidget", config?.className)
        assertEquals(
            "com.example.clock/com.example.widgets.ClockWidget",
            config?.flattenedName,
        )
    }

    @Test
    fun rejectsMalformedProviderComponents() {
        listOf(
            "",
            "com.example.clock",
            "/.ClockWidget",
            "com.example.clock/",
            "com.example.clock/ClockWidget",
            "com example/.ClockWidget",
            "com.example/.Clock Widget",
            "com.example/.ClockWidget/extra",
        ).forEach { value ->
            assertNull("Expected rejection for $value", AppWidgetProviderConfig.parse(value))
        }
    }

    @Test
    fun providerDtoUsesStablePlatformContractKeys() {
        val dto = AppWidgetProviderDto(
            label = "Clock",
            packageName = "com.example.clock",
            provider = "com.example.clock/.ClockWidget",
            minWidth = 240,
            minHeight = 120,
        )

        assertEquals(
            mapOf(
                "label" to "Clock",
                "package" to "com.example.clock",
                "provider" to "com.example.clock/.ClockWidget",
                "minWidth" to 240,
                "minHeight" to 120,
            ),
            dto.toPlatformMap(),
        )
    }

    @Test
    fun parsesHostViewSizeAndConvertsPixelsToDp() {
        val size = AppWidgetHostViewSize.fromPlatformMap(
            mapOf("width" to 301L, "height" to 199.0),
            density = 2f,
        )

        assertEquals(301, size?.widthPx)
        assertEquals(199, size?.heightPx)
        assertEquals(151, size?.widthDp)
        assertEquals(100, size?.heightDp)
    }

    @Test
    fun rejectsInvalidHostViewSize() {
        assertNull(
            AppWidgetHostViewSize.fromPlatformMap(
                emptyMap<String, Any>(),
                density = 1f,
            ),
        )
        assertNull(
            AppWidgetHostViewSize.fromPlatformMap(
                mapOf("width" to 0, "height" to 100),
                density = 1f,
            ),
        )
        assertNull(
            AppWidgetHostViewSize.fromPlatformMap(
                mapOf("width" to 100, "height" to 100),
                density = 0f,
            ),
        )
    }

    @Test
    fun requestsImmediateBindingWhenAllowed() {
        val host = FakeIdHost()
        val binder = FakeBinder(bindAllowed = true)
        val coordinator = AppWidgetBindingCoordinator(host, binder)

        val result = coordinator.request("com.example.clock/.ClockWidget")

        assertEquals(
            AppWidgetBindingRequest.Bound(41, "com.example.clock/.ClockWidget"),
            result,
        )
        assertEquals(emptyList<Int>(), host.deletedIds)
    }

    @Test
    fun requestsPermissionAndCompletesSystemBinding() {
        val host = FakeIdHost()
        val binder = FakeBinder(bindAllowed = false)
        val coordinator = AppWidgetBindingCoordinator(host, binder)

        val request = coordinator.request("com.example.clock/.ClockWidget")
        assertEquals(
            AppWidgetBindingRequest.PermissionRequired(
                41,
                "com.example.clock/.ClockWidget",
            ),
            request,
        )

        binder.boundIds += 41
        val completion = coordinator.complete(41, approved = true)

        assertEquals(
            AppWidgetBindingCompletion.Bound(41, "com.example.clock/.ClockWidget"),
            completion,
        )
        assertEquals(emptyList<Int>(), host.deletedIds)
    }

    @Test
    fun rejectedPermissionDeletesAllocatedId() {
        val host = FakeIdHost()
        val coordinator = AppWidgetBindingCoordinator(host, FakeBinder(bindAllowed = false))
        coordinator.request("com.example.clock/.ClockWidget")

        val completion = coordinator.complete(41, approved = false)

        assertEquals(
            AppWidgetBindingCompletion.Failed(41, "com.example.clock/.ClockWidget"),
            completion,
        )
        assertEquals(listOf(41), host.deletedIds)
    }

    @Test
    fun malformedProviderDoesNotAllocateAnId() {
        val host = FakeIdHost()
        val coordinator = AppWidgetBindingCoordinator(host, FakeBinder(bindAllowed = true))

        val result = coordinator.request("not-a-component")

        assertTrue(result is AppWidgetBindingRequest.InvalidProvider)
        assertEquals(0, host.allocations)
    }

    @Test
    fun bindingExceptionDeletesTheNewlyAllocatedId() {
        val host = FakeIdHost()
        val coordinator = AppWidgetBindingCoordinator(
            host,
            object : AppWidgetIdBinder {
                override fun bindIfAllowed(
                    appWidgetId: Int,
                    provider: AppWidgetProviderConfig,
                ): Boolean = error("binder unavailable")

                override fun isBound(
                    appWidgetId: Int,
                    provider: AppWidgetProviderConfig,
                ): Boolean = false
            },
        )

        assertTrue(
            runCatching { coordinator.request("com.example.clock/.ClockWidget") }.isFailure,
        )
        assertEquals(listOf(41), host.deletedIds)
    }

    @Test
    fun unknownCompletionDoesNotDeleteAnotherId() {
        val host = FakeIdHost()
        val coordinator = AppWidgetBindingCoordinator(host, FakeBinder(bindAllowed = false))
        coordinator.request("com.example.clock/.ClockWidget")

        assertEquals(AppWidgetBindingCompletion.UnknownRequest(99), coordinator.complete(99, true))
        assertEquals(emptyList<Int>(), host.deletedIds)
    }

    @Test
    fun completionExceptionDeletesThePendingId() {
        val host = FakeIdHost()
        var requested = false
        val coordinator = AppWidgetBindingCoordinator(
            host,
            object : AppWidgetIdBinder {
                override fun bindIfAllowed(
                    appWidgetId: Int,
                    provider: AppWidgetProviderConfig,
                ): Boolean {
                    if (!requested) {
                        requested = true
                        return false
                    }
                    error("binder unavailable")
                }

                override fun isBound(
                    appWidgetId: Int,
                    provider: AppWidgetProviderConfig,
                ): Boolean = error("binder unavailable")
            },
        )
        coordinator.request("com.example.clock/.ClockWidget")

        assertTrue(runCatching { coordinator.complete(41, approved = true) }.isFailure)
        assertEquals(listOf(41), host.deletedIds)
    }

    @Test
    fun listeningLifecycleIsIdempotent() {
        val host = FakeListeningHost()
        val lifecycle = AppWidgetListeningLifecycle(host)

        lifecycle.start()
        lifecycle.start()
        lifecycle.stop()
        lifecycle.stop()

        assertEquals(1, host.starts)
        assertEquals(1, host.stops)
    }

    @Test
    fun failedStartCanBeRetriedAndDoesNotStop() {
        val host = FakeListeningHost(failFirstStart = true)
        val lifecycle = AppWidgetListeningLifecycle(host)

        assertTrue(runCatching { lifecycle.start() }.isFailure)
        lifecycle.stop()
        lifecycle.start()

        assertEquals(2, host.starts)
        assertEquals(0, host.stops)
    }

    private class FakeListeningHost(
        private var failFirstStart: Boolean = false,
    ) : AppWidgetListeningHost {
        var starts = 0
        var stops = 0

        override fun startListening() {
            starts += 1
            if (failFirstStart) {
                failFirstStart = false
                error("start failed")
            }
        }

        override fun stopListening() {
            stops += 1
        }
    }

    private class FakeIdHost : AppWidgetIdHost {
        var allocations = 0
        val deletedIds = mutableListOf<Int>()

        override fun allocateAppWidgetId(): Int {
            allocations += 1
            return 40 + allocations
        }

        override fun deleteAppWidgetId(appWidgetId: Int) {
            deletedIds += appWidgetId
        }
    }

    private class FakeBinder(
        private val bindAllowed: Boolean,
    ) : AppWidgetIdBinder {
        val boundIds = mutableSetOf<Int>()

        override fun bindIfAllowed(appWidgetId: Int, provider: AppWidgetProviderConfig): Boolean {
            if (bindAllowed) boundIds += appWidgetId
            return bindAllowed
        }

        override fun isBound(appWidgetId: Int, provider: AppWidgetProviderConfig): Boolean =
            appWidgetId in boundIds
    }
}
