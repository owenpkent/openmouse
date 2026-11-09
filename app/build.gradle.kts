import java.io.FileInputStream
import java.util.Properties

plugins {
    // AGP 9 provides built-in Kotlin support, so the standalone Kotlin plugin
    // is not applied.
    alias(libs.plugins.android.application)
}

// Release signing is optional: fill in keystore.properties (gitignored, see
// keystore.properties.example) to produce a signed release. Without it, the
// release build is unsigned, which is fine for F-Droid (it signs from source).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseKeystore) FileInputStream(keystorePropertiesFile).use { load(it) }
}

// The in-app update check (Settings > Updates, plus a check at launch that is
// on by default) is the app's only network path. F-Droid and Play builds pass
// -PopenmouseUpdater=false so the whole feature, UI and launch check included,
// compiles out: both stores ship updates themselves and sign with their own key,
// so an APK from GitHub Releases could never install over them anyway, and Play
// policy forbids an app updating itself outside Play.
val updaterEnabled = providers.gradleProperty("openmouseUpdater").orElse("true").get().toBoolean()

android {
    namespace = "io.github.owenpkent.openmouse"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.owenpkent.openmouse"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "UPDATER_ENABLED", updaterEnabled.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Instrumented UI tests: animations off so Espresso and the timing-based
        // dwell assertions are not racing transitions.
        animationsDisabled = true
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // JUnit 5 for the plain tests and for Jazzer's @FuzzTest.
            test.useJUnitPlatform()

            // Fuzzing is opt-in: with JAZZER_FUZZ unset the fuzz tests only replay
            // their seed corpus, so an ordinary `gradlew test` stays fast. With
            // JAZZER_FUZZ=1 each target fuzzes for its @FuzzTest maxDuration, and
            // Jazzer fuzzes only one target per JVM, so every test class gets its
            // own JVM in that mode or the second target is silently skipped.
            // A fuzz campaign is also never up to date and never cached: JAZZER_FUZZ
            // is not a task input, so Gradle would otherwise answer a fuzz request
            // with the last seed replay (UP-TO-DATE, or FROM-CACHE after a clean)
            // and no fuzzer would run. Untracked, every request runs Jazzer.
            System.getenv("JAZZER_FUZZ")?.let {
                test.environment("JAZZER_FUZZ", it)
                test.setForkEvery(1)
                test.doNotTrackState("a fuzz campaign is open-ended; never reuse a previous result")
            }
            // Jazzer attaches its agent at runtime; JDK 21+ warns unless this is set.
            test.jvmArgs("-XX:+EnableDynamicAgentLoading")
        }
    }
}

// The updater's permissions (INTERNET, REQUEST_INSTALL_PACKAGES) and its
// FileProvider sit in their own manifest, merged in only when the updater is
// compiled in. Adding rather than removing means a -PopenmouseUpdater=false
// build never declares them at all, with no tools:node="remove" to keep in step.
androidComponents {
    onVariants { variant ->
        if (updaterEnabled) {
            variant.sources.manifests.addStaticManifestFile("src/updater/AndroidManifest.xml")
        }
    }
}

// Drops a version-named copy of the debug APK in the repo-root apks/ folder
// (apks/README.md), a stable place to grab a build to sideload. The name tracks
// versionName above. A debug build is signed with this machine's debug key, so
// it never belongs on a GitHub Release (docs/SIGNING.md). Everything the task
// needs is resolved here, at configuration time, so it stays compatible with
// the configuration cache (the rename is a plain string, not a lambda, which
// would capture the build script); the AOSP Android.bp build never runs Gradle.
val debugApkName = "openmouse-v${android.defaultConfig.versionName ?: "dev"}-debug.apk"
val copyDebugApkToRoot = tasks.register<Copy>("copyDebugApkToRoot") {
    description = "Copies the debug APK to apks/openmouse-v<versionName>-debug.apk."
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("app-debug.apk")
        rename("app-debug\\.apk", debugApkName)
    }
    into(rootProject.layout.projectDirectory.dir("apks"))
}

// Hooked to packageDebug, which writes the APK and which both assembleDebug and
// installDebug run through, so either entry point refreshes the copy.
// named { } (not matching { }) keeps every other task unrealized.
tasks.named { it == "packageDebug" }.configureEach {
    finalizedBy(copyDebugApkToRoot)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jazzer.junit)
    // Real org.json for ReleaseFeedTest: the unit-test android.jar returns
    // default values from every method, including JSONObject's.
    testImplementation(libs.org.json)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Lock what ships: the debug and release runtime classpaths, in
// app/gradle.lockfile, so a build is reproducible and OSV-Scanner has the full
// list of artifacts that reach a device. Test and lint configurations are left
// unlocked on purpose: their tooling pulls its own, often old, libraries that
// never ship, and a vulnerability scan of them is noise. Update with:
// ./gradlew :app:dependencies --write-locks
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath") && !name.contains("Test")) {
        resolutionStrategy.activateDependencyLocking()
    }
}
