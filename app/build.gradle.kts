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

// The in-app update check (Settings > Updates) is the app's only network path.
// F-Droid builds pass -PopenmouseUpdater=false so the whole feature, UI and
// launch check included, compiles out: F-Droid signs with its own key, so an
// APK from GitHub Releases could never install over it anyway.
val updaterEnabled = providers.gradleProperty("openmouseUpdater").orElse("true").get().toBoolean()

android {
    namespace = "io.github.owenpkent.openmouse"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.owenpkent.openmouse"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

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
            System.getenv("JAZZER_FUZZ")?.let {
                test.environment("JAZZER_FUZZ", it)
                test.setForkEvery(1)
            }
            // Jazzer attaches its agent at runtime; JDK 21+ warns unless this is set.
            test.jvmArgs("-XX:+EnableDynamicAgentLoading")
        }
    }
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
