import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.prototux.aare"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // the AARE underneath, shown under the phone app (gradle.properties, docs/upstream.md)
        buildConfigField("String", "AARE_BASE", "\"" + (providers.gradleProperty("aareBase").orNull ?: "?") + "\"")
        buildConfigField("String", "AARE_SEEN", "\"" + (providers.gradleProperty("aareSeen").orNull ?: "?") + "\"")
        applicationId = "app.idmate.mate"
        // Android 8. Newer features need newer Android: car audio by screen capture and the
        // modern Wi-Fi join (10), Shizuku capture (12); older phones fall back or go without.
        minSdk = 26
        targetSdk = 37
        // The release workflow passes the tag's version (-PversionName=1.2.3 -PversionCode=10203).
        // A desk build carries the last tag's code (v2.0.7 -> 20007): it installs over the Panel-Store's release (same
        // key, not a downgrade), and the next release replaces it again (17.11.2026)
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: runCatching {
            // isIgnoreExitValue: without tags (the CI's shallow checkout) git ends with 128 - a failure the configuration
            // cache kept and failed the whole build with, after every test had passed (17.11.2026)
            val t = providers.exec { commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*"); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
            val (ma, mi, pa) = t.removePrefix("v").split('.').map { it.toInt() }
            ma * 10000 + mi * 100 + pa
        }.getOrDefault(10000)
        // a build without a tag's version (debug, at the desk) names the last tag and "-dev", not a made-up 1.0.0
        versionName = providers.gradleProperty("versionName").orNull ?: runCatching {
            providers.exec { commandLine("git", "describe", "--tags", "--always"); isIgnoreExitValue = true }.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "dev" } + "-dev"
        }.getOrDefault("dev")
    }

    signingConfigs {
        // Release key from the environment (the release workflow's secrets). Without it the
        // release APK is unsigned: use the debug one locally.
        val keystore = providers.environmentVariable("AARE_KEYSTORE").orNull
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("AARE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("AARE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("AARE_KEY_PASSWORD").orNull
            }
        }
        // Mate's own key at the build PC (~/aosp/keys, never in the repo): desk builds signed like the releases, so they
        // go over the Panel-Store's install and back without uninstalling (17.11.2026)
        val home = System.getProperty("user.home")
        val mateKey = file("$home/aosp/keys/mate-release.jks")
        val matePass = file("$home/aosp/keys/mate-release.pass")
        if (mateKey.isFile && matePass.isFile) {
            create("mate") {
                storeFile = mateKey
                storePassword = matePass.readText().trim()
                keyAlias = "mate"
                keyPassword = matePass.readText().trim()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 on (shrinks and optimizes the code; names stay, see proguard-rules.pro): Compose runs far
            // smoother optimized. Resources are left as they are.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "TEST_HOOKS", "false")
        }
        debug {
            signingConfigs.findByName("mate")?.let { signingConfig = it }
            buildConfigField("boolean", "TEST_HOOKS", "true")
        }
        // For measuring (projekt/werkzeuge/navi-messen.sh): optimised like the release (R8, not debuggable), signed with the
        // debug key, and answering the test tools from adb - so a test destination is set without touching the screen
        // (no blind taps through the driver's own destinations). Never handed out.
        create("messen") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("mate") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "TEST_HOOKS", "true")
        }
        // A test build beside the Panel-Store's install (21.11.2026: "für sowas lohnt sich ja ne 2. branch oder nicht? test
        // builds"): its own app id and name ("Mate Probe"), so the store's Mate keeps its data and updates - the release key
        // is only in the release workflow, a desk build cannot go over the store's. Without the TEMPLATE_RENDERER
        // definition the store's Mate already holds (src/probe). ./gradlew :app:assembleProbe
        create("probe") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".probe"
            versionNameSuffix = "-probe"
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        // Ferrostar uses java.time on every Android version Mate runs on (minSdk 26)
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // the car screen's preview (CarScreenPreview): Compose drawn by Robolectric with the app's resources
        unitTests.isIncludeAndroidResources = true
        // the preview runs only when asked for (-Ppreview), never with the ordinary tests or in CI
        unitTests.all { t ->
            // conscrypt-android (System L-063) has no native part for the build machine: Robolectric keeps its own TLS
            t.systemProperty("robolectric.conscryptMode", "OFF")
            if (project.hasProperty("preview")) t.systemProperty("preview", "1") else t.exclude("**/preview/**")
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
        // speech recognition (sherpa-onnx): phones and tablets only, no emulator builds of its 50 MB of libraries
        jniLibs.excludes += setOf("lib/x86/**", "lib/x86_64/**")
    }
    lint {
        // a sideloaded app: no Play Store policy checks
        disable += setOf("ProtectedPermissions", "QueryAllPackagesPermission")
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

// AARE's built-in phone certificate, offered to head units after any imported one and before the
// runtime-generated one. Release builds get it from the AARE_PHONE_CERT / AARE_PHONE_KEY secrets
// (PEM certificate chain, PEM PKCS#8 key); other builds simply go without. It ships in the APK, so
// it isn't secret from users: it's a stable identity a head-unit vendor or CA could sign one day.
abstract class PhoneIdentityAssets : DefaultTask() {
    @get:Input @get:Optional abstract val cert: Property<String>
    @get:Input @get:Optional abstract val key: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = outputDir.get().asFile.resolve("phone_identity")
        dir.deleteRecursively()
        val c = cert.orNull?.takeIf { it.isNotBlank() }
        val k = key.orNull?.takeIf { it.isNotBlank() }
        if (c == null || k == null) {
            if (c != null || k != null) logger.warn("AARE_PHONE_CERT and AARE_PHONE_KEY go together: no built-in certificate")
            return
        }
        dir.mkdirs()
        dir.resolve("cert.pem").writeText(c)
        dir.resolve("key.pem").writeText(k)
    }
}

