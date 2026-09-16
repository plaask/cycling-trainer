import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing material lives OUTSIDE the repository, addressed by
 * app/key.properties (gitignored). Keeping the keystore out of the tree means
 * it cannot be committed even by an accidental `git add -f`, and the password
 * is never written into build files.
 *
 * The file looks like:
 *   storeFile=/absolute/path/to/cycling-trainer.jks
 *   storePassword=...
 *   keyAlias=cyclingtrainer
 *   keyPassword=...
 *
 * Without it the release build is simply left unsigned instead of failing, so
 * a fresh clone can still run `assembleDebug` and the tests.
 */
val releaseSigning: Properties? = rootProject.file("app/key.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "io.github.cyclingtrainer.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                // minSdk 33 -> every target device supports APK Signature
                // Scheme v3, which also carries key-rotation proof.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.cyclingtrainer.app"
        minSdk = 33
        targetSdk = 37
        versionCode = 6
        versionName = "0.4.1"
        // The settings screen shows BuildConfig.VERSION_NAME, so this is the
        // single place the app version lives.

        // Only arm64 devices are supported. This is about hygiene and install
        // size, NOT app size: the APK's only native library is a 10 KB copy of
        // libandroidx.graphics.path.so, so dropping the other three ABIs saves
        // ~27 KB out of 23.5 MB. It also removes the silent failure mode where
        // a 32-bit device installs fine and then fails in a native call.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // R8 strips the ~90% of the dex that this app never calls. Almost
            // all of it is Compose runtime/UI/foundation/material3 code pulled
            // in by the few composables actually used. shrinkResources then
            // removes resources no reachable code references.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real release key when one is configured; otherwise left unsigned
            // (an unsigned release APK cannot be installed, which is the safe
            // default — never fall back to the debug key, whose password is
            // public and would let anyone sign a fake update).
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // AGP 8 defaults this off; the About box reads BuildConfig.VERSION_NAME
        // so the displayed version follows the build file.
        buildConfig = true
    }

    // Test fixtures live in app/src/test/resources (the default location); the
    // app ships no bundled workouts. Courses come from a folder the user picks
    // via the system directory picker (see workout/CourseSource.kt).

    lint {
        // lintVitalAnalyzeRelease downloads a lint model and fails on this
        // machine with a TLS handshake error (the local network breaks some
        // TLS paths), which blocks every release build.
        // The normal `lint` task still runs and still reports.
        checkReleaseBuilds = false
    }
}

// A hung test must fail the build, not block it forever: a spinning test
// thread used to keep the JVM alive indefinitely (FitWriterTest's FIT walker
// looped in place on a data message), which made the whole test task hang.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // NOTE: material-icons-extended is deliberately NOT used. Its aar is 34 MB
    // (5 variants x ~2100 icons) and a debug build does not shrink, so it alone
    // accounted for ~40% of the APK. The handful of icons this app shows all
    // come from material-icons-core, which material3 already depends on.
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kxml2)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
