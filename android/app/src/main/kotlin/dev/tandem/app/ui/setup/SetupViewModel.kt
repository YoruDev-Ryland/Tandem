package dev.tandem.app.ui.setup

import androidx.lifecycle.SavedStateHandle
import dev.tandem.app.api.Api
import dev.tandem.app.api.ApiException
import dev.tandem.app.api.PROTOCOL_VERSION
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.model.ApplyConfigRequest
import dev.tandem.app.model.ApplyMode
import dev.tandem.app.model.ConfigOverview
import dev.tandem.app.model.ConflictReason
import dev.tandem.app.model.ConflictRow
import dev.tandem.app.model.EmulatorConfigRow
import dev.tandem.app.modules.EmulatorModule
import dev.tandem.app.service.ServiceSnapshot
import dev.tandem.app.service.SyncService
import dev.tandem.app.sync.StateStore
import dev.tandem.app.ui.screens.AZAHAR_ROOT
import dev.tandem.app.ui.screens.ConflictsViewModel
import dev.tandem.app.ui.screens.SettingsViewModel
import dev.tandem.app.ui.state.Loaded
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The setup steps, in order, as the list down the left names them. */
enum class SetupStep(val label: String) {
    Server("Server and sign-in"),
    Shizuku("Shizuku"),
    Emulators("Emulators found"),
    SyncChoice("How each one syncs"),
    FirstSync("First sync"),
}

/** Step 1's screens: the server's address, how to sign in, then the code or the email and password. */
enum class SignInPart { Server, Method, Entry }

enum class SignInMethod(val label: String, val detail: String) {
    Code("Pairing code", "Get one on the web under Devices › Pair a device, or from another signed-in device."),
    Password("Email and password", "The account you sign in with on the web."),
    Register("Create an account", "A new account on this server, with this device as its first."),
}

/** What step 4 does for one emulator. */
sealed interface ConfigChoice {
    data class Join(val groupId: String) : ConfigChoice
    data class Copy(val versionId: String) : ConfigChoice
    /** This device already follows the group: nothing to send. */
    data object Follow : ConfigChoice
    data object Own : ConfigChoice
}

/** One answer to "how does {Emulator} sync", by a [key] that stays the same across reloads of the server's list. */
data class ConfigOption(val key: String, val title: String, val detail: String, val choice: ConfigChoice)

/** "a", "a and b", "a, b and c". */
internal fun nameList(names: List<String>): String =
    if (names.size < 2) names.firstOrNull().orEmpty() else names.dropLast(1).joinToString(", ") + " and " + names.last()

/**
 * Step 4's options for one emulator, with the web setup page's source rules: a group is offered to join only when
 * this device is not in it, another device's own config is offered to copy only when this device has its own (a copy
 * replaces it), and nothing without a backed-up version is offered. The group this device follows is kept first.
 */
fun configOptions(row: EmulatorConfigRow?, me: String): List<ConfigOption> {
    val groups = row?.groups.orEmpty()
    val solo = row?.solo.orEmpty()
    val out = mutableListOf<ConfigOption>()
    groups.firstOrNull { g -> g.members.any { it.id == me } }?.let { g ->
        out += ConfigOption("g/${g.id}", "Keep following \"${g.name}\"", "Nothing changes here. This device keeps the group's settings.", ConfigChoice.Follow)
    }
    for (g in groups) {
        if (g.headVersionId == null || g.members.any { it.id == me }) continue
        val with = g.members.map { it.name }.takeIf { it.isNotEmpty() }?.let { " with ${nameList(it)}" }.orEmpty()
        out += ConfigOption(
            "g/${g.id}", "Join \"${g.name}\"$with",
            "Use the group's settings and keep them the same from now on. This device's current settings are backed up first.",
            ConfigChoice.Join(g.id),
        )
    }
    if (solo.any { it.device.id == me }) {
        for (s in solo) {
            val head = s.headVersionId ?: continue
            if (s.device.id == me) continue
            out += ConfigOption(
                "d/${s.device.id}", "Copy ${s.device.name}'s settings once",
                "Start from ${s.device.name}'s, then change them freely here.", ConfigChoice.Copy(head),
            )
        }
    }
    out += ConfigOption("own", "Only back up this device's settings", "Nothing changes here. You can join a group later.", ConfigChoice.Own)
    return out
}

/** A server read: nothing yet, the value, or why it failed. */
data class Fetch<T>(val value: T? = null, val error: Throwable? = null, val loading: Boolean = false)

