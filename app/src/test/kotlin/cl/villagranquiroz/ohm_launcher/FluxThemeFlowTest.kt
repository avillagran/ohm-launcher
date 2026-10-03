package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FluxThemeFlowTest {
    @Test fun blockedRenameBDoesNotReportLocalSuccessUntilCommitted() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val folder = org.junit.rules.TemporaryFolder().apply { create() }
        try {
            val target = folder.newFile("settings.json").apply { writeText("{}") }
            val store = LauncherSettingsStore(target, themeIo = object : ThemeTransactionIo {
                override fun stage(temporary: java.io.File, bytes: ByteArray) { temporary.writeBytes(bytes) }
                override fun replace(temporary: java.io.File, target: java.io.File) {
                    if (String(temporary.readBytes()).contains("\"Two\"")) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    check(temporary.renameTo(target))
                }
                override fun syncDirectory(directory: java.io.File) = Unit
            })
            val queued = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
            val completed = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
            val session = Any()
            val flow = FluxThemeFlow<Any>(allowed = { true }, schedule = { queued.add(it) },
                apply = { palette, authority -> store.prepareOmarchyTheme(palette.toJson()).commit(authority) },
                select = { _, _ -> true },
                onApplied = { _, id, success -> completed.add(id to success) })
            flow.receive("peer", session, catalogA)
            queued.clear()
            assertTrue(flow.request("peer", session, catalogA, "two"))
            assertTrue(flow.ack("peer", session, FluxThemeSelection.Result("B", true, "two")))
            val worker = Thread { queued.removeAt(0)() }.also { it.start() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue("remote ACK is not a local commit", completed.isEmpty())
                release.countDown()
                worker.join(5000)
                assertEquals(listOf("B" to true), completed.toList())
                assertEquals("Two", store.themeSnapshot().getString("name"))
            } finally { release.countDown(); worker.join(5000) }
        } finally { folder.delete() }
    }
    @Test fun ackedAIsSupersededPromptlyWhileBCommitBlocksWithoutClaimingBApplied() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobs = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        val superseded = java.util.concurrent.CopyOnWriteArrayList<Pair<Any, String>>()
        val completed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val session = Any()
        val flow = FluxThemeFlow<Any>(allowed = { true }, schedule = { jobs.add(it) },
            apply = { palette, _ ->
                if (palette.name == "Two") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                committed()
            }, select = { _, _ -> true },
            onApplied = { _, id, ok -> if (ok) completed.add(id) },
            onSuperseded = { owner, id -> superseded.add(owner to id) })
        flow.receive("peer", session, catalogA)
        jobs.clear()
        assertTrue(flow.request("peer", session, catalogA, "two"))
        assertTrue(flow.ack("peer", session, FluxThemeSelection.Result("A", true, "one")))
        flow.receive("peer", session, catalogB)
        assertEquals(listOf(session to "A"), superseded.toList())
        jobs.removeAt(0)() // The queued A job must not report success.
        val worker = Thread { jobs.removeAt(0)() }.also { it.start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(completed.isEmpty())
            assertEquals(listOf(session to "A"), superseded.toList())
        } finally { release.countDown(); worker.join(5000) }
        assertTrue(completed.isEmpty())
    }
    @Test fun revokedAckApplyCannotReportSuccessAfterBlockedDurableWrite() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobs = mutableListOf<() -> Unit>()
        val completions = mutableListOf<Boolean>()
        val session = Any()
        val flow = FluxThemeFlow<Any>(allowed = { true }, schedule = { jobs.add(it) },
            apply = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); committed() },
            select = { _, _ -> true }, onApplied = { _, _, success -> completions.add(success) })
        flow.receive("peer", session, catalogA)
        jobs.clear()
        assertTrue(flow.request("peer", session, catalogA, "two"))
        assertTrue(flow.ack("peer", session, FluxThemeSelection.Result("request", true, "two")))
        val worker = Thread { jobs.removeAt(0)() }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            flow.revoke("peer", session)
            release.countDown()
            worker.join(5000)
            assertTrue("revoked selection reported applied", completions.isEmpty())
        } finally { release.countDown(); worker.join(5000) }
    }
    @Test fun revokeAndReplacementRemainPromptWhileApplyBlocksAndNewestWins() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = mutableListOf<() -> Unit>()
        val writes = mutableListOf<String>()
        val first = Any()
        val second = Any()
        var live: Any = first
        val flow = FluxThemeFlow<Any>(
            allowed = { it === live }, schedule = { queued.add(it) },
            apply = { palette, _ ->
                if (palette.name == "One") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                synchronized(writes) { writes.add(palette.name) }
                committed()
            }, select = { _, _ -> true },
        )
        val one = catalogA
        val two = catalogB
        flow.receive("peer", first, one)
        val applying = Thread { queued.removeAt(0)() }
        applying.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val finished = CountDownLatch(1)
            val replacing = Thread {
                flow.revoke("peer", first)
                live = second
                flow.receive("peer", second, two)
                finished.countDown()
            }
            replacing.start()
            assertTrue("revoke/replace blocked by apply", finished.await(1, TimeUnit.SECONDS))
            release.countDown()
            applying.join(5000)
            queued.removeAt(0)()
            assertEquals("Two", synchronized(writes) { writes.last() })
        } finally { release.countDown(); applying.join(5000) }
    }

    @Test fun revokeAndReplacementRemainPromptWhileSelectBlocks() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Any()
        val second = Any()
        var live: Any = first
        val flow = FluxThemeFlow<Any>(allowed = { it === live }, schedule = {}, apply = { _, _ -> committed() },
            select = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true })
        flow.receive("peer", first, catalogA)
        val selecting = Thread { flow.request("peer", first, catalogA, "two") }
        selecting.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val finished = CountDownLatch(1)
            val replacing = Thread {
                flow.revoke("peer", first)
                live = second
                flow.receive("peer", second, catalogB)
                finished.countDown()
            }
            replacing.start()
            assertTrue("revoke/replace blocked by select", finished.await(1, TimeUnit.SECONDS))
            release.countDown()
            selecting.join(5000)
            assertFalse(flow.ack("peer", first, FluxThemeSelection.Result("old", true, "two")))
        } finally { release.countDown(); selecting.join(5000) }
    }
    @Test fun blockedStagingRevokesWithoutPublishingOrAcknowledgingAndReconcilesNewest() {
        val file = org.junit.rules.TemporaryFolder().apply { create() }
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val target = file.newFile("settings.json")
            target.writeText("{}")
            val io = object : ThemeTransactionIo {
                override fun stage(temporary: java.io.File, bytes: ByteArray) {
                    if (String(bytes).contains("\"One\"")) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    temporary.writeBytes(bytes)
                }
                override fun replace(temporary: java.io.File, target: java.io.File) { check(temporary.renameTo(target)) }
                override fun syncDirectory(directory: java.io.File) = Unit
            }
            val store = LauncherSettingsStore(target, themeIo = io)
            val queue = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
            val published = mutableListOf<String>()
            val first = Any()
            val second = Any()
            var live: Any = first
            val flow = FluxThemeFlow<Any>(allowed = { it === live }, schedule = { queue.add(it) },
                apply = { palette, authority -> store.prepareOmarchyTheme(palette.toJson()).commit(authority) },
                select = { _, _ -> true }, onCommitted = { _, palette, authority ->
                    if (authority()) published.add(palette.name)
                })
            flow.receive("peer", first, catalogA)
            val worker = Thread { queue.removeAt(0)() }.also { it.start() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                live = second
                flow.revoke("peer", first)
                flow.receive("peer", second, catalogB)
            } finally { release.countDown(); worker.join(5000) }
            assertFalse(worker.isAlive)
            queue.toList().forEach { queue.remove(it); it() }
            assertEquals("Two", store.themeSnapshot().getString("name"))
            assertEquals(listOf("Two"), published)
        } finally { file.delete() }
    }

    @Test fun blockedRenameOfOldSessionSchedulesReconciliationWithoutAnotherPacket() {
        val folder = org.junit.rules.TemporaryFolder().apply { create() }
        try {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        val target = folder.newFile("settings.json").apply { writeText("{}") }
        val store = LauncherSettingsStore(target, themeIo = object : ThemeTransactionIo {
            override fun stage(temporary: java.io.File, bytes: ByteArray) { temporary.writeBytes(bytes) }
            override fun replace(temporary: java.io.File, target: java.io.File) {
                if (String(temporary.readBytes()).contains("\"One\"")) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                check(temporary.renameTo(target))
            }
            override fun syncDirectory(directory: java.io.File) = Unit
        })
        val published = mutableListOf<String>()
        val first = Any()
        val second = Any()
        var live: Any = first
        val flow = FluxThemeFlow<Any>(allowed = { it === live }, schedule = { queue.add(it) },
            apply = { palette, authority -> store.prepareOmarchyTheme(palette.toJson()).commit(authority) },
            select = { _, _ -> true }, onCommitted = { _, palette, authority ->
                if (authority()) published.add(palette.name)
            })
        flow.receive("peer", first, catalogA)
        val worker = Thread { queue.removeAt(0)() }.also { it.start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            live = second
            flow.revoke("peer", first)
            flow.receive("peer", second, catalogB)
            // Simulate a dropped original B worker: the A completion must queue a fresh B.
            queue.clear()
        } finally { release.countDown(); worker.join(5000) }
        assertEquals("One", store.themeSnapshot().getString("name"))
        assertTrue("missing reconciliation", queue.isNotEmpty())
        queue.removeAt(0)()
        assertEquals("Two", store.themeSnapshot().getString("name"))
        assertEquals(listOf("Two"), published)
        } finally { folder.delete() }
    }

    @Test fun timeoutDuringLocalApplyKeepsDesiredPaletteButSuppressesLateAck() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobs = mutableListOf<() -> Unit>()
        val completions = mutableListOf<Boolean>()
        val published = mutableListOf<String>()
        val session = Any()
        val flow = FluxThemeFlow<Any>(allowed = { true }, schedule = { jobs.add(it) },
            apply = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); committed() },
            select = { _, _ -> true }, onApplied = { _, _, ok -> completions.add(ok) },
            onCommitted = { _, palette, token -> if (token()) published.add(palette.name) })
        flow.receive("peer", session, catalogA)
        jobs.clear()
        assertTrue(flow.request("peer", session, catalogA, "two"))
        assertTrue(flow.ack("peer", session, FluxThemeSelection.Result("request", true, "two")))
        val worker = Thread { jobs.removeAt(0)() }.also { it.start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            flow.timeout("peer", session)
        } finally { release.countDown(); worker.join(5000) }
        assertEquals(listOf("Two"), published)
        assertTrue(completions.isEmpty())
    }

    @Test fun equivalentCatalogRefreshStillAllowsPickingFromLatestCatalogObject() {
        val queue = mutableListOf<() -> Unit>()
        val session = Any()
        val flow = FluxThemeFlow<Any>(allowed = { true }, schedule = { queue.add(it) },
            apply = { _, _ -> committed() }, select = { _, _ -> true })
        flow.receive("peer", session, catalogA)
        val refreshed = catalogA.copy()
        flow.receive("peer", session, refreshed)
        assertTrue(flow.request("peer", session, refreshed, "two"))
    }

    @Test fun equivalentCatalogBeforeAckKeepsPendingSelectionAndReportsActualCommit() {
        flow.receive("peer", a, catalogA)
        jobs.clear()
        assertTrue(flow.request("peer", a, catalogA, "two"))
        flow.receive("peer", a, catalogA.copy())
        assertTrue(flow.ack("peer", a, FluxThemeSelection.Result("request", true, "two")))
        assertTrue(completions.isEmpty())
        jobs.removeAt(0)()
        assertEquals(listOf("Two"), applied)
        assertEquals(listOf(a to true), completions)
        assertEquals(listOf(a to "two"), selects)
    }

    @Test fun acceptedAckBeforeSelectReturnsStillReportsSendAndWaitsForLocalCommit() {
        val queue = mutableListOf<() -> Unit>()
        val completed = mutableListOf<Pair<String, Boolean>>()
        val palettes = mutableListOf<String>()
        val session = Any()
        lateinit var flow: FluxThemeFlow<Any>
        flow = FluxThemeFlow(allowed = { it === session }, schedule = { queue.add(it) },
            apply = { palette, _ -> palettes.add(palette.name); committed() },
            select = { _, _ ->
                assertTrue(flow.ack("peer", session, FluxThemeSelection.Result("request", true, "two")))
                true
            }, onApplied = { _, id, ok -> completed.add(id to ok) })
        flow.receive("peer", session, catalogA)
        queue.clear()
        assertTrue(flow.request("peer", session, catalogA, "two"))
        assertTrue(completed.isEmpty())
        assertTrue(palettes.isEmpty())
        queue.removeAt(0)()
        assertEquals(listOf("Two"), palettes)
        assertEquals(listOf("request" to true), completed)
        assertFalse(flow.ack("peer", session, FluxThemeSelection.Result("request", true, "two")))
    }

    @Test fun earlyAckDoesNotResurrectWithdrawnRequestOrAcceptForeignDenial() {
        for (mode in listOf("revoked", "replacement", "timeout", "unwanted", "foreign", "denied")) {
            val session = Any()
            val foreign = Any()
            var wanted = true
            lateinit var flow: FluxThemeFlow<Any>
            flow = FluxThemeFlow(allowed = { true }, schedule = {}, apply = { _, _ -> committed() },
                select = { _, _ ->
                    val accepted = flow.ack("peer", if (mode == "foreign") foreign else session,
                        FluxThemeSelection.Result("request", mode != "denied", "two"))
                    assertEquals(mode, mode != "foreign" && mode != "denied", accepted)
                    when (mode) {
                        "revoked" -> flow.revoke("peer", session)
                        "replacement" -> flow.receive("peer", foreign, catalogB)
                        "timeout", "foreign" -> flow.timeout("peer", session)
                        "unwanted" -> wanted = false
                    }
                    true
                })
            flow.receive("peer", session, catalogA)
            assertFalse(mode, flow.request("peer", session, catalogA, "two") { wanted })
        }
    }

    @Test fun changedCurrentAndPaletteBeforeAckUsesLatestPaletteAndCompletesSelection() {
        val queue = mutableListOf<() -> Unit>()
        val palettes = mutableListOf<OmarchyThemePalette>()
        val completed = mutableListOf<Pair<String, Boolean>>()
        val sent = mutableListOf<String>()
        val session = Any()
        val flow = FluxThemeFlow<Any>(allowed = { it === session }, schedule = { queue.add(it) },
            apply = { palette, _ -> palettes.add(palette); committed() },
            select = { _, id -> sent.add(id); true },
            onApplied = { _, id, ok -> completed.add(id to ok) })
        flow.receive("peer", session, catalogA)
        queue.clear()
        assertTrue(flow.request("peer", session, catalogA, "two"))
        val revised = catalogB.copy(themes = catalogB.themes.map { theme ->
            if (theme.id == "two") theme.copy(palette = theme.palette.copy(
                colors = mapOf("background" to "#333333"))) else theme
        })
        val latest = revised.themes.first { it.id == "two" }.palette
        flow.receive("peer", session, revised)
        queue.removeAt(0)()
        assertEquals(listOf(latest), palettes)
        assertTrue("catalog application is not the correlated ACK", completed.isEmpty())
        assertTrue(flow.ack("peer", session, FluxThemeSelection.Result("request", true, "two")))
        queue.removeAt(0)()
        assertEquals(listOf(latest, latest), palettes)
        assertEquals(listOf("request" to true), completed)
        assertEquals(listOf("two"), sent)
    }

    @Test fun foreignAckAfterCatalogRefreshDoesNotConsumeExactSessionRequest() {
        otherLive = b
        flow.receive("peer", a, catalogA)
        jobs.clear()
        assertTrue(flow.request("peer", a, catalogA, "two"))
        flow.receive("peer", a, catalogB)
        assertFalse(flow.ack("peer", b, FluxThemeSelection.Result("foreign", true, "two")))
        assertTrue(flow.ack("peer", a, FluxThemeSelection.Result("request", true, "two")))
        jobs.forEach { it() }
        assertEquals(listOf(a to true), completions)
        assertEquals(listOf("Two"), applied)
    }

    @Test fun newerDifferentCurrentBeforeAckSupersedesRequestWithoutRestoringOldPalette() {
        val queue = mutableListOf<() -> Unit>()
        val palettes = mutableListOf<String>()
        val superseded = mutableListOf<String>()
        val completed = mutableListOf<Boolean>()
        val session = Any()
        val flow = FluxThemeFlow<Any>(allowed = { it === session }, schedule = { queue.add(it) },
            apply = { palette, _ -> palettes.add(palette.name); committed() },
            select = { _, _ -> true }, onApplied = { _, _, ok -> completed.add(ok) },
            onSuperseded = { _, id -> superseded.add(id) })
        flow.receive("peer", session, catalogA)
        queue.clear()
        assertTrue(flow.request("peer", session, catalogA, "two"))
        val third = FluxThemeCatalog.Theme("three", "Three", OmarchyThemePalette(
            "Three", OmarchyThemeMode.DARK, mapOf("background" to "#333333")))
        val latest = catalogA.copy(current = "three", themes = catalogA.themes + third)
        flow.receive("peer", session, latest)
        assertTrue(flow.ack("peer", session, FluxThemeSelection.Result("request", true, "two")))
        queue.forEach { it() }
        assertEquals(listOf("Three"), palettes)
        assertEquals(listOf("request"), superseded)
        assertTrue(completed.isEmpty())
    }

    @Test fun replacementAndTimeoutStillRejectAcksAfterCatalogRefresh() {
        flow.receive("peer", a, catalogA)
        jobs.clear()
        assertTrue(flow.request("peer", a, catalogA, "two"))
        flow.receive("peer", a, catalogB)
        flow.timeout("peer", a)
        assertFalse(flow.ack("peer", a, FluxThemeSelection.Result("expired", true, "two")))
        assertTrue(flow.request("peer", a, catalogB, "one"))
        live = b
        flow.receive("peer", b, catalogB.copy())
        assertFalse(flow.ack("peer", a, FluxThemeSelection.Result("old", true, "one")))
        assertFalse(flow.ack("peer", b, FluxThemeSelection.Result("old", true, "one")))
        jobs.forEach { it() }
        assertTrue(completions.isEmpty())
        assertEquals(listOf("Two"), applied)
    }

    @Test fun revokingNewestPeerReconcilesRemainingLiveDesiredCatalog() {
        val queue = mutableListOf<() -> Unit>()
        val disk = mutableListOf<String>()
        val first = Any()
        val second = Any()
        val flow = FluxThemeFlow<Any>(allowed = { true }, schedule = { queue.add(it) },
            apply = { palette, _ -> disk.add(palette.name); committed() }, select = { _, _ -> true })
        flow.receive("first", first, catalogA)
        queue.removeAt(0)()
        flow.receive("second", second, catalogB)
        queue.removeAt(0)()
        flow.revoke("second", second)
        assertTrue(queue.isNotEmpty())
        queue.removeAt(0)()
        assertEquals(listOf("One", "Two", "One"), disk)
    }

    private fun committed() = ThemeCommitResult(ThemeCommitStatus.COMMITTED)
    private val a = Any()
    private val b = Any()
    private var live: Any? = a
    private var otherLive: Any? = null
    private var direct = true
    private val jobs = mutableListOf<() -> Unit>()
    private val applied = mutableListOf<String>()
    private val selects = mutableListOf<Pair<Any, String>>()
    private val completions = mutableListOf<Pair<Any, Boolean>>()
    private val catalogA = FluxThemeCatalog("one", listOf(
        FluxThemeCatalog.Theme("one", "One", OmarchyThemePalette("One", OmarchyThemeMode.DARK, mapOf("background" to "#111111"))),
        FluxThemeCatalog.Theme("two", "Two", OmarchyThemePalette("Two", OmarchyThemeMode.DARK, mapOf("background" to "#222222")))))
    private val catalogB = catalogA.copy(current = "two")
    private val flow by lazy { FluxThemeFlow<Any>(
        allowed = { session -> direct && (live === session || otherLive === session) },
        schedule = { jobs.add(it) },
        apply = { palette, _ -> applied.add(palette.name); committed() },
        select = { session, id -> selects.add(session to id); true },
        onApplied = { session, _, success -> completions.add(session to success) },
    ) }

    @Test fun initialAndCurrentChangeApplyButUnchangedCatalogAndReceiveNeverSelect() {
        flow.receive("peer", a, catalogA)
        jobs.removeAt(0)()
        flow.receive("peer", a, catalogA.copy())
        assertTrue(jobs.isEmpty())
        flow.receive("peer", a, catalogB)
        jobs.removeAt(0)()
        assertEquals(listOf("One", "Two"), applied)
        assertTrue(selects.isEmpty())
    }

    @Test fun paletteChangeUnderSameCurrentReappliesWithoutSelectingDesktop() {
        flow.receive("peer", a, catalogA)
        jobs.removeAt(0)()
        val revised = catalogA.copy(themes = catalogA.themes.map { theme ->
            if (theme.id == "one") theme.copy(palette = theme.palette.copy(
                colors = mapOf("background" to "#333333"))) else theme
        })
        flow.receive("peer", a, revised)
        assertEquals(1, jobs.size)
        jobs.removeAt(0)()
        assertEquals(listOf("One", "One"), applied)
        assertTrue(selects.isEmpty())
    }

    @Test fun queuedOldGenerationCannotOverwriteNewerThemeOrReplacement() {
        flow.receive("peer", a, catalogA)
        flow.receive("peer", a, catalogB)
        jobs.removeAt(1)()
        jobs.removeAt(0)()
        assertEquals(listOf("Two"), applied)
        flow.receive("peer", a, catalogA)
        live = b
        flow.revoke("peer", a)
        flow.receive("peer", b, catalogB)
        jobs.removeAt(0)()
        jobs.removeAt(0)()
        assertEquals(listOf("Two", "Two"), applied)
    }

    @Test fun pickerWaitsForAckAndUsesEffectiveCurrentRatherThanRequestedPalette() {
        flow.receive("peer", a, catalogA)
        jobs.clear()
        assertTrue(flow.request("peer", a, catalogA, "two"))
        assertTrue(applied.isEmpty())
        assertEquals(listOf(a to "two"), selects)
        assertTrue(flow.ack("peer", a, FluxThemeSelection.Result("request", true, "one")))
        jobs.removeAt(0)()
        assertEquals(listOf("One"), applied)
        assertEquals(listOf(a to true), completions)
    }

    @Test fun independentPeersDoNotClobberEachOthersPendingSelection() {
        otherLive = b
        flow.receive("peer", a, catalogA)
        flow.receive("second", b, catalogB)
        jobs.clear()
        assertTrue(flow.request("peer", a, catalogA, "one"))
        assertTrue(flow.request("second", b, catalogB, "two"))
        assertTrue(flow.ack("second", b, FluxThemeSelection.Result("b", true, "two")))
        assertTrue(flow.ack("peer", a, FluxThemeSelection.Result("a", true, "one")))
        jobs.forEach { it() }
        // Both peers can select independently; only the newest global desired palette
        // can publish/complete once their queued local applies run.
        assertEquals(listOf(a to true), completions)
    }

    @Test fun expiredQueuedChoiceCannotSendAfterTimeout() {
        flow.receive("peer", a, catalogA)
        jobs.clear()
        var desired = true
        desired = false
        assertFalse(flow.request("peer", a, catalogA, "two") { desired })
        assertTrue(selects.isEmpty())
    }

    @Test fun denialTimeoutAndStaleSessionNeverApplyOrSend() {
        flow.receive("peer", a, catalogA)
        jobs.clear()
        assertTrue(flow.request("peer", a, catalogA, "two"))
        assertFalse(flow.ack("peer", a, FluxThemeSelection.Result("request", false, "one")))
        assertTrue(applied.isEmpty())
        assertTrue(flow.request("peer", a, catalogA, "one"))
        flow.timeout("peer", a)
        assertFalse(flow.ack("peer", a, FluxThemeSelection.Result("request", true, "two")))
        live = b
        assertFalse(flow.request("peer", a, catalogA, "one"))
        direct = false
        assertFalse(flow.request("peer", b, catalogA, "one"))
        assertTrue(applied.isEmpty())
    }
}
