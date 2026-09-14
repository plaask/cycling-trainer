import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.cyclingtrainer.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.cyclingtrainer.app"
        minSdk = 33
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.1"
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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    sourceSets {
        // Make the repo-root preset .zwo workouts available to JVM unit tests.
        // The path is relative to THIS module (app/), so the repo root is one
        // level up — "..", not "../.." (which resolved to D:\coding).
        getByName("test") {
            resources.srcDir("../preset-workouts")
        }
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
