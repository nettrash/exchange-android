import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

plugins {
    alias(libs.plugins.android.application)
    // AGP 9's built-in Kotlin auto-applies the Kotlin Android plugin.
    // Adding it explicitly here would throw "Cannot add extension with
    // name 'kotlin' …" — see android.builtInKotlin in gradle.properties.
    alias(libs.plugins.kotlin.compose)
    // KSP replaces kapt for Room. kapt was fragile against Kotlin 2.2 +
    // Room 2.6.x; KSP is Room's recommended path and faster besides.
    alias(libs.plugins.ksp)
}

// versionCode strategy: read from `version.properties` at the repo root
// and auto-incremented after every successful assemble*/bundle* by the
// `bumpVersionCode` finalizer below. Mirrors the iOS app's `agvtool
// bump` post-build action — every Build button press bumps the number.
//
// The file IS tracked in git so the bump propagates between machines
// (commit it after a release, just like you'd commit the iOS pbxproj
// diff).
//
// versionName is human (semver). Defaults to "1.0" but can be overridden
// at the command line — the release workflow passes
// `-PversionName=1.2.3` derived from the v1.2.3 git tag, so a tag = a
// published version name.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use(::load)
    }
}
val storedVersionCode: Int = run {
    val raw = versionProps.getProperty("versionCode")
    if (raw == null) {
        // No file present (fresh checkout that forgot to commit
        // version.properties, or a shallow CI clone). Fail loudly here
        // instead of defaulting to a small number that Play will already
        // have reserved from a previous upload.
        throw GradleException(
            "version.properties is missing or has no `versionCode` entry. " +
                "Either commit ${versionPropsFile.relativeTo(rootProject.projectDir)} " +
                "to the repo, or pass an explicit `-PversionCode=N` (where N is " +
                "strictly greater than every versionCode previously uploaded to Play)."
        )
    }
    val override = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    override ?: raw.toInt()
}

