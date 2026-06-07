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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    // Encrypted storage for the saved server keys / token.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // MediaSessionCompat + MediaStyle notification for lock-screen controls.
    implementation("androidx.media:media:1.7.0")
    // Periodic background check for newly-aired episodes.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
