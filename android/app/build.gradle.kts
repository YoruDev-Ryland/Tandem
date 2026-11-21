import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
}

kotlin { jvmToolchain(21) }

/** Runs git in the repository; null when git or the repository isn't there (a source tarball). */
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

// CI passes the release's numbers; a local build counts commits and names itself after the nearest tag.
val tandemVersionCode: Int = System.getenv("TANDEM_VERSION_CODE")?.toIntOrNull()
    ?: git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val tandemVersionName: String = System.getenv("TANDEM_VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: git("describe", "--tags", "--always")?.removePrefix("v")?.let { if (it.first().isDigit()) it else "0.1.0-$it" }
    ?: "0.1.0"

/** android/keystore.properties (gitignored; see scripts/make-release-key.sh). Release builds are signed only with it. */
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "dev.tandem.app"
    // Compose 1.13 (BOM 2026.11.01) needs compileSdk 37.1 or later.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.tandem.app"
        minSdk = 33
        targetSdk = 35
        versionCode = tandemVersionCode
        versionName = tandemVersionName
        // Shizuku keeps a UserService process alive across app updates unless its version changes, so the version
        // changes on every build: an updated APK always replaces the old shell process. Seconds since the epoch fit an
        // int until 2038. (Evaluated at configuration time; revisit if the configuration cache is turned on.)
        val shellServiceVersion = (System.currentTimeMillis() / 1000L).toInt()
        buildConfigField("int", "SHELL_SERVICE_VERSION", "$shellServiceVersion")
    }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
