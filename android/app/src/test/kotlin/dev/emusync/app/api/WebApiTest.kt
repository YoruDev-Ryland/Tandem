package dev.emusync.app.api

import dev.emusync.app.model.*
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WebApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun client() = ApiClient(server.url("/").toString().trimEnd('/'), "key123")
    private fun ok(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())
    private fun noContent() = server.enqueue(MockResponse.Builder().code(204).build())

    private fun assertHeaders(r: mockwebserver3.RecordedRequest) {
        assertEquals("1", r.headers["EmuSync-Protocol"])
        assertEquals("Bearer key123", r.headers["Authorization"])
    }

    @Test fun games_filters_by_emulator_and_parses_rows() = runBlocking {
        ok("""[{"emulatorId":"melonds","gameKey":"nds/Pokemon","displayName":"Pokemon","installedOn":["d1"],
            "saveItemId":"i1","newestOnDeviceId":"d1","newestUtc":"2026-01-02T03:04:05+00:00","stateCount":2,
            "openConflict":true,"newestOnDeviceName":"Deck",
            "devices":[{"deviceId":"d1","deviceName":"Deck","state":"Newest"},
                       {"deviceId":"d2","deviceName":"Phone","state":"NotInstalled"}]}]""")
        val rows = client().games("melonds")
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertHeaders(r)
        assertEquals("/v1/web/games?emulator=melonds", r.target)
        assertEquals(1, rows.size)
        assertEquals("nds/Pokemon", rows[0].gameKey)
        assertEquals(true, rows[0].openConflict)
        assertEquals(DeviceGameState.Newest, rows[0].devices[0].state)
        assertEquals(DeviceGameState.NotInstalled, rows[0].devices[1].state)
        assertEquals("Deck", rows[0].newestOnDeviceName)
    }

    @Test fun games_without_emulator_has_no_query() = runBlocking {
        ok("[]")
        assertTrue(client().games(null).isEmpty())
        assertEquals("/v1/web/games", server.takeRequest().target)
    }

    @Test fun game_encodes_key_segments_keeping_slash() = runBlocking {
        ok("""{"row":{"emulatorId":"melonds","gameKey":"k","installedOn":[],"stateCount":0,"openConflict":false,"devices":[]},"items":[
            {"itemId":"i1","kind":"Save","slot":"","core":"","headVersionId":"v1","openConflict":false,"versions":[
              {"id":"v1","seq":3,"deviceName":"Deck","receivedUtc":"2026-01-02T03:04:05+00:00","totalSize":2048,
               "core":"","variant":null,"pinned":true,"conflictReason":"FirstSync","isHead":true,"resolved":false,
               "manifestHash":"abc","fileCount":2}]}]}""")
        val d = client().game("melonds", "nds/Pokemon - Platinum Version (USA)")
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertHeaders(r)
        assertEquals("/v1/web/games/melonds/nds/Pokemon%20-%20Platinum%20Version%20(USA)", r.target)
        val v = d.items[0].versions[0]
        assertEquals(ItemKind.Save, d.items[0].kind)
        assertEquals("abc", v.manifestHash)
        assertEquals(2, v.fileCount)
        assertEquals(ConflictReason.FirstSync, v.conflictReason)
        assertTrue(v.pinned && v.isHead)
    }

    @Test fun game_encodes_reserved_characters_per_segment() = runBlocking {
        ok("""{"row":{"emulatorId":"e","gameKey":"k","installedOn":[],"stateCount":0,"openConflict":false,"devices":[]},"items":[]}""")
        client().game("e", "a b/c#d?e%f/ü")
        assertEquals("/v1/web/games/e/a%20b/c%23d%3Fe%25f/%C3%BC", server.takeRequest().target)
    }

    @Test fun conflicts_parses_sides() = runBlocking {
        ok("""[{"itemId":"i1","emulatorId":"eden","gameKey":"g","displayName":null,"kind":"State","slot":null,"core":null,
            "reason":"Concurrent","sides":[
              {"versionId":"v1","deviceId":"d1","deviceName":"Deck","receivedUtc":"t","totalSize":10,"fileCount":1,"manifestHash":"h1","isHead":true},
              {"versionId":"v2","deviceId":null,"deviceName":null,"receivedUtc":"t","totalSize":11,"fileCount":2,"manifestHash":"h2","isHead":false}]}]""")
        val rows = client().conflicts()
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertHeaders(r)
        assertEquals("/v1/web/conflicts", r.target)
        assertEquals(ConflictReason.Concurrent, rows[0].reason)
        assertEquals(ItemKind.State, rows[0].kind)
        assertNull(rows[0].slot)
        assertTrue(rows[0].sides[0].isHead)
        assertNull(rows[0].sides[1].deviceId)
    }

    @Test fun resolveConflict_posts_choice() = runBlocking {
        noContent()
        client().resolveConflict("i1", ResolveConflictRequest(ResolveChoice.KeepBoth, "v2", "From Deck"))
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertHeaders(r)
        assertEquals("/v1/conflicts/i1/resolve", r.target)
        assertEquals("""{"choice":"KeepBoth","keepVersionId":"v2","variantName":"From Deck"}""", r.body!!.utf8())
    }

    @Test fun firstSync_posts_bulk_request() = runBlocking {
        noContent()
        client().firstSync(BulkFirstSyncRequest("d1", FirstSyncChoice.UseServer, listOf("i1", "i2")))
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertHeaders(r)
        assertEquals("/v1/conflicts/first-sync/resolve", r.target)
        assertEquals("""{"deviceId":"d1","choice":"UseServer","itemIds":["i1","i2"]}""", r.body!!.utf8())
    }

    @Test fun configs_parses_groups_and_solo() = runBlocking {
        ok("""{"emulators":[{"emulatorId":"eden","groups":[{"id":"g1","name":"Handhelds","members":[{"id":"d1","name":"Deck"}],
            "headVersionId":"v1","openConflict":false}],
            "solo":[{"device":{"id":"d2","name":"Phone"},"bundleId":"b1","headVersionId":null,"openConflict":true}]}]}""")
        val o = client().configs()
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertHeaders(r)
        assertEquals("/v1/web/configs", r.target)
        assertEquals("Handhelds", o.emulators[0].groups[0].name)
        assertEquals("Deck", o.emulators[0].groups[0].members[0].name)
        assertEquals("Phone", o.emulators[0].solo[0].device.name)
        assertTrue(o.emulators[0].solo[0].openConflict)
    }

    @Test fun joinGroup_posts_device_id() = runBlocking {
        ok("""{"id":"g1","emulatorId":"eden","name":"Handhelds","bundleId":"b"}""")
        client().joinGroup("g1", "d1")
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertHeaders(r)
        assertEquals("/v1/configs/groups/g1/members", r.target)
        assertEquals("""{"deviceId":"d1"}""", r.body!!.utf8())
    }

    @Test fun applyConfig_posts_and_parses_response() = runBlocking {
        ok("""{"bundleId":"b1","headVersionId":"v9","groupId":null}""")
        val resp = client().applyConfig(ApplyConfigRequest("d1", "eden", "v1", ApplyMode.Copy))
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertHeaders(r)
        assertEquals("/v1/configs/apply", r.target)
        assertEquals("""{"targetDeviceId":"d1","emulatorId":"eden","sourceVersionId":"v1","mode":"Copy"}""", r.body!!.utf8())
        assertEquals("v9", resp.headVersionId)
        assertNull(resp.groupId)
    }

    @Test fun forget_posts_the_emulator_ids() = runBlocking {
        noContent()
        client().forget(listOf("eden", "ppsspp"))
        val r = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) ?: error("no request sent")
        assertEquals("POST", r.method); assertHeaders(r)
        assertEquals("/v1/devices/me/forget", r.target)
        assertEquals("""{"emulatorIds":["eden","ppsspp"]}""", r.body!!.utf8())
    }

    @Test fun keepOwn_posts_to_device_setup() = runBlocking {
        noContent()
        client().keepOwn("d1", "eden")
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertHeaders(r)
        assertEquals("/v1/web/devices/d1/setup/keep-own/eden", r.target)
    }

    @Test fun setup_parses_steps() = runBlocking {
        ok("""{"steps":[{"kind":"GrantFolders","emulatorId":"eden","label":"Grant folders","done":false,"detail":"2 left"},
            {"kind":"ChooseConfig","emulatorId":"eden","label":"Choose a config","done":true,"detail":null}]}""")
        val c = client().setup("d1")
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertHeaders(r)
        assertEquals("/v1/web/devices/d1/setup", r.target)
        assertEquals(SetupStepKind.GrantFolders, c.steps[0].kind)
        assertEquals("2 left", c.steps[0].detail)
        assertTrue(c.steps[1].done)
    }

    @Test fun activity_sends_filters_and_parses_page() = runBlocking {
        ok("""{"items":[{"id":42,"utc":"t","kind":"upload","message":"Deck saved Pokemon","deviceId":"d1","deviceName":"Deck",
            "emulatorId":"melonds","gameKey":"nds/Pokemon"}],"nextBefore":42}""")
        val p = client().activity("d1", 100L, 25)
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertHeaders(r)
        assertEquals("/v1/activity?device=d1&before=100&limit=25", r.target)
        assertEquals(42L, p.items[0].id)
        assertEquals("Deck", p.items[0].deviceName)
        assertEquals(42L, p.nextBefore)
    }

    @Test fun activity_defaults_to_limit_50_and_last_page_has_no_cursor() = runBlocking {
        ok("""{"items":[],"nextBefore":null}""")
        val p = client().activity(null, null)
        assertEquals("/v1/activity?limit=50", server.takeRequest().target)
        assertNull(p.nextBefore)
    }

    @Test fun devices_parses_summaries() = runBlocking {
        ok("""[{"id":"d1","name":"Deck","platform":"android","lastSeenUtc":"t","shizukuOk":true,"isCurrent":true,
            "runningEmulators":["melonds"]},{"id":"d2","name":"PC","platform":"windows","lastSeenUtc":null,"shizukuOk":null,
            "isCurrent":false,"runningEmulators":[]}]""")
        val ds = client().devices()
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertHeaders(r)
        assertEquals("/v1/devices", r.target)
        assertEquals(listOf("melonds"), ds[0].runningEmulators)
        assertEquals(true, ds[0].shizukuOk)
        assertNull(ds[1].shizukuOk)
    }

    @Test fun me_parses_current_device() = runBlocking {
        ok("""{"id":"d1","name":"Deck","platform":"android","lastSeenUtc":null,"shizukuOk":false,"isCurrent":true,"runningEmulators":[]}""")
        val d = client().me()
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertHeaders(r)
        assertEquals("/v1/devices/me", r.target)
        assertTrue(d.isCurrent)
    }

    @Test fun conflict_open_409_maps_to_ApiException_code() = runBlocking {
        server.enqueue(MockResponse.Builder().code(409).body("""{"code":"conflict_open","message":"Resolve the conflict first."}""").build())
        try {
            client().applyConfig(ApplyConfigRequest("d1", "eden", "v1", ApplyMode.Linked))
            fail("expected")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
            assertEquals("conflict_open", e.code)
            assertEquals("Resolve the conflict first.", e.message)
        }
        Unit
    }

    @Test fun payload_missing_a_required_field_fails_instead_of_defaulting() = runBlocking {
        // "openConflict" is required on the server; a rename must not decode silently as false.
        ok("""[{"emulatorId":"e","gameKey":"k","installedOn":[],"stateCount":0,"devices":[]}]""")
        try {
            client().games(null)
            fail("expected a SerializationException")
        } catch (e: kotlinx.serialization.SerializationException) {
            assertTrue(e.message, e.message!!.contains("openConflict"))
        }
        Unit
    }
}
