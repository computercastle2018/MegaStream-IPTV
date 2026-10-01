import java.util.Properties
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.math.BigInteger
import java.util.Base64
import com.android.build.api.variant.BuildConfigField

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

// Public-only, deterministic hash-to-curve fixture. No signing/private key exists here.
// DEBUG ONLY: never use this identity or point as a production trust anchor.
object LeasePins {
    const val DEBUG_KID = "megastream-debug-public-fixture-v1"
    const val DEBUG_X = "7ZxLpGZzKkZzTPjUflE7plRT06zqvCNavKhn378e3N4"
    const val DEBUG_Y = "ei_MPCRnLcEjGJbbs9jZqH21sGfUzvg-LYhDVoQrV4g"

    fun validate(kid: String, x: String, y: String) {
        require(kid.matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
            "MEGASTREAM_LEASE_KID must be a nonempty token (1–128 ASCII letters, digits, '.', '_' or '-')."
        }
        validateCurvePoint(coordinate("MEGASTREAM_LEASE_X", x), coordinate("MEGASTREAM_LEASE_Y", y))
    }

    fun rejectDebugFixture(kid: String, x: String, y: String) {
        require(kid != DEBUG_KID && !(x == DEBUG_X && y == DEBUG_Y)) {
            "The debug-only public lease fixture is forbidden in release/beta builds."
        }
    }

    private fun coordinate(name: String, encoded: String): BigInteger {
        require(encoded.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            "$name must be an unpadded base64url P-256 coordinate (32 bytes)."
        }
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded) {
            "$name must be a canonical base64url P-256 coordinate."
        }
        return BigInteger(1, bytes)
    }

    private fun validateCurvePoint(x: BigInteger, y: BigInteger) {
        val p = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        val b = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
        require(x < p && y < p &&
            y.modPow(BigInteger.TWO, p) ==
            x.modPow(BigInteger.valueOf(3), p).subtract(x.multiply(BigInteger.valueOf(3))).add(b).mod(p)) {
            "MEGASTREAM_LEASE_X/Y must identify a finite point on the P-256 curve."
        }
    }
}

abstract class ValidateLeasePins : DefaultTask() {
    @get:Input abstract val kid: Property<String>
    @get:Input abstract val x: Property<String>
    @get:Input abstract val y: Property<String>
    @get:Input abstract val production: Property<Boolean>

    @TaskAction
    fun validatePins() {
        LeasePins.validate(kid.get(), x.get(), y.get())
        if (production.get()) LeasePins.rejectDebugFixture(kid.get(), x.get(), y.get())
    }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    FileInputStream(localPropertiesFile).use(localProperties::load)
}

fun localProp(key: String): String = localProperties.getProperty(key, "")

fun computeOfficialSigningCertSha256(): String {
    if (!keystorePropertiesFile.exists()) return ""

    val storePath = keystoreProperties.getProperty("storeFile") ?: return ""
    val storePassword = keystoreProperties.getProperty("storePassword") ?: return ""
    val keyAlias = keystoreProperties.getProperty("keyAlias") ?: return ""
    val storeFile = rootProject.file(storePath)
    if (!storeFile.exists()) return ""

    val keyStore = KeyStore.getInstance("JKS")
    storeFile.inputStream().use { input ->
        keyStore.load(input, storePassword.toCharArray())
    }

    val certificate = keyStore.getCertificate(keyAlias) ?: return ""
    return MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded)
        .joinToString(":") { byte -> "%02X".format(byte) }
}

val officialSigningCertSha256 = computeOfficialSigningCertSha256()