enum class FirstSyncPhase { Scanning, Review, Done, Failed }

/** A refusal made before anything is sent, worded for the screen. */
class InputError(message: String) : Exception(message)

const val SCAN_FAILED =
    "The scan of this device didn't finish, so some saves may not be on the server yet. Check the connection and Shizuku, then try again."
const val STILL_SCANNING = "Still scanning…"

const val URL_HELP = "Enter the full address, starting with http:// or https://."

/** The address as the app stores it: trimmed, without a trailing slash; null unless it is http(s) with a host. */
internal fun normalizeUrl(raw: String): String? {
    val u = raw.trim().trimEnd('/')
    val rest = when {
        u.startsWith("http://", ignoreCase = true) -> u.substring(7)
        u.startsWith("https://", ignoreCase = true) -> u.substring(8)
        else -> return null
    }
    return if (rest.isEmpty() || rest.startsWith("/") || rest.any { it.isWhitespace() }) null else u
}

private const val K_ACTIVE = "setup.active"
private const val K_EXITABLE = "setup.exitable"
private const val K_STEP = "setup.step"
private const val K_PART = "setup.part"
private const val K_URL = "setup.url"
private const val K_SERVER_NAME = "setup.serverName"
private const val K_REGISTRATION = "setup.registrationOpen"
private const val K_METHOD = "setup.method"
private const val K_METHOD_AT = "setup.methodAt"
private const val K_CODE = "setup.code"
private const val K_EMAIL = "setup.email"
private const val K_NAME = "setup.deviceName"
private const val K_BACKED = "setup.backedIntoShizuku"
private const val K_EMUS = "setup.emulators"
private const val K_EMU_AT = "setup.emulatorAt"
private const val K_CHOICES = "setup.choices"

/**
 * The first-run setup flow: five steps, one question per screen. Where it is and what was chosen live in the
 * [SavedStateHandle], so rotation and process death come back to the same screen (the password never: it is kept
 * in memory only). The server address and email also go to [StateStore.signInForm].
 *
 * Nothing here writes emulator files. Signing in goes through [StateStore.signIn] (every emulator's sync state is
 * forgotten before the new key uploads anything), the variant and folder choices go through [settings] (the same
 * writes and forget protection as Settings), and the service is started only through [service] (SyncService
 * tryStart). Step 5 shows auto-apply (on for a new install) and can turn it off, through [settings] like Settings.
 */