val resolvedVersionName: String =
    (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.3"

// Allow opting out of the bump for a single build, e.g. when running a
// throwaway test or when CI does not want the local file mutated:
//     ./gradlew :app:bundleRelease -PnoBump
val skipVersionBump: Boolean = project.hasProperty("noBump")

// Resolve release signing material from (in order):
//   1. `keystore.properties` next to the root build file (developer machines).
//   2. EXCHANGE_KEYSTORE_PATH / EXCHANGE_KEYSTORE_PASSWORD /
//      EXCHANGE_KEY_ALIAS / EXCHANGE_KEY_PASSWORD environment variables
//      (CI).
// Returns `null` when nothing is configured — the release build then
// falls back to the debug signing config so `assembleRelease` still
// works locally without keys (it just won't be uploadable to Play).
val releaseSigning: Map<String, String>? = run {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val p = Properties().apply { propsFile.inputStream().use(::load) }
        mapOf(
            "storeFile" to (p.getProperty("storeFile") ?: return@run null),
            "storePassword" to (p.getProperty("storePassword") ?: return@run null),
            "keyAlias" to (p.getProperty("keyAlias") ?: return@run null),
            "keyPassword" to (p.getProperty("keyPassword") ?: return@run null),
        )
    } else {
        val path = System.getenv("EXCHANGE_KEYSTORE_PATH")
        val storePassword = System.getenv("EXCHANGE_KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("EXCHANGE_KEY_ALIAS")
        val keyPassword = System.getenv("EXCHANGE_KEY_PASSWORD")
        if (path != null && storePassword != null && keyAlias != null && keyPassword != null) {
            mapOf(
                "storeFile" to path,
                "storePassword" to storePassword,
                "keyAlias" to keyAlias,
                "keyPassword" to keyPassword,
            )
        } else null
    }
}

android {
    namespace = "me.nettrash.exchange"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.nettrash.exchange"
        // Mirror iOS Exchange 26+ on Android: API 36 only. Modern device
        // baseline; every API in scope (Compose, Camera2/CameraX, latest
        // Keystore, edge-to-edge insets) is first-class.
        minSdk = 36
        targetSdk = 36
        versionCode = storedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        releaseSigning?.let { sig ->
            create("release") {
                storeFile = rootProject.file(sig.getValue("storeFile"))
                storePassword = sig.getValue("storePassword")
                keyAlias = sig.getValue("keyAlias")
                keyPassword = sig.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // If `keystore.properties` / env-vars aren't present, this
            // stays null and AGP falls back to the debug signing config
            // so local `assembleRelease` still works (just not
            // uploadable to Play).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // Generate BuildConfig so we can surface VERSION_NAME /
        // VERSION_CODE in the Settings "About" section. AGP 8+ only
        // produces BuildConfig when this flag is on.
        buildConfig = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols += setOf("**/*.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
            )
        }
    }
}

// One-shot housekeeping: the launcher icons live as WebP under
// mipmap-*dpi/. An earlier scripted icon-regeneration step also left
// stale PNG siblings (ic_launcher.png / ic_launcher_round.png) in each
// density folder, which AAPT rejects as duplicate resources. We can't
// delete them from a sandboxed environment, so the cleanup is wired
// into preBuild — once the PNGs are gone this task becomes a fast
// no-op on every subsequent build.
val cleanStalePngLaunchers = tasks.register<Delete>("cleanStalePngLaunchers") {
    delete(
        fileTree("src/main/res") {
            include(
                "mipmap-mdpi/ic_launcher.png",
                "mipmap-mdpi/ic_launcher_round.png",
                "mipmap-hdpi/ic_launcher.png",
                "mipmap-hdpi/ic_launcher_round.png",
                "mipmap-xhdpi/ic_launcher.png",
                "mipmap-xhdpi/ic_launcher_round.png",
                "mipmap-xxhdpi/ic_launcher.png",
                "mipmap-xxhdpi/ic_launcher_round.png",
                "mipmap-xxxhdpi/ic_launcher.png",
                "mipmap-xxxhdpi/ic_launcher_round.png",
            )
        }
    )
}
tasks.named("preBuild") { dependsOn(cleanStalePngLaunchers) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    // Only needed for the XML splash theme parent (`Theme.Material3.*`).
    // Compose draws everything at runtime; this dependency is dormant
    // outside the splash window's background.
    implementation(libs.material)

    implementation(libs.bouncycastle.bcprov)

    implementation(libs.androidx.biometric)

    implementation(libs.zxing.core)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // JVM unit tests need a real org.json implementation; Android's
    // test stubs throw "Method ... not mocked" for JSONObject APIs.
    testImplementation("org.json:json:20250517")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

// ---- IDE compatibility: legacy aggregate test-class tasks --------------
//
// AGP 9 stopped creating the legacy aggregate `unitTestClasses` /
// `androidTestClasses` tasks and only registers the variant-specific
// compile tasks (e.g. `compileDebugUnitTestKotlin`,
// `compileDebugAndroidTestKotlin`). Android Studio's "Make Project" /
// Gradle sync still invokes the aggregate names on some code paths and
// fails with "Cannot locate tasks that match ':app:<name>'".
// Register thin aliases so the IDE is happy. Pure aggregators — no
// actions of their own, just dependsOn the per-variant compile tasks.
afterEvaluate {
    if (tasks.findByName("unitTestClasses") == null) {
        tasks.register("unitTestClasses") {
            group = "verification"
            description =
                "Compatibility alias — depends on all variant-specific unit-test compile tasks."
            dependsOn(tasks.matching {
                val n = it.name
                n.startsWith("compile") &&
                    (n.endsWith("UnitTestKotlin") || n.endsWith("UnitTestJavaWithJavac"))
            })
        }
    }
    if (tasks.findByName("androidTestClasses") == null) {
        tasks.register("androidTestClasses") {
            group = "verification"
            description =
                "Compatibility alias — depends on all variant-specific instrumentation-test compile tasks."
            dependsOn(tasks.matching {
                val n = it.name
                n.startsWith("compile") &&
                    (n.endsWith("AndroidTestKotlin") || n.endsWith("AndroidTestJavaWithJavac"))
            })
        }
    }
}

// ---- versionCode auto-bump ----------------------------------------------
//
// Mirrors the iOS app's `agvtool bump` post-build action: every
// successful `assembleDebug`, `assembleRelease`, `bundleDebug`, or
// `bundleRelease` rewrites `version.properties` with `versionCode + 1`.
// The new value is effective on the *next* build (the current build
// keeps the value it was configured with, since defaultConfig is locked
// at configuration time).
//
// `doLast` only fires when the parent task's actions complete
// successfully, so failed builds don't bump. The AtomicBoolean guards
// against double-bumping when more than one of the listed tasks runs
// in a single invocation (e.g. `./gradlew assembleRelease bundleRelease`).
//
// Opt out per-build with `-PnoBump`.
val bumpedInThisInvocation = AtomicBoolean(false)
afterEvaluate {
    if (skipVersionBump) return@afterEvaluate
    listOf(
        "assembleDebug",
        "assembleRelease",
        "bundleDebug",
        "bundleRelease",
    ).forEach { taskName ->
        tasks.findByName(taskName)?.doLast {
            if (!bumpedInThisInvocation.compareAndSet(false, true)) return@doLast
            val newValue = storedVersionCode + 1
            versionProps.setProperty("versionCode", newValue.toString())
            versionPropsFile.outputStream().use {
                versionProps.store(
                    it,
                    "Auto-incremented after build. Edit only if you know what you're doing."
                )
            }
            logger.lifecycle(
                ":app: bumped versionCode $storedVersionCode -> $newValue (effective next build)"
            )
        }
    }
}
