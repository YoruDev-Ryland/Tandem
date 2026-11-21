package dev.tandem.app.ui.screens

/** A library the app ships, with its licence. */
data class Dependency(val name: String, val by: String, val licence: String)

/** What the APK bundles (releaseRuntimeClasspath), grouped as their projects publish them. */
val DEPENDENCIES = listOf(
    Dependency("Shizuku API and provider", "RikkaApps", "Apache License 2.0"),
    Dependency("OkHttp and OkHttp SSE", "Square", "Apache License 2.0"),
    Dependency("Okio", "Square", "Apache License 2.0"),
    Dependency("Kotlin standard library", "JetBrains", "Apache License 2.0"),
    Dependency("kotlinx.coroutines", "JetBrains", "Apache License 2.0"),
    Dependency("kotlinx.serialization", "JetBrains", "Apache License 2.0"),
    Dependency("Jetpack Compose (UI, Foundation, Material 3)", "Google / AOSP", "Apache License 2.0"),
    Dependency("AndroidX Activity, Lifecycle, Core, Startup", "Google / AOSP", "Apache License 2.0"),
    Dependency("AndroidX WorkManager (and Room, SQLite)", "Google / AOSP", "Apache License 2.0"),
    Dependency("Guava ListenableFuture", "Google", "Apache License 2.0"),
    Dependency("JetBrains annotations, JSpecify", "JetBrains, JSpecify", "Apache License 2.0"),
)

/** The bundled font licences, in assets/licenses/. */
val FONT_LICENCES = listOf(
    "Barlow" to "licenses/barlow-OFL.txt",
    "Barlow Semi Condensed" to "licenses/barlow-semi-condensed-OFL.txt",
    "JetBrains Mono" to "licenses/jetbrains-mono-OFL.txt",
)

/**
 * The About › Open-source licences text: the libraries with their licences, then each font's OFL text as bundled.
 * [read] returns an asset's text, or null when it can't be read.
 */
fun licenceText(read: (String) -> String?): String = buildString {
    appendLine("Tandem is built with these libraries. The Apache License 2.0 is at https://www.apache.org/licenses/LICENSE-2.0.")
    appendLine()
    for (d in DEPENDENCIES) appendLine("${d.name} — ${d.by} — ${d.licence}")
    for ((font, path) in FONT_LICENCES) {
        appendLine()
        appendLine("$font — SIL Open Font License 1.1")
        appendLine()
        appendLine(read(path)?.trim() ?: "($path could not be read)")
    }
}.trimEnd()
