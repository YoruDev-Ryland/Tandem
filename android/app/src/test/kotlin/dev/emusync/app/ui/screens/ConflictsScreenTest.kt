package dev.emusync.app.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.emusync.app.api.Api
import dev.emusync.app.model.BulkFirstSyncRequest
import dev.emusync.app.model.ConflictReason
import dev.emusync.app.model.ConflictRow
import dev.emusync.app.model.ConflictSide
import dev.emusync.app.model.FirstSyncChoice
import dev.emusync.app.model.ItemKind
import dev.emusync.app.model.ResolveChoice
import dev.emusync.app.model.ResolveConflictRequest
import dev.emusync.app.ui.kit.key
import dev.emusync.app.ui.state.Loaded
import dev.emusync.app.ui.theme.EmuTheme
import dev.emusync.app.ui.theme.ThemeMode
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val THOR = "dev-thor"
private const val ODIN = "dev-odin"
private const val DECK = "dev-deck"

internal fun side(device: String?, name: String?, head: Boolean, version: String = "v-$device", received: String = "2026-11-19T21:48:00Z") =
    ConflictSide(version, device, name, received, totalSize = 1_048_576, fileCount = 3, manifestHash = "abcdef0123456789", isHead = head)

/** A conflict between this Thor and the Odin 3; the Odin's save is the server's head unless [thorHead]. */
internal fun conflict(
    item: String,
    game: String,
    reason: ConflictReason = ConflictReason.FirstSync,
    thorHead: Boolean = false,
    emu: String = "eden",
    sides: List<ConflictSide> = listOf(
        side(THOR, "Thor", thorHead, version = "v-$item-thor", received = "2026-11-19T21:48:00Z"),
        side(ODIN, "Odin 3", !thorHead, version = "v-$item-odin", received = "2026-11-17T10:12:00Z"),
    ),
) = ConflictRow(item, emu, "key-$item", game, ItemKind.Save, null, null, reason, sides)

private val names = mapOf("eden" to "Eden", "azahar" to "Azahar")

/** What the fake server was asked, in order. */
private class Calls {
    val resolved = mutableListOf<Pair<String, ResolveConflictRequest>>()
    val firstSync = mutableListOf<BulkFirstSyncRequest>()
    var gate: CompletableDeferred<Unit>? = null
}

private val unused: Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, m, _ ->
    throw UnsupportedOperationException(m.name)
} as Api

private fun fakeApi(calls: Calls): Api = object : Api by unused {
    override suspend fun resolveConflict(itemId: String, req: ResolveConflictRequest) {
        calls.resolved += itemId to req
        calls.gate?.await()
    }
    override suspend fun firstSync(req: BulkFirstSyncRequest) { calls.firstSync += req }
}