android {
    namespace = "com.MegaStream.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.megastream.app"
        minSdk = 27
        targetSdk = 36
        versionCode = 41
        versionName = "3.0.8"
        resValue("string", "app_display_name", "MegaStream $versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OFFICIAL_APPLICATION_ID", "\"com.megastream.app\"")
        buildConfigField("String", "OFFICIAL_SIGNING_CERT_SHA256", "\"$officialSigningCertSha256\"")
        buildConfigField("String", "APP_UPDATE_CHANNEL", "\"stable\"")
        buildConfigField("long", "BUILD_TIMESTAMP_UTC", "0L")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // Dev seeding hooks — populated from rootProject/local.properties in the
        // `debug` build type only. Release builds inherit these empty defaults so
        // a release APK can never ship a contributor's credentials. See
        // local.properties.example and docs/DEV_SEEDING.md.
        buildConfigField("String", "XTREAM_DEV_SERVER", "\"\"")
        buildConfigField("String", "XTREAM_DEV_USERNAME", "\"\"")
        buildConfigField("String", "XTREAM_DEV_PASSWORD", "\"\"")
        buildConfigField("String", "XTREAM_DEV_NAME", "\"\"")
        buildConfigField("String", "M3U_DEV_URL", "\"\"")
        buildConfigField("String", "M3U_DEV_NAME", "\"\"")
    }

    // Produce per-ABI APKs (smaller per-device download) plus a universal APK
    // for sideload / GitHub Releases. Each per-ABI APK is ~10 MB lighter than
    // the universal because it omits native libs for other architectures.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "XTREAM_DEV_SERVER", "\"${localProp("xtream.dev.server")}\"")
            buildConfigField("String", "XTREAM_DEV_USERNAME", "\"${localProp("xtream.dev.username")}\"")
            buildConfigField("String", "XTREAM_DEV_PASSWORD", "\"${localProp("xtream.dev.password")}\"")
            buildConfigField("String", "XTREAM_DEV_NAME", "\"${localProp("xtream.dev.name")}\"")
            buildConfigField("String", "M3U_DEV_URL", "\"${localProp("m3u.dev.url")}\"")
            buildConfigField("String", "M3U_DEV_NAME", "\"${localProp("m3u.dev.name")}\"")
        }
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            buildConfigField("String", "APP_UPDATE_CHANNEL", "\"beta\"")
            buildConfigField("long", "BUILD_TIMESTAMP_UTC", "${System.currentTimeMillis()}L")
            isDebuggable = false
            // Keep beta close to release behavior but faster for CI/test distribution.
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        animationsDisabled = true
    }
}

// Environment takes precedence, including explicitly empty values (which must fail).
// A partial pin set never falls back to the debug fixture.
val leasePinNames = listOf("MEGASTREAM_LEASE_KID", "MEGASTREAM_LEASE_X", "MEGASTREAM_LEASE_Y")
val configuredLeasePins = leasePinNames.map { name ->
    providers.environmentVariable(name).orElse(providers.gradleProperty(name)).orNull
}
if (configuredLeasePins.any { it != null }) {
    LeasePins.validate(
        configuredLeasePins[0].orEmpty(), configuredLeasePins[1].orEmpty(),
        configuredLeasePins[2].orEmpty()
    )
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val production = variant.buildType != "debug"
        val pins = if (!production && configuredLeasePins.all { it == null }) {
            listOf(LeasePins.DEBUG_KID, LeasePins.DEBUG_X, LeasePins.DEBUG_Y)
        } else {
            configuredLeasePins.map { it.orEmpty() }
        }
        // Nonempty supplied strings have already been restricted to safe ASCII above.
        // Missing production pins remain empty until the fail-closed task rejects them.
        leasePinNames.zip(pins).forEach { (name, value) ->
            requireNotNull(variant.buildConfigFields) { "BuildConfig lease pins unavailable" }
                .put(name, BuildConfigField("String", "\"$value\"", null))
        }
        val variantTaskName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val validatePins = tasks.register<ValidateLeasePins>("validate${variantTaskName}LeasePins") {
            kid.set(pins[0])
            x.set(pins[1])
            y.set(pins[2])
            this.production.set(production)
        }
        // Gate both the normal variant build and directly requested BuildConfig generation.
        // No requested-task-name heuristic: aggregate and abbreviated tasks are covered too.
        tasks.configureEach {
            if (name == "pre${variantTaskName}Build" || name == "generate${variantTaskName}BuildConfig") {
                dependsOn(validatePins)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

kover {
    currentProject {
        createVariant("ci") {
            add("debug")
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":player"))
    implementation(files("../player/libs/media3-decoder-ffmpeg-1.9.2.aar"))

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(libs.leakcanary.android)

    // Compose TV
    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Activity & Lifecycle
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-process:${libs.versions.lifecycle.get()}")
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.documentfile)
    implementation(libs.coroutines.android)
    implementation(libs.appcompat)
    implementation(libs.mediarouter)
    implementation(libs.play.services.cast.framework)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.kotlin)

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

tasks.configureEach {
    if (name == "hiltJavaCompileDebugUnitTest") {
        enabled = false
    }
}
