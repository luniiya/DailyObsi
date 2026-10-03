import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key material lives outside version control (see .gitignore);
// keystore.properties itself is generated once via `keytool` and never committed.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.ayaya.dailyobsi"
    compileSdk = 35
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.ayaya.dailyobsi"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by Nextcloud's Android-SingleSignOn (checked by AAR metadata).
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // AGP's release build type runs "lint vital" by default, blocking
    // assembleRelease on it. One specific bundled detector
    // (NonNullableMutableLiveDataDetector) hard-crashes here on a real
    // AGP-8.7.3-vs-newer-Kotlin-analysis-API incompatibility, unrelated to
    // this app's code -- not a normal lint warning to fix, an exception in
    // the checker itself. This isn't published anywhere that needs
    // Play-Store-grade lint gating, so just don't run it as part of release.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        // These AndroidX detectors are binary-incompatible with the
        // Kotlin analysis API bundled with AGP 8.7.3 and crash lint itself.
        disable += setOf(
            "NullSafeMutableLiveData",
            "FrequentlyChangingValue",
            "RememberInComposition",
            "AutoboxingStateCreation",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Bumped from 2024.12.01 specifically for the new TextFieldState-based
    // BasicTextField (androidx.compose.foundation.text.input), stable since
    // Compose Foundation 1.8.0 -- lets the edit-mode checkbox overlay share a
    // hoisted ScrollState with the actual text field, so overlay positions
    // stay correct while scrolling instead of guessing at scroll offset.
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    // The Todo widget's background refresh. Same version Glance already pulls in.
    implementation("androidx.work:work-runtime-ktx:2.7.1")

    // Renders ![[embed]] images straight from a content:// SAF Uri, no manual bitmap decoding.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Nextcloud Daily Todo tab: borrows the account logged in to the Nextcloud
    // Files app and proxies requests through it (docs/nextcloud-daily-todo.md).
    // Pinned to 1.3.2: 1.3.3+ is built with Kotlin 2.2 / compileSdk 35+ deps
    // that this toolchain (Kotlin 2.0.21, AGP 8.7.3) can't consume. Brings Gson.
    implementation("com.github.nextcloud:Android-SingleSignOn:1.3.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