class SetupViewModel(
    private val handle: SavedStateHandle,
    private val store: StateStore,
    private val modules: List<EmulatorModule>,
    /** A client for [String] (the server URL) with no device key. */
    private val anon: (String) -> Api,
    /** The signed-in client, or null when signed out. */
    private val api: () -> Api?,
    val shizuku: StateFlow<ShizukuState>,
    private val askShizuku: () -> Unit,
    val snapshot: StateFlow<ServiceSnapshot>,
    val settings: SettingsViewModel,
    /** Starts the sync service with an action (SyncService.tryStart). */
    private val service: (String?) -> Unit,
    private val afterSignIn: () -> Unit,
    private val scope: CoroutineScope,
    running: StateFlow<Set<String>>,
    val emulatorName: (String) -> String,
    private val defaultDeviceName: String,
    /** How long step 5 waits for its scan before it says it is still scanning. */
    private val scanPatienceMillis: Long = 120_000L,
) {
    /** A value kept in the saved state, readable as a flow. */
    private inner class Saved<T>(private val key: String, initial: T) {
        val flow = MutableStateFlow(if (handle.contains(key)) handle.get<T>(key) as T else initial)
        init { handle[key] = flow.value }
        var value: T
            get() = flow.value
            set(v) { handle[key] = v; flow.value = v }
    }

    private val activeS = Saved(K_ACTIVE, store.deviceKey == null)
    private val exitableS = Saved(K_EXITABLE, false)
    private val stepS = Saved(K_STEP, SetupStep.Server.name)
    private val partS = Saved(K_PART, SignInPart.Server.name)
    private val urlS = Saved(K_URL, store.signInForm.serverUrl ?: store.serverUrl ?: "")
    private val serverNameS = Saved<String?>(K_SERVER_NAME, null)
    private val registrationS = Saved(K_REGISTRATION, false)
    private val methodS = Saved(K_METHOD, SignInMethod.Code.name)
    private val methodAtS = Saved(K_METHOD_AT, 0)
    private val codeS = Saved(K_CODE, "")
    private val emailS = Saved(K_EMAIL, store.signInForm.email ?: "")
    private val nameS = Saved(K_NAME, defaultDeviceName)
    private val backedS = Saved(K_BACKED, false)
    private val emusS = Saved<ArrayList<String>?>(K_EMUS, null)
    private val emuAtS = Saved(K_EMU_AT, 0)
    private val choicesS = Saved(K_CHOICES, ArrayList<String>())

    /** Whether the flow is on screen instead of the tabs. Starts on when the app opens signed out. */
    val active: StateFlow<Boolean> = activeS.flow
    /** B on the first screen leaves (opened from Settings or This device); at launch there is nothing to go back to. */
    val exitable: StateFlow<Boolean> = exitableS.flow
    private val stepFlow = MutableStateFlow(SetupStep.valueOf(stepS.value))
    val step: StateFlow<SetupStep> = stepFlow

    /** This device's id on the server, once signed in. */
    val deviceId: String? get() = store.deviceId

    val signedIn: StateFlow<Boolean> =
        store.changes.map { store.deviceKey != null }.stateIn(scope, SharingStarted.Eagerly, store.deviceKey != null)

    // ---- step 1 ----

    private val partFlow = MutableStateFlow(SignInPart.valueOf(partS.value))
    val part: StateFlow<SignInPart> = partFlow
    val url: StateFlow<String> = urlS.flow
    val serverName: StateFlow<String?> = serverNameS.flow
    val code: StateFlow<String> = codeS.flow
    val email: StateFlow<String> = emailS.flow
    val deviceName: StateFlow<String> = nameS.flow
    val methodAt: StateFlow<Int> = methodAtS.flow
    private val passwordFlow = MutableStateFlow("")
    val password: StateFlow<String> = passwordFlow
    private val methodFlow = MutableStateFlow(SignInMethod.valueOf(methodS.value))
    val method: StateFlow<SignInMethod> = methodFlow
    /** The ways in this server offers: registration only when it takes new accounts. */
    val methods: StateFlow<List<SignInMethod>> = registrationS.flow
        .map { open -> SignInMethod.entries.filter { it != SignInMethod.Register || open } }
        .stateIn(scope, SharingStarted.Eagerly, SignInMethod.entries.filter { it != SignInMethod.Register || registrationS.value })
    private val busyFlow = MutableStateFlow(false)
    /** A request of this step is on its way. */
    val busy: StateFlow<Boolean> = busyFlow
    private val errorFlow = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = errorFlow

    // ---- step 4 ----

    /** The emulators step 4 asks about, frozen when it is first shown; null while the service is still looking. */
    val syncEmulators: StateFlow<List<String>?> = combine(emusS.flow, snapshot) { frozen, s -> frozen ?: detected(s) }
        .stateIn(scope, SharingStarted.Eagerly, emusS.value ?: detected(snapshot.value))
    val emulatorAt: StateFlow<Int> = emuAtS.flow
    /** The option chosen per emulator id, by [ConfigOption.key]. */
    val choices: StateFlow<Map<String, String>> = choicesS.flow
        .map(::decodeChoices).stateIn(scope, SharingStarted.Eagerly, decodeChoices(choicesS.value))
    private val overviewFlow = MutableStateFlow(Fetch<ConfigOverview>())
    val overview: StateFlow<Fetch<ConfigOverview>> = overviewFlow
    private val choosingFlow = MutableStateFlow<String?>(null)
    /** The option key on its way to the server. */
    val choosing: StateFlow<String?> = choosingFlow
    private val choiceErrorFlow = MutableStateFlow<Pair<String, Throwable>?>(null)
    /** The option whose request failed ("emulator/key"), and why. */
    val choiceError: StateFlow<Pair<String, Throwable>?> = choiceErrorFlow

    // ---- step 5 ----

    private val firstRows = MutableStateFlow(Loaded<List<ConflictRow>>())
    private val phaseFlow = MutableStateFlow(FirstSyncPhase.Scanning)
    val phase: StateFlow<FirstSyncPhase> = phaseFlow
    private val firstErrorFlow = MutableStateFlow<Throwable?>(null)
    val firstSyncError: StateFlow<Throwable?> = firstErrorFlow
    private val slowFlow = MutableStateFlow(false)
    /** Step 5's scan has taken longer than the setup waits quietly. */
    val scanSlow: StateFlow<Boolean> = slowFlow
    private var firstSyncJob: Job? = null

    /** The first-sync review: the Conflicts tab's model over this device's FirstSync conflicts, with its bulk plans. */
    val review = ConflictsViewModel(
        conflicts = firstRows,
        running = running,
        signedIn = signedIn,
        deviceId = { store.deviceId },
        api = api,
        scope = scope,
        // The choice changes what this device should hold: apply it (held while the emulator runs), then look again.
        afterResolve = { service(SyncService.ACTION_APPLY); fetchFirstSync() },
        emulatorName = emulatorName,
    )

    init {
        // Shizuku ready while its step is shown: carry on, unless the user came back to look at it.
        scope.launch {
            combine(stepFlow, shizuku, backedS.flow) { s, st, b -> s == SetupStep.Shizuku && st == ShizukuState.Ready && !b }
                .collect { if (it) goTo(SetupStep.Emulators) }
        }
        // Step 4 asks about the emulators found when it was first shown, even while a reload empties the list.
        scope.launch {
            combine(stepFlow, snapshot) { s, snap -> s to snap }.collect { (s, snap) ->
                if (s == SetupStep.SyncChoice && emusS.value == null) detected(snap)?.let { emusS.value = ArrayList(it) }
            }
        }
        if (activeS.value) enter(stepFlow.value)
    }

    private fun detected(s: ServiceSnapshot): List<String>? {
        if (!s.live) return null
        val ids = s.emulators.map { it.detected.emulatorId }.toSet()
        return modules.map { it.emulatorId }.filter { it in ids }
    }

    // ---- moving around ----

    /** Opens the flow from the app (This device's sign-in, Settings › Set up again); B on its first screen leaves. */
    fun start(exitable: Boolean) {
        firstSyncJob?.cancel()
        exitableS.value = exitable
        setPart(SignInPart.Server)
        backedS.value = false
        emusS.value = null
        emuAtS.value = 0
        choicesS.value = ArrayList()
        overviewFlow.value = Fetch()
        errorFlow.value = null
        choiceErrorFlow.value = null
        activeS.value = true
        goTo(if (store.deviceKey != null) SetupStep.Shizuku else SetupStep.Server)
    }

    /** Leaves the flow (finished, or B on its first screen). */
    fun finish() {
        firstSyncJob?.cancel()
        activeS.value = false
    }

    /** B: one screen back. On the first screen it leaves only when there is somewhere to go back to. */
    fun back() {
        if (busyFlow.value) return
        errorFlow.value = null
        when (stepFlow.value) {
            SetupStep.Server -> when {
                // Signed in, setup can always be left (Settings › Set up again resumes it).
                store.deviceKey != null -> finish()
                partFlow.value == SignInPart.Server -> if (exitableS.value) finish()
                partFlow.value == SignInPart.Entry -> setPart(SignInPart.Method)
                else -> setPart(SignInPart.Server)
            }
            SetupStep.Shizuku -> goTo(SetupStep.Server)
            SetupStep.Emulators -> { backedS.value = true; goTo(SetupStep.Shizuku) }
            SetupStep.SyncChoice -> {
                choiceErrorFlow.value = null
                if (emuAtS.value > 0) emuAtS.value -= 1
                else { emusS.value = null; goTo(SetupStep.Emulators) }
            }
            SetupStep.FirstSync -> {
                emuAtS.value = maxOf(0, (syncEmulators.value?.size ?: 1) - 1)
                goTo(SetupStep.SyncChoice)
            }
        }
    }

    private fun setPart(p: SignInPart) { partS.value = p.name; partFlow.value = p }

    private fun goTo(s: SetupStep) {
        if (s != SetupStep.FirstSync) firstSyncJob?.cancel()
        if (s == SetupStep.Shizuku && stepFlow.value != SetupStep.Emulators) backedS.value = false
        stepS.value = s.name
        stepFlow.value = s
        enter(s)
    }

    private fun enter(s: SetupStep) {
        when (s) {
            SetupStep.SyncChoice -> if (overviewFlow.value.value == null) loadConfigs()
            SetupStep.FirstSync -> startFirstSync()
            else -> {}
        }
    }

    // ---- step 1 ----

    fun setUrl(v: String) { urlS.value = v; errorFlow.value = null }
    fun setCode(v: String) { codeS.value = v.filterNot { it.isWhitespace() }.uppercase().take(8); errorFlow.value = null }
    fun setEmail(v: String) { emailS.value = v; errorFlow.value = null }
    fun setPassword(v: String) { passwordFlow.value = v; errorFlow.value = null }
    fun setDeviceName(v: String) { nameS.value = v }
    fun pointMethod(i: Int) { methodAtS.value = i }

    /** Asks the server at the entered address who it is, then moves on to how to sign in. */
    fun connect() {
        if (busyFlow.value) return
        val u = normalizeUrl(urlS.value) ?: run { errorFlow.value = InputError(URL_HELP); return }
        busyFlow.value = true
        errorFlow.value = null
        scope.launch {
            try {
                val info = anon(u).serverInfo()
                if (info.protocol.toString() != PROTOCOL_VERSION) throw ApiException(400, "protocol_mismatch", null)
                urlS.value = u
                serverNameS.value = info.name
                registrationS.value = info.registrationOpen
                methodAtS.value = 0
                store.signInForm = store.signInForm.copy(serverUrl = u)
                setPart(SignInPart.Method)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorFlow.value = e
            } finally {
                busyFlow.value = false
            }
        }
    }

    fun chooseMethod(m: SignInMethod) {
        methodS.value = m.name
        methodFlow.value = m
        errorFlow.value = null
        setPart(SignInPart.Entry)
    }

    /** Signs in with the chosen method; on success the key is stored, sync restarts and Shizuku comes next. */
    fun signIn() {
        if (busyFlow.value) return
        val m = methodFlow.value
        val u = urlS.value
        val name = nameS.value.trim().ifEmpty { defaultDeviceName }
        val email = emailS.value.trim()
        val password = passwordFlow.value
        val code = codeS.value
        when {
            m == SignInMethod.Code && code.length != 8 -> { errorFlow.value = InputError("Enter all 8 characters of the code."); return }
            m != SignInMethod.Code && (email.isEmpty() || password.isEmpty()) -> {
                errorFlow.value = InputError("Enter your email and password."); return
            }
        }
        busyFlow.value = true
        errorFlow.value = null
        scope.launch {
            try {
                val c = anon(u)
                val r = when (m) {
                    SignInMethod.Code -> c.redeemPairing(code, name)
                    SignInMethod.Password -> c.login(email, password, name)
                    SignInMethod.Register -> c.register(email, password, name)
                }
                // One write: the key, and every emulator forgotten so nothing uploads against another account's versions.
                store.signIn(u, r.deviceKey, r.deviceId, modules.map { it.emulatorId })
                store.signInForm = store.signInForm.copy(serverUrl = u, email = if (m == SignInMethod.Code) store.signInForm.email else email)
                passwordFlow.value = ""
                codeS.value = ""
                service(SyncService.ACTION_RELOAD)
                afterSignIn()
                goTo(SetupStep.Shizuku)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorFlow.value = e
            } finally {
                busyFlow.value = false
            }
        }
    }

    /** The signed-in summary (Set up again): on to Shizuku. */
    fun continueSignedIn() { if (store.deviceKey != null) goTo(SetupStep.Shizuku) }

    // ---- step 2 ----

    fun requestShizuku() = askShizuku()
    fun continueShizuku() { if (shizuku.value == ShizukuState.Ready) goTo(SetupStep.Emulators) }

    /** On without Shizuku: sync waits until it is ready, and This device says so. */
    fun skipShizuku() = goTo(SetupStep.Emulators)

    // ---- step 3 ----

    /** The Settings path: the same write and the same forget of the emulator's sync state. */
    fun chooseVariant(emulatorId: String, pkg: String) = settings.chooseVariant(emulatorId, pkg)
    fun addRomDir(path: String): String? = settings.addRomDir(path)
    fun setAzaharFolder(path: String): String? { settings.setOverride(AZAHAR_ROOT, path); return null }
    fun emulatorsDone() = goTo(SetupStep.SyncChoice)

    // ---- step 4 ----

    fun loadConfigs() {
        if (overviewFlow.value.loading) return
        val a = api() ?: return
        overviewFlow.value = overviewFlow.value.copy(loading = true, error = null)
        scope.launch {
            overviewFlow.value = try {
                Fetch(a.configs())
            } catch (e: CancellationException) {
                overviewFlow.value = Fetch()
                throw e
            } catch (e: Exception) {
                Fetch(error = e)
            }
        }
    }

    /** Sends [option] for [emulatorId] (unless it is the one already chosen), then moves to the next emulator. */
    fun choose(emulatorId: String, option: ConfigOption) {
        if (choosingFlow.value != null) return
        val me = store.deviceId ?: return
        choiceErrorFlow.value = null
        if (choices.value[emulatorId] == option.key) { advance(); return }
        val a = api() ?: return
        choosingFlow.value = option.key
        scope.launch {
            try {
                when (val c = option.choice) {
                    is ConfigChoice.Join -> a.joinGroup(c.groupId, me)
                    is ConfigChoice.Copy -> a.applyConfig(ApplyConfigRequest(me, emulatorId, c.versionId, ApplyMode.Copy))
                    ConfigChoice.Own -> a.keepOwn(me, emulatorId)
                    ConfigChoice.Follow -> {}
                }
                choicesS.value = ArrayList(encodeChoices(choices.value + (emulatorId to option.key)))
                // A joined or copied config is now this device's to receive: apply it (held while the emulator runs).
                if (option.choice is ConfigChoice.Join || option.choice is ConfigChoice.Copy) service(SyncService.ACTION_APPLY)
                choosingFlow.value = null
                advance()
            } catch (e: CancellationException) {
                choosingFlow.value = null
                throw e
            } catch (e: Exception) {
                choosingFlow.value = null
                choiceErrorFlow.value = "$emulatorId/${option.key}" to e
            }
        }
    }

    /** No emulator to ask about: on to the first sync. */
    fun syncChoicesDone() = goTo(SetupStep.FirstSync)

    private fun advance() {
        val n = syncEmulators.value?.size ?: 0
        if (emuAtS.value + 1 < n) emuAtS.value += 1 else goTo(SetupStep.FirstSync)
    }

    // ---- step 5 ----

    /**
     * Asks the service for a scan and waits for it to end. Only a scan that got every upload through moves on to this
     * device's first-sync conflicts; a failed or cancelled one is [FirstSyncPhase.Failed], never "All set.". A long
     * wait says so ([scanSlow]) and keeps waiting.
     */
    private fun startFirstSync() {
        firstSyncJob?.cancel()
        phaseFlow.value = FirstSyncPhase.Scanning
        firstErrorFlow.value = null
        slowFlow.value = false
        val base = snapshot.value
        service(SyncService.ACTION_SCAN)
        firstSyncJob = scope.launch {
            val patience = launch { delay(scanPatienceMillis); slowFlow.value = true }
            val end = snapshot.first { it.scans > base.scans || it.failedScans > base.failedScans }
            patience.cancel()
            slowFlow.value = false
            if (end.scans > base.scans) {
                fetchFirstSync()
            } else {
                firstErrorFlow.value = InputError(SCAN_FAILED)
                phaseFlow.value = FirstSyncPhase.Failed
            }
        }
    }

    /** Whether arriving saves are applied by themselves (Settings › Sync's switch). */
    val autoApply: StateFlow<Boolean> = settings.ui.map { it.autoApply }.stateIn(scope, SharingStarted.Eagerly, settings.ui.value.autoApply)
    fun setAutoApply(on: Boolean) = settings.setAutoApply(on)

    /** Try again after a failure: scan again, then read the conflicts. */
    fun retryFirstSync() = startFirstSync()

    /** Reads the conflicts again (Try again, and after each choice). */
    fun fetchFirstSync() {
        val a = api() ?: return
        val me = store.deviceId ?: return
        scope.launch {
            try {
                val rows = a.conflicts().filter { r ->
                    r.reason == ConflictReason.FirstSync && r.sides.size >= 2 && r.sides.any { it.deviceId == me }
                }
                firstRows.value = Loaded(rows, Instant.now())
                firstErrorFlow.value = null
                phaseFlow.value = if (rows.isEmpty()) FirstSyncPhase.Done else FirstSyncPhase.Review
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                firstErrorFlow.value = e
                if (phaseFlow.value != FirstSyncPhase.Review) phaseFlow.value = FirstSyncPhase.Failed
            }
        }
    }
}

private fun encodeChoices(m: Map<String, String>) = m.map { (k, v) -> "$k=$v" }

private fun decodeChoices(l: List<String>): Map<String, String> =
    l.mapNotNull { s -> s.indexOf('=').takeIf { it > 0 }?.let { s.substring(0, it) to s.substring(it + 1) } }.toMap()
