package dev.tandem.app.ui.screens

import dev.tandem.app.api.Api
import dev.tandem.app.api.ApiException
import dev.tandem.app.model.ActivityItem
import dev.tandem.app.model.ActivityPage
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")

    private fun item(id: Long, at: String = "2026-11-20T11:55:00+00:00", msg: String = "m$id", kind: String = "upload") =
        ActivityItem(id, at, kind, msg)

    private class Fake {
        val calls = mutableListOf<Pair<String?, Long?>>()
        var pages: (String?, Long?) -> ActivityPage = { _, _ -> ActivityPage(emptyList()) }
        var gate: CompletableDeferred<Unit>? = null
    }

    private val unused: Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, m, _ ->
        error("unexpected " + m.name)
    } as Api

    private fun api(f: Fake): Api = object : Api by unused {
        override suspend fun activity(device: String?, before: Long?, limit: Int): ActivityPage {
            f.calls += device to before
            f.gate?.await()
            return f.pages(device, before)
        }
    }

    private fun TestScope.vm(f: Fake, signedIn: Boolean = true, nudges: MutableStateFlow<Long> = MutableStateFlow(0)) =
        ActivityViewModel({ if (signedIn) api(f) else null }, { "dev-thor" }, backgroundScope, nudges, { now }, { ZoneOffset.UTC })

    @Test fun the_server_query_passes_this_device() = runTest {
        val f = Fake().apply { pages = { _, _ -> ActivityPage(listOf(item(3), item(2)), nextBefore = 2) } }
        val vm = vm(f); vm.refresh(); runCurrent()
        assertEquals(listOf<Pair<String?, Long?>>("dev-thor" to null), f.calls)
        assertEquals(listOf(3L, 2L), vm.ui.value.entries.map { it.id })
        assertTrue(vm.ui.value.hasOlder)
    }

    @Test fun load_older_passes_before_and_appends_without_duplicates() = runTest {
        val f = Fake().apply {
            pages = { _, before ->
                if (before == null) ActivityPage(listOf(item(4), item(3)), nextBefore = 3)
                else ActivityPage(listOf(item(3), item(2), item(1)), nextBefore = null)
            }
        }
        val vm = vm(f); vm.refresh(); runCurrent()
        vm.loadOlder(); runCurrent()
        assertEquals("dev-thor" to 3L, f.calls.last())
        assertEquals(listOf(4L, 3L, 2L, 1L), vm.ui.value.entries.map { it.id })
        assertFalse(vm.ui.value.hasOlder)
    }

    @Test fun a_failed_older_page_keeps_the_rows_and_says_so() = runTest {
        val f = Fake().apply {
            pages = { _, before -> if (before == null) ActivityPage(listOf(item(4)), nextBefore = 4) else throw IOException("down") }
        }
        val vm = vm(f); vm.refresh(); runCurrent()
        vm.loadOlder(); runCurrent()
        val ui = vm.ui.value
        assertEquals(listOf(4L), ui.entries.map { it.id })
        assertTrue(ui.olderError); assertTrue(ui.hasOlder); assertFalse(ui.loadingOlder)
        vm.loadOlder(); runCurrent()   // trying again clears the error while it loads
        assertEquals(3, f.calls.size)
    }

    @Test fun loading_older_twice_asks_once() = runTest {
        val f = Fake().apply { pages = { _, _ -> ActivityPage(listOf(item(4)), nextBefore = 4) } }
        val vm = vm(f); vm.refresh(); runCurrent()
        f.gate = CompletableDeferred()
        vm.loadOlder(); vm.loadOlder(); runCurrent()
        assertEquals(2, f.calls.size)
        assertTrue(vm.ui.value.loadingOlder)
        f.gate!!.complete(Unit)
    }

    @Test fun new_items_are_prepended_and_older_pages_stay() = runTest {
        var newest = listOf(item(4), item(3))
        val f = Fake().apply {
            pages = { _, before -> if (before == null) ActivityPage(newest, nextBefore = 3) else ActivityPage(listOf(item(2), item(1))) }
        }
        val vm = vm(f); vm.refresh(); runCurrent()
        vm.loadOlder(); runCurrent()
        newest = listOf(item(6), item(5), item(4), item(3))
        vm.refresh(); runCurrent()
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L), vm.ui.value.entries.map { it.id })
        assertFalse(vm.ui.value.hasOlder)   // the cursor from the older page is kept
    }

    @Test fun more_new_than_a_page_holds_starts_over() = runTest {
        var newest = listOf(item(4), item(3))
        val f = Fake().apply { pages = { _, _ -> ActivityPage(newest, nextBefore = 3) } }
        val vm = vm(f); vm.refresh(); runCurrent()
        newest = listOf(item(9), item(8))
        vm.refresh(); runCurrent()
        assertEquals(listOf(9L, 8L), vm.ui.value.entries.map { it.id })
    }

    @Test fun a_stale_response_after_sign_out_is_dropped() = runTest {
        val f = Fake().apply { pages = { _, _ -> ActivityPage(listOf(item(1))) }; gate = CompletableDeferred() }
        var key = true
        val vm = ActivityViewModel({ if (key) api(f) else null }, { "dev-thor" }, backgroundScope, MutableStateFlow(0L), { now }, { ZoneOffset.UTC })
        vm.refresh(); runCurrent()
        key = false; vm.refresh()
        f.gate!!.complete(Unit); runCurrent()
        assertTrue(vm.ui.value.entries.isEmpty()); assertFalse(vm.ui.value.signedIn)
    }

    @Test fun only_the_latest_refresh_lands() = runTest {
        var round = 0
        val f = Fake().apply { pages = { _, _ -> round += 1; ActivityPage(listOf(item(round.toLong()))) }; gate = CompletableDeferred() }
        val vm = vm(f); vm.refresh(); runCurrent(); vm.refresh(); runCurrent()
        f.gate!!.complete(Unit); runCurrent()
        assertEquals(2, f.calls.size)
        assertEquals(1, vm.ui.value.entries.size)
    }

    @Test fun offline_keeps_the_page_and_names_when_it_was_current() = runTest {
        var down = false
        val f = Fake().apply { pages = { _, _ -> if (down) throw IOException("x") else ActivityPage(listOf(item(1))) } }
        val vm = vm(f); vm.refresh(); runCurrent()
        down = true; vm.refresh(); runCurrent()
        val ui = vm.ui.value
        assertEquals(1, ui.entries.size)
        assertEquals("Offline · last updated 12:00", ui.offlineNote)
        down = false; vm.refresh(); runCurrent()
        assertNull(vm.ui.value.offlineNote)
    }

    @Test fun an_empty_cache_offline_is_an_error_not_a_spinner() = runTest {
        val f = Fake().apply { pages = { _, _ -> throw IOException("x") } }
        val vm = vm(f); vm.refresh(); runCurrent()
        assertTrue(vm.ui.value.loaded); assertTrue(vm.ui.value.error != null)
    }

    @Test fun an_http_error_is_not_offline() = runTest {
        val f = Fake().apply { pages = { _, _ -> throw ApiException(500, "boom", "x") } }
        val vm = vm(f); vm.refresh(); runCurrent()
        assertNull(vm.ui.value.offlineNote); assertTrue(vm.ui.value.error != null)
    }

    @Test fun a_delivery_nudge_refreshes_only_while_shown() = runTest {
        val nudges = MutableStateFlow(0L)
        val f = Fake().apply { pages = { _, _ -> ActivityPage(listOf(item(1))) } }
        val vm = vm(f, nudges = nudges); runCurrent()
        nudges.value = 1; runCurrent()
        assertEquals(0, f.calls.size)
        vm.show(); runCurrent()
        assertEquals(1, f.calls.size)
        nudges.value = 2; runCurrent()
        assertEquals(2, f.calls.size)
        vm.hide(); nudges.value = 3; runCurrent()
        assertEquals(2, f.calls.size)
    }

    @Test fun signed_out_shows_no_activity() = runTest {
        val f = Fake()
        val vm = vm(f, signedIn = false); vm.refresh(); runCurrent()
        assertFalse(vm.ui.value.signedIn); assertEquals(0, f.calls.size)
    }

    @Test fun times_are_relative_under_a_day_and_dated_after() {
        val z = ZoneOffset.UTC
        fun t(iso: String) = activityTime(iso, now, z)
        assertEquals("just now", t("2026-11-20T11:59:40+00:00"))
        assertEquals("5 min ago", t("2026-11-20T11:54:30.1234567+00:00"))
        assertEquals("3 h ago", t("2026-11-20T09:00:00+00:00"))
        assertEquals("Nov 19, 11:14", t("2026-11-19T11:14:00+00:00"))
        assertEquals("just now", t("2026-11-20T12:05:00+00:00"))   // clock skew
        assertEquals("", t("garbage"))
    }

    @Test fun only_an_opened_conflict_takes_the_fault_tone() = runTest {
        val kinds = listOf("save_uploaded", "config_uploaded", "vault_uploaded", "device_paired", "conflict_opened", "conflict_resolved")
        val f = Fake().apply { pages = { _, _ -> ActivityPage(kinds.mapIndexed { i, k -> item(10L - i, kind = k) }) } }
        val vm = vm(f); vm.refresh(); runCurrent()
        assertEquals(listOf(false, false, false, false, true, false), vm.ui.value.entries.map { it.fault })
    }
}