private fun ui(rows: List<ConflictRow>, running: Set<String> = emptySet()) =
    conflictsUi(Loaded(rows, Instant.EPOCH), THOR, running, { names[it] ?: it }, signedIn = true, zone = ZoneOffset.UTC)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class ConflictsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = Calls()
    private var applied = 0
    private val rows = MutableStateFlow(Loaded<List<ConflictRow>>())
    private val running = MutableStateFlow<Set<String>>(emptySet())
    /** The screen's clock, in ms; tests move it by hand. */
    private var clock = 10_000L

    /** The tab as the app wires it: the model over a fake server, the screen over the model. */
    private fun showLive(list: List<ConflictRow>) {
        rows.value = Loaded(list, Instant.EPOCH)
        val vm = ConflictsViewModel(
            conflicts = rows, running = running, signedIn = MutableStateFlow(true), deviceId = { THOR },
            api = { fakeApi(calls) }, scope = CoroutineScope(Dispatchers.Unconfined),
            afterResolve = { applied++ }, emulatorName = { names[it] ?: it }, zone = ZoneOffset.UTC,
        )
        compose.setContent {
            val state by vm.ui.collectAsState()
            EmuTheme(ThemeMode.Dark) {
                ConflictsScreen(state, top = {}, actions = ConflictsActions(resolve = vm::resolve, bulk = vm::bulk), now = { clock })
            }
        }
    }

    private fun show(ui: ConflictsUi, actions: ConflictsActions) {
        compose.setContent { EmuTheme(ThemeMode.Dark) { ConflictsScreen(ui, top = {}, actions = actions, now = { clock }) } }
    }

    @Test fun aKeepsThisDevicesSideThenAppliesAndRefreshes() {
        showLive(listOf(conflict("i1", "Pokémon Brilliant Diamond")))
        compose.onNodeWithText("Keep this Thor's").assertExists()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("i1" to ResolveConflictRequest(ResolveChoice.KeepVersion, "v-i1-thor", null)), calls.resolved)
        assertEquals(1, applied)
    }

    @Test fun aStillKeepsThisDevicesSideWhenTheListArrivesAfterTheScreen() {
        val sent = mutableListOf<String>()
        var current by mutableStateOf(ui(emptyList()))
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                ConflictsScreen(current, top = {}, actions = ConflictsActions(resolve = { _, r -> sent += r.keepVersionId.orEmpty() }))
            }
        }
        current = ui(listOf(conflict("i1", "Pokémon Brilliant Diamond")))
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("v-i1-thor"), sent)
    }

    @Test fun tappingTheCursorRowDecidesNothing() {
        val sent = mutableListOf<String>()
        show(ui(listOf(conflict("i1", "Pokémon Brilliant Diamond"))), ConflictsActions(resolve = { id, _ -> sent += id }))
        compose.onNodeWithText("Pokémon Brilliant Diamond").performClick()
        compose.onNodeWithText("Pokémon Brilliant Diamond").performClick()
        assertTrue(sent.isEmpty())
    }

    @Test fun xKeepsTheOtherDevicesSide() {
        showLive(listOf(conflict("i1", "Pokémon Brilliant Diamond")))
        compose.onNodeWithText("Keep Odin 3's").assertExists()
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(listOf("i1" to ResolveConflictRequest(ResolveChoice.KeepVersion, "v-i1-odin", null)), calls.resolved)
    }

    @Test fun yKeepsBothWithTheOtherDeviceAsTheVariantName() {
        showLive(listOf(conflict("i1", "Pokémon Brilliant Diamond")))
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(listOf("i1" to ResolveConflictRequest(ResolveChoice.KeepBoth, "v-i1-thor", "From Odin 3")), calls.resolved)
    }

    @Test fun keepBothWithThreeSidesLetsTheServerNameEachCopy() {
        val three = conflict("i1", "Mario Kart", sides = listOf(side(THOR, "Thor", false), side(ODIN, "Odin 3", true), side(DECK, "Deck", false)))
        assertEquals(ResolveConflictRequest(ResolveChoice.KeepBoth, "v-$THOR", null), ui(listOf(three)).entries.single().keepBoth)
    }

    @Test fun aSecondAWhileTheFirstIsInFlightSendsNothing() {
        calls.gate = CompletableDeferred()
        showLive(listOf(conflict("i1", "Pokémon Brilliant Diamond")))
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        calls.gate!!.complete(Unit)
        compose.waitForIdle()
        assertEquals(1, calls.resolved.size)
        assertEquals(1, applied)
    }

    @Test fun aResolvedConflictIsHiddenUntilTheServerAgrees() {
        showLive(listOf(conflict("i1", "Pokémon Brilliant Diamond"), conflict("i2", "Pokémon Pearl")))
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        // The refresh has not landed yet; the next A must not resolve the same conflict again.
        compose.onNodeWithText("Pokémon Brilliant Diamond").assertDoesNotExist()
        clock += 500   // the next game has been on screen long enough to have been seen
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("i1", "i2"), calls.resolved.map { it.first })
    }

    @Test fun aSecondQuickAAfterADecisionDoesNotDecideTheNextGameUnseen() {
        showLive(listOf(conflict("i1", "Pokémon Brilliant Diamond"), conflict("i2", "Pokémon Pearl")))
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // resolves at once; Pearl takes the cursor
        clock += 499
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(listOf("i1"), calls.resolved.map { it.first })
        clock += 1
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("i1", "i2"), calls.resolved.map { it.first })
    }

    @Test fun movingTheCursorYourselfNeedsNoWait() {
        showLive(listOf(conflict("i1", "A"), conflict("i2", "B"), conflict("i3", "C")))
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        clock += 600
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)   // a cursor the user moves is a game they are looking at
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("i1", "i3"), calls.resolved.map { it.first })
    }

    @Test fun theCursorStaysOnItsGameWhenTheListReorders() {
        val sent = mutableListOf<String>()
        var current by mutableStateOf(ui(listOf(conflict("i1", "A"), conflict("i2", "B"))))
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                ConflictsScreen(current, top = {}, actions = ConflictsActions(resolve = { id, _ -> sent += id }), now = { clock })
            }
        }
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        current = ui(listOf(conflict("i0", "Z"), conflict("i2", "B"), conflict("i1", "A")))
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("i2"), sent)
    }

    @Test fun aDeviceWithSeveralOpenVersionsIsOneSideWithItsNewest() {
        val r = conflict("i1", "Pokémon Brilliant Diamond", sides = listOf(
            side(THOR, "Thor", false, version = "thor-old", received = "2026-11-18T08:00:00+00:00"),
            side(ODIN, "Odin 3", true, version = "odin-old", received = "2026-11-17T10:12:00+00:00"),
            side(THOR, "Thor", false, version = "thor-new", received = "2026-11-19T21:48:00+00:00"),
            side(ODIN, "Odin 3", false, version = "odin-new", received = "2026-11-19T22:00:00+00:00"),
        ))
        val e = ui(listOf(r)).entries.single()
        assertEquals("thor-new", e.keepMine.keepVersionId)
        assertEquals("odin-new", e.keepOther.keepVersionId)
        assertEquals(ResolveConflictRequest(ResolveChoice.KeepBoth, "thor-new", "From Odin 3"), e.keepBoth)
        assertEquals("This Thor and the Odin 3 each had a save before they first synced. Both are safe on the server.", e.subtitle)
        assertEquals(listOf("This Thor", "Odin 3"), e.columns.map { it.title })
        assertEquals("Thu, Nov 19, 21:48", e.columns[0].received)
    }

    /** A conflict against this device's own older upload (the head), with two newer uploads of its own open. */
    private val selfOnly = conflict("i1", "Pokémon Brilliant Diamond", ConflictReason.Concurrent, sides = listOf(
        side(THOR, "Thor", true, version = "head", received = "2026-11-17T10:12:00+00:00"),
        side(THOR, "Thor", false, version = "mid", received = "2026-11-18T08:00:00+00:00"),
        side(THOR, "Thor", false, version = "newest", received = "2026-11-19T21:48:00+00:00"),
    ))

    @Test fun aConflictWithOnlyThisDevicesOwnSavesIsListed() {
        val e = ui(listOf(selfOnly)).entries.single()
        assertEquals("This Thor has two different saves for this game. Both are safe on the server.", e.subtitle)
        assertEquals(listOf("This Thor", "Earlier save"), e.columns.map { it.title })
        assertEquals(listOf("Thu, Nov 19, 21:48", "Tue, Nov 17, 10:12"), e.columns.map { it.received })
        assertEquals(null, e.otherId)
    }

    @Test fun aSelfOnlyConflictIsDecidedWithTheButtons() {
        val sent = mutableListOf<ResolveConflictRequest>()
        show(ui(listOf(selfOnly)), ConflictsActions(resolve = { _, r -> sent += r }))
        compose.onNodeWithText("Keep the earlier save").assertExists()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(
            listOf(
                ResolveConflictRequest(ResolveChoice.KeepVersion, "newest", null),
                ResolveConflictRequest(ResolveChoice.KeepVersion, "head", null),
                ResolveConflictRequest(ResolveChoice.KeepBoth, "newest", null),
            ),
            sent,
        )
    }

    @Test fun bulkPlanSettlesTheHeadDevicesOwnOlderOpenVersions() {
        // Thor's newest is the head, but an older Thor upload is still open in the conflict, as is Odin's.
        val r = conflict("i1", "A", sides = listOf(
            side(THOR, "Thor", false, version = "thor-old", received = "2026-11-17T08:00:00+00:00"),
            side(THOR, "Thor", true, version = "thor-head", received = "2026-11-19T21:48:00+00:00"),
            side(ODIN, "Odin 3", false, version = "odin", received = "2026-11-18T10:12:00+00:00"),
        ))
        assertEquals(
            listOf(
                BulkFirstSyncRequest(THOR, FirstSyncChoice.UseServer, listOf("i1")),
                BulkFirstSyncRequest(ODIN, FirstSyncChoice.UseServer, listOf("i1")),
            ),
            bulkPlan(listOf(r), THOR),
        )
    }

    @Test fun bulkPlanNamesEachDeviceAndItemOnceWhenADeviceHasSeveralVersions() {
        val r = conflict("i1", "A", sides = listOf(
            side(THOR, "Thor", false, version = "thor-old", received = "2026-11-18T08:00:00+00:00"),
            side(ODIN, "Odin 3", true, version = "odin-head", received = "2026-11-17T10:12:00+00:00"),
            side(THOR, "Thor", false, version = "thor-new", received = "2026-11-19T21:48:00+00:00"),
            side(ODIN, "Odin 3", false, version = "odin-new", received = "2026-11-19T22:00:00+00:00"),
        ))
        assertEquals(
            listOf(
                BulkFirstSyncRequest(THOR, FirstSyncChoice.UseDevice, listOf("i1")),
                BulkFirstSyncRequest(ODIN, FirstSyncChoice.UseServer, listOf("i1")),
            ),
            bulkPlan(listOf(r), THOR),
        )
        assertEquals(
            listOf(
                BulkFirstSyncRequest(ODIN, FirstSyncChoice.UseDevice, listOf("i1")),
                BulkFirstSyncRequest(THOR, FirstSyncChoice.UseServer, listOf("i1")),
            ),
            bulkPlan(listOf(r), ODIN),
        )
    }

    @Test fun showsTheMockupCopyForAFirstSyncConflict() {
        show(ui(listOf(conflict("i1", "Pokémon Brilliant Diamond"))), ConflictsActions())
        compose.onNodeWithText("Two different saves").assertExists()
        compose.onNodeWithText("This Thor and the Odin 3 each had a save before they first synced. Both are safe on the server.").assertExists()
        compose.onNodeWithText("This Thor").assertExists()
        compose.onNodeWithText("Odin 3").assertExists()
        compose.onNodeWithText("Thu, Nov 19, 21:48").assertExists()
        compose.onNodeWithText("Tue, Nov 17, 10:12").assertExists()
        compose.onAllNodesWithText("1.0 MB · 3 files").let { assertEquals(2, it.fetchSemanticsNodes().size) }
        compose.onNodeWithText("Whatever you choose, the other copy stays in history.").assertExists()
        compose.onNodeWithText("Applied right away").assertExists()
    }

    @Test fun aConcurrentConflictSaysBothDevicesChangedIt() {
        show(ui(listOf(conflict("i1", "Pokémon Brilliant Diamond", ConflictReason.Concurrent))), ConflictsActions())
        compose.onNodeWithText("Both devices changed this save before syncing.").assertExists()
    }

    @Test fun aRunningEmulatorDefersTheChoice() {
        show(ui(listOf(conflict("i1", "Pokémon Brilliant Diamond")), running = setOf("eden")), ConflictsActions())
        compose.onNodeWithText("Eden is open: your choice is applied when it closes").assertExists()
    }

    @Test fun onlyConflictsInvolvingThisDeviceAreListed() {
        val elsewhere = conflict("i9", "Metroid", sides = listOf(side(ODIN, "Odin 3", true), side(DECK, "Deck", false)))
        val u = ui(listOf(conflict("i1", "Pokémon Brilliant Diamond"), elsewhere))
        assertEquals(listOf("i1"), u.entries.map { it.itemId })
        show(u, ConflictsActions())
        compose.onNodeWithText("Metroid").assertDoesNotExist()
    }

    @Test fun nothingToDecide() {
        show(ui(emptyList()), ConflictsActions())
        compose.onNodeWithText("Nothing to decide.").assertExists()
        compose.onNodeWithText("Keep both").assertDoesNotExist()
    }

    @Test fun bulkNeedsTwoFirstSyncConflicts() {
        val one = ui(listOf(conflict("i1", "A"), conflict("i2", "B", ConflictReason.Concurrent)))
        show(one, ConflictsActions())
        compose.onNodeWithText("Use this device's for all").assertDoesNotExist()
    }

    @Test fun l2ConfirmsThenUsesThisDevicesSavesForAll() {
        showLive(listOf(conflict("i1", "A"), conflict("i2", "B")))
        compose.onNodeWithText("Use this device's for all").assertExists()
        compose.key(KeyEvent.KEYCODE_BUTTON_L2)
        assertTrue(calls.firstSync.isEmpty())
        compose.onNodeWithText("Use this Thor's saves for 2 games?").assertExists()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(
            listOf(BulkFirstSyncRequest(THOR, FirstSyncChoice.UseDevice, listOf("i1", "i2"))),
            calls.firstSync,
        )
        assertTrue(calls.resolved.isEmpty())
        assertEquals(1, applied)
    }

    @Test fun bOnTheSheetCancelsAndTheSheetTakesEveryButton() {
        showLive(listOf(conflict("i1", "A"), conflict("i2", "B")))
        compose.key(KeyEvent.KEYCODE_BUTTON_L2)
        compose.key(KeyEvent.KEYCODE_BUTTON_X)   // no single-game choice while the sheet is open
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("Use this Thor's saves for 2 games?").assertDoesNotExist()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // back on the screen: A is a single choice again
        assertTrue(calls.firstSync.isEmpty())
        assertEquals(listOf("i1"), calls.resolved.map { it.first })
    }

    @Test fun r2UsesTheOtherDevicesSavesForAll() {
        showLive(listOf(conflict("i1", "A"), conflict("i2", "B", thorHead = true)))
        compose.onNodeWithText("Use Odin 3's for all").performClick()
        compose.onNodeWithText("Use Odin 3's saves for 2 games?").assertExists()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(
            listOf(
                BulkFirstSyncRequest(ODIN, FirstSyncChoice.UseDevice, listOf("i2")),
                BulkFirstSyncRequest(THOR, FirstSyncChoice.UseServer, listOf("i1")),
            ),
            calls.firstSync,
        )
    }

    @Test fun theConfirmSheetSendsWhatItShowedEvenIfTheListRefreshes() {
        val sent = mutableListOf<List<BulkFirstSyncRequest>>()
        var current by mutableStateOf(ui(listOf(conflict("i1", "A"), conflict("i2", "B"))))
        compose.setContent {
            EmuTheme(ThemeMode.Dark) { ConflictsScreen(current, top = {}, actions = ConflictsActions(bulk = { sent += it })) }
        }
        compose.key(KeyEvent.KEYCODE_BUTTON_L2)
        current = ui(listOf(conflict("i1", "A"), conflict("i2", "B"), conflict("i3", "C")))
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, sent.size)
        assertEquals(listOf("i1", "i2"), sent.single().first().itemIds.orEmpty())
    }

    @Test fun bulkPlanForTheHeadDeviceSetsTheOthersAside() {
        val rows = listOf(conflict("i1", "A", thorHead = true), conflict("i2", "B", thorHead = false))
        assertEquals(
            listOf(
                BulkFirstSyncRequest(THOR, FirstSyncChoice.UseDevice, listOf("i2")),
                BulkFirstSyncRequest(ODIN, FirstSyncChoice.UseServer, listOf("i1")),
            ),
            bulkPlan(rows, THOR),
        )
        assertEquals(
            listOf(BulkFirstSyncRequest(ODIN, FirstSyncChoice.UseServer, listOf("i1"))),
            bulkPlan(rows.take(1), THOR),
        )
    }

    @Test fun bulkPlanSkipsConcurrentConflicts() {
        val rows = listOf(conflict("i1", "A"), conflict("i2", "B", ConflictReason.Concurrent))
        assertEquals(
            listOf(BulkFirstSyncRequest(THOR, FirstSyncChoice.UseDevice, listOf("i1"))),
            bulkPlan(rows, THOR),
        )
    }

    @Test fun bulkPlanWithThreeSidesSetsAsideEveryOtherNonHeadSide() {
        val three = conflict("i1", "A", sides = listOf(side(THOR, "Thor", false), side(ODIN, "Odin 3", true), side(DECK, "Deck", false)))
        assertEquals(
            listOf(
                BulkFirstSyncRequest(THOR, FirstSyncChoice.UseDevice, listOf("i1")),
                BulkFirstSyncRequest(DECK, FirstSyncChoice.UseServer, listOf("i1")),
            ),
            bulkPlan(listOf(three), THOR),
        )
    }
}
