package dev.tandem.app.ui.setup

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import dev.tandem.app.api.Api
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.model.ConfigOverview
import dev.tandem.app.model.ConflictReason
import dev.tandem.app.model.ConflictRow
import dev.tandem.app.model.ConflictSide
import dev.tandem.app.model.DeviceRef
import dev.tandem.app.model.EmulatorConfigRow
import dev.tandem.app.model.GroupRow
import dev.tandem.app.model.ItemKind
import dev.tandem.app.model.SoloRow
import dev.tandem.app.modules.Detected
import dev.tandem.app.modules.ModuleRegistry
import dev.tandem.app.service.EmulatorInfo
import dev.tandem.app.service.ServiceSnapshot
import dev.tandem.app.sync.StateStore
import dev.tandem.app.ui.screens.SettingsViewModel
import dev.tandem.app.ui.state.ServerConn
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.kit.Chrome
import dev.tandem.app.ui.theme.ThemeMode
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import dev.tandem.app.ui.screens.DEVICE_SIZE
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Setup steps 1 (how to sign in), 4 (RetroArch, as in the mockup) and 5 (the first-sync review) in both themes. With
 * TANDEM_SHOT_DIR set it writes setup-{signin,sync,first}-{dark,light}.png there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class SetupScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun stop() = scope.cancel()

    private val modules = ModuleRegistry.default().all()
    private val unused: Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as Api

    private fun side(device: String, name: String, head: Boolean, received: String) =
        ConflictSide("v-$device", device, name, received, 1_048_576, 3, "abc", head)

    private val server = object : Api by unused {
        override suspend fun configs() = ConfigOverview(listOf(
            EmulatorConfigRow(
                "eden",
                groups = listOf(GroupRow("g1", "AYN handhelds", listOf(DeviceRef("dev-thor", "Thor")), "gv", false)),
                solo = listOf(SoloRow(DeviceRef("dev-me", "Odin 3"), "b0", "mv", false), SoloRow(DeviceRef("dev-thor2", "Thor"), "b1", "tv", false)),
            ),
            EmulatorConfigRow(
                "retroarch",
                groups = listOf(GroupRow("g2", "AYN handhelds", listOf(DeviceRef("dev-thor", "Thor")), "gv", false)),
                solo = listOf(SoloRow(DeviceRef("dev-me", "Odin 3"), "b2", "mv", false), SoloRow(DeviceRef("dev-thor", "Thor"), "b3", "tv", false)),
            ),
        ))
        override suspend fun conflicts() = listOf(
            ConflictRow("c1", "eden", "k1", "Pokémon Brilliant Diamond", ItemKind.Save, null, null, ConflictReason.FirstSync,
                listOf(side("dev-me", "Odin 3", false, "2026-11-19T21:48:00Z"), side("dev-thor", "Thor", true, "2026-11-17T10:12:00Z"))),
            ConflictRow("c2", "retroarch", "k2", "Super Metroid", ItemKind.Save, null, null, ConflictReason.FirstSync,
                listOf(side("dev-me", "Odin 3", false, "2026-11-18T09:00:00Z"), side("dev-thor", "Thor", true, "2026-11-16T12:00:00Z"))),
        )
    }

    private val calm = object : Api by server {
        override suspend fun conflicts() = emptyList<ConflictRow>()
    }

    private fun vm(handle: SavedStateHandle, signedIn: Boolean, api: Api = server): SetupViewModel {
        val store = StateStore(File(tmp.root, "state.json"))
        if (signedIn) { store.serverUrl = "http://nas.local:5100"; store.deviceKey = "key"; store.deviceId = "dev-me" }
        val settings = SettingsViewModel(store, modules, { emptySet() }, { null }, MutableStateFlow("Odin 3"), {}, {}, {}, scope)
        val snapshot = MutableStateFlow(ServiceSnapshot(running = true, live = true, emulators = listOf(
            EmulatorInfo(Detected("eden", "dev.eden.eden_emulator", "", "/d"), 4),
            EmulatorInfo(Detected("retroarch", "com.retroarch.aarch64", "", "/d"), 12),
            EmulatorInfo(Detected("ppsspp", "org.ppsspp.ppsspp", "", "/d"), 2),
        )))
        return SetupViewModel(
            handle, store, modules, { api }, { api }, MutableStateFlow(ShizukuState.Ready), {}, snapshot, settings,
            { if (it != null && it.endsWith("SCAN")) snapshot.value = snapshot.value.copy(scans = snapshot.value.scans + 1) },
            {}, scope, MutableStateFlow(emptySet()), { id -> modules.first { it.emulatorId == id }.displayName }, "Odin 3",
        )
    }

    private fun signIn() = SavedStateHandle(mapOf(
        "setup.active" to true, "setup.step" to SetupStep.Server.name, "setup.part" to SignInPart.Method.name,
        "setup.serverName" to "Home server", "setup.registrationOpen" to true, "setup.url" to "http://nas.local:5100",
    ))

    private fun at(step: SetupStep, extra: Map<String, Any?> = emptyMap()) =
        SavedStateHandle(mapOf("setup.active" to true, "setup.step" to step.name) + extra)

    @Test fun signInDark() = shoot(ThemeMode.Dark, vm(signIn(), false), "setup-signin-dark.png", "Sign in to Home server", signedIn = false)
    @Test fun signInLight() = shoot(ThemeMode.Light, vm(signIn(), false), "setup-signin-light.png", "Sign in to Home server", signedIn = false)

    private fun sync() = vm(at(SetupStep.SyncChoice, mapOf("setup.emulatorAt" to 1)), true)
    @Test fun syncChoiceDark() = shoot(ThemeMode.Dark, sync(), "setup-sync-dark.png", "RetroArch settings")
    @Test fun syncChoiceLight() = shoot(ThemeMode.Light, sync(), "setup-sync-light.png", "RetroArch settings")

    @Test fun firstSyncDark() = shoot(ThemeMode.Dark, vm(at(SetupStep.FirstSync), true), "setup-first-dark.png", "Super Metroid")
    @Test fun firstSyncLight() = shoot(ThemeMode.Light, vm(at(SetupStep.FirstSync), true), "setup-first-light.png", "Super Metroid")

    // The devices' real size, for design/screens/app/robo-*.png.
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceSignInDark() = shoot(ThemeMode.Dark, vm(signIn(), false), "robo-setup-signin-dark.png", "Sign in to Home server", signedIn = false)
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceSignInLight() = shoot(ThemeMode.Light, vm(signIn(), false), "robo-setup-signin-light.png", "Sign in to Home server", signedIn = false)
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceSyncChoiceDark() = shoot(ThemeMode.Dark, sync(), "robo-setup-sync-dark.png", "RetroArch settings")
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceSyncChoiceLight() = shoot(ThemeMode.Light, sync(), "robo-setup-sync-light.png", "RetroArch settings")
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceAllSetDark() = shoot(ThemeMode.Dark, vm(at(SetupStep.FirstSync), true, calm), "robo-setup-allset-dark.png", "18")
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceAllSetLight() = shoot(ThemeMode.Light, vm(at(SetupStep.FirstSync), true, calm), "robo-setup-allset-light.png", "18")
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceFirstSyncDark() = shoot(ThemeMode.Dark, vm(at(SetupStep.FirstSync), true), "robo-setup-first-dark.png", "Super Metroid")
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceFirstSyncLight() = shoot(ThemeMode.Light, vm(at(SetupStep.FirstSync), true), "robo-setup-first-light.png", "Super Metroid")

    private fun shoot(mode: ThemeMode, vm: SetupViewModel, name: String, expect: String, signedIn: Boolean = true) {
        val status = TopStatus(if (signedIn) "Odin 3" else null, if (signedIn) ServerConn.Connected else ServerConn.SignedOut, ShizukuState.Ready, signedIn)
        compose.setContent { EmuTheme(mode) { Chrome { SetupFlow(vm, status, openShizuku = null, onDone = {}, diagnostics = {}) } } }
        compose.onNodeWithText(expect).assertExists()
        val dir = System.getenv("TANDEM_SHOT_DIR") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