val phoneIdentityAssets = tasks.register<PhoneIdentityAssets>("phoneIdentityAssets") {
    cert.set(providers.environmentVariable("AARE_PHONE_CERT"))
    key.set(providers.environmentVariable("AARE_PHONE_KEY"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(phoneIdentityAssets, PhoneIdentityAssets::outputDir)
    }
}

// Local speech recognition (O56) and voice (O110): sherpa-onnx 1.12 (Apache-2.0) with Whisper and Piper, all on the
// device - 1.12, as 2.x no longer brings the espeak-ng phonemizer that Piper voices need. The AAR is too big for
// git: fetched on the first build and checked against its checksum.
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-1.12.40.aar").asFile
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.40/sherpa-onnx-1.12.40.aar").toURL()
        .openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
}
run {
    val sum = MessageDigest.getInstance("SHA-256").digest(sherpaAar.readBytes()).joinToString("") { "%02x".format(it) }
    check(sum == "a7699d6a2e638a38791b2459cb0099c7577e6e5fca6b8ae916742c872a618b82") { "sherpa-onnx AAR: checksum $sum does not match" }
}

dependencies {
    implementation(files(sherpaAar))
    implementation(project(":aap"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    // the built-in dashboard reads its server's pairing code as a QR code
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(libs.media3.session)
    // the built-in music area plays from the own Plex server
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    // own navigation (O34, specification N-03): Ferrostar guides along a route from the own Valhalla,
    // MapLibre draws the map from the own tiles
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(libs.ferrostar.core)
    implementation(libs.ferrostar.ui.maplibre)
    implementation(libs.ferrostar.ui.formatters)
    // Ferrostar talks to the routing service through OkHttp (its own dependency is runtime only)
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    // Car audio without the screen-capture consent: through Shizuku (adb rights), or Android's
    // audio-policy interface when installed as a privileged app (tools/magisk).
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    // System L-063: Mate pairs once with the tablet's wireless debugging and starts Shizuku itself (adb protocol, TLS pairing)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    testImplementation(libs.junit)
    // the car screen's preview drawn in the unit tests (18.11.2026: pictures of the controls at the car's size, without a car)
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
