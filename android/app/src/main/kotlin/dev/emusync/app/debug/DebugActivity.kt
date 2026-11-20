package dev.emusync.app.debug

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.emusync.app.api.ApiClient
import dev.emusync.app.api.ApiException
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.model.DeviceKeyResponse
import dev.emusync.app.service.AppGraph
import dev.emusync.app.service.LogBuffer
import dev.emusync.app.service.SyncService
import dev.emusync.app.sync.SignInForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Temporary setup and diagnostics screen, built in code. Replaced by the real UI after the mockups. */
class DebugActivity : Activity() {
    private companion object {
        const val DEFAULT_SERVER = "http://192.168.1.117:5100"
        const val LOG_LINES = 200
        const val REFRESH_MS = 2_000L
        const val REQ_NOTIFICATIONS = 1
        val EDITED_OVERRIDES = setOf("azahar.root", "ppsspp.memstick")
    }

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refresher: Job? = null
    private val store by lazy { AppGraph.store(this) }
    private val gate by lazy { AppGraph.gate(this) }

    private lateinit var root: LinearLayout
    private lateinit var account: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var shizuku: TextView
    private lateinit var emulators: TextView
    private lateinit var logView: TextView
    private lateinit var server: EditText
    private lateinit var deviceName: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var pairCode: EditText
    private lateinit var romDirs: EditText
    private lateinit var azaharRoot: EditText
    private lateinit var ppssppMemstick: EditText
    private lateinit var edenPackage: EditText
    private lateinit var autoApply: CheckBox
    private lateinit var pending: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "EmuSync (debug)"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        build()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        gate.refresh()
        refresher = ui.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        refresher?.cancel()
        refresher = null
        super.onPause()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    // ---- layout ----

