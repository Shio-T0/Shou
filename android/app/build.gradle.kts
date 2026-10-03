plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.shiot0.shou"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.shiot0.shou"
        minSdk = 26
        targetSdk = 34
        // CI overrides these from the git tag (-PversionCode / -PversionName) so
        // Obtainium sees a monotonically increasing version on each release.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    }

    // Release signing: in CI a keystore is materialised from secrets and pointed
    // at by SHOU_KEYSTORE; locally (no secrets) the release build falls back to
    // the debug key so `assembleRelease` still produces an installable APK.
    val keystorePath = System.getenv("SHOU_KEYSTORE")
    val hasReleaseKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()
    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("SHOU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SHOU_KEY_ALIAS")
                keyPassword = System.getenv("SHOU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        // Compose compiler that matches Kotlin 1.9.24 (see the top-level build file).
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // The remote UI is native Jetpack Compose.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    // Cover art + banners straight from AniList's CDN.
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Live kiosk state over the server's Socket.IO channel (Android ships org.json).
    implementation("io.socket:socket.io-client:2.1.1") {
        exclude(group = "org.json", module = "json")
    }
    // "Throw to phone": a real native player for the PC's episode (HLS or a plain file).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // Encrypted storage for the saved server keys / token.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // MediaSessionCompat + MediaStyle notification for lock-screen controls.
    implementation("androidx.media:media:1.7.0")
    // Periodic background check for newly-aired episodes.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