    private fun build() {
        val settings = store.settings

        header("Status")
        account = text()
        serviceStatus = text()
        row(
            button("Start sync service") { SyncService.start(this) },
            button("Stop service") { stopService(Intent(this, SyncService::class.java)) },
        )
        row(
            button("Scan now") { SyncService.start(this, SyncService.ACTION_SCAN) },
            button("Apply deliveries now") { SyncService.start(this, SyncService.ACTION_APPLY) },
        )

        header("Shizuku")
        shizuku = text()
        root.addView(button("Request Shizuku permission") { requestShizuku() })

        header("Server and sign-in")
        val form = store.signInForm
        server = field("Server URL", form.serverUrl ?: store.serverUrl ?: DEFAULT_SERVER, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        deviceName = field("Device name", Build.MODEL ?: "Android")
        email = field("Email", form.email ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = field("Password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        row(
            button("Register") { signIn("register") { c, n -> c.register(email.value(), password.value(), n) } },
            button("Login") { signIn("login") { c, n -> c.login(email.value(), password.value(), n) } },
        )
        pairCode = field("Pairing code", "")
        root.addView(button("Pair") { signIn("pair") { c, n -> c.redeemPairing(pairCode.value(), n) } })

        header("Detected emulators")
        emulators = text()

        header("Deliveries")
        autoApply = CheckBox(this).apply {
            text = "Auto-apply deliveries"
            isChecked = settings.autoApply
            setOnCheckedChangeListener { _, on -> setAutoApply(on) }
        }.also { root.addView(it) }
        text().text = "Off: deliveries are applied only with \"Apply deliveries now\"."
        root.addView(button("Refresh pending deliveries") { loadPending() })
        pending = text().apply { text = "Not loaded." }

        header("Settings")
        romDirs = field("ROM folders (comma-separated)", settings.romDirs.joinToString(", "))
        azaharRoot = field("azahar.root (Azahar data root override)", settings.overrides["azahar.root"] ?: "")
        ppssppMemstick = field("ppsspp.memstick (PPSSPP memstick override)", settings.overrides["ppsspp.memstick"] ?: "")
        edenPackage = field("Eden variant (package; Settings › Emulators)", settings.variants["eden"] ?: "")
        root.addView(button("Save settings") { saveSettings() })
        text().text = "melonDS settings sync via export: export them in melonDS to " +
            "/storage/emulated/0/EmuSync/melonds-export/ and EmuSync picks them up from there."

        header("Log (last $LOG_LINES lines)")
        logView = text().apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
    }

    // ---- actions ----

    private fun signIn(what: String, call: suspend (ApiClient, String) -> DeviceKeyResponse) {
        val url = server.value().trimEnd('/')
        saveForm()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("Server URL must start with http:// or https://")
            return
        }
        val name = deviceName.value().ifBlank { Build.MODEL ?: "Android" }
        ui.launch {
            try {
                val r = call(ApiClient(url, null), name)
                store.serverUrl = url
                store.deviceKey = r.deviceKey
                store.deviceId = r.deviceId
                LogBuffer.add("$what ok: device ${r.deviceId} on $url")
                toast("Signed in as device ${r.deviceId}")
                SyncService.start(this@DebugActivity, SyncService.ACTION_RELOAD)
            } catch (e: ApiException) {
                LogBuffer.add("$what failed: ${e.status} ${e.code ?: ""} ${e.message ?: ""}".trim())
                toast("$what failed: ${e.status} ${e.code ?: e.message ?: ""}")
            } catch (e: Exception) {
                LogBuffer.add("$what failed: ${e.javaClass.simpleName}: ${e.message}")
                toast("$what failed: ${e.message}")
            }
            refresh()
        }
    }

    private fun saveSettings() {
        val dirs = romDirs.value().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val edited = mapOf(
            "azahar.root" to azaharRoot.value(),
            "ppsspp.memstick" to ppssppMemstick.value(),
        ).filterValues { it.isNotBlank() }
        val current = store.settings
        val overrides = (current.overrides - EDITED_OVERRIDES) + edited   // keep keys this screen doesn't edit
        val eden = edenPackage.value().trim()
        val variants = if (eden.isEmpty()) current.variants - "eden" else current.variants + ("eden" to eden)
        // copy: theme and the other emulators' variants are not on this screen and must survive a save
        store.settings = current.copy(romDirs = dirs, overrides = overrides, autoApply = autoApply.isChecked, variants = variants)
        saveForm()
        LogBuffer.add("settings saved: ${dirs.size} ROM folders, overrides ${overrides.keys}")
        if (AppGraph.snapshot.value.running) SyncService.start(this, SyncService.ACTION_RELOAD)
        toast("Saved")
    }

    /** Persists the server URL and email of the form. Never the password. */
    private fun saveForm() {
        store.signInForm = SignInForm(server.value().trimEnd('/').ifBlank { null }, email.value().ifBlank { null })
    }

    private fun setAutoApply(on: Boolean) {
        if (store.settings.autoApply == on) return
        store.settings = store.settings.copy(autoApply = on)
        LogBuffer.add("auto-apply ${if (on) "on" else "off"}")
        if (AppGraph.snapshot.value.running) SyncService.start(this, SyncService.ACTION_RELOAD)
    }

    private fun loadPending() {
        val url = store.serverUrl
        val key = store.deviceKey
        if (url.isNullOrBlank() || key.isNullOrBlank()) {
            pending.text = "Sign in first."
            return
        }
        pending.text = "Loading..."
        ui.launch {
            pending.text = try {
                val list = ApiClient(url, key).deliveries()
                if (list.isEmpty()) "No pending deliveries."
                else "${list.size} pending:\n" + list.joinToString("\n") { d ->
                    listOf(
                        d.kind.name, d.emulatorId, d.itemKind?.name, d.gameKey,
                        d.slot?.takeIf { it.isNotEmpty() }?.let { "slot $it" },
                        d.core?.takeIf { it.isNotEmpty() }, "${d.manifest.size} files",
                    ).filterNotNull().joinToString(" / ")
                }
            } catch (e: ApiException) {
                "Failed: ${e.status} ${e.code ?: e.message ?: ""}"
            } catch (e: Exception) {
                "Failed: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private fun requestShizuku() {
        gate.refresh()
        when (gate.state.value) {
            ShizukuState.NotRunning -> toast("Shizuku isn't running: start it in the Shizuku app first")
            ShizukuState.Ready -> toast("Shizuku permission already granted")
            ShizukuState.NoPermission -> try {
                gate.requestPermission()
            } catch (e: Exception) {
                toast("Request failed: ${e.message}")
            }
        }
    }

    private fun requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    // ---- refresh ----

    private fun refresh() {
        val snap = AppGraph.snapshot.value
        account.text = if (store.deviceKey.isNullOrBlank()) "Not signed in"
        else "Signed in: device ${store.deviceId ?: "?"} on ${store.serverUrl ?: "?"}"
        serviceStatus.text = if (snap.running)
            "Service: ${snap.status}\nServer events: ${if (snap.online) "connected" else "not connected"}, watching ${snap.watchDirs} dirs"
        else "Service: not running"
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        shizuku.text = "Shizuku: ${gate.state.value}" + (if (notif) "" else "\nNotifications: not allowed")
        emulators.text = when {
            !snap.running -> "Start the sync service to detect emulators."
            snap.emulators.isEmpty() -> "None detected yet (needs Shizuku, sign-in and a finished scan)."
            else -> snap.emulators.joinToString("\n\n") { e ->
                val d = e.detected
                val flags = buildList {
                    if (d.extraRoots.containsKey("cfgMissing")) add("RetroArch config not found externally (cfgMissing)")
                    d.extraRoots.filterKeys { it != "cfgMissing" }.forEach { (k, v) -> add("$k: $v") }
                }
                "${d.emulatorId}: ${d.pkg} ${d.version}\n  data root: ${d.dataRoot}\n  items: ${e.itemCount}" +
                    flags.joinToString("") { "\n  $it" }
            }
        }
        logView.text = LogBuffer.last(LOG_LINES).joinToString("\n")
    }

    // ---- view helpers ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(s: String) {
        root.addView(TextView(this).apply {
            text = s
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    private fun text(): TextView = TextView(this).also { root.addView(it) }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        root.addView(TextView(this).apply { text = hint; textSize = 12f })
        return EditText(this).apply {
            isSingleLine = true          // before inputType: setSingleLine resets a password transformation
            inputType = type
            setText(value)
        }.also { root.addView(it) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { r.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        root.addView(r)
    }

    private fun EditText.value() = text.toString().trim()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
