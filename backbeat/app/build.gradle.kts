plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI stamps each build with its run number so a newer APK always installs over an older one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// A tagged release names itself after the tag (v1.2.0 → 1.2.0); other builds use the run number.
val buildVersionName = System.getenv("BACKBEAT_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.$buildNumber"

// The family signing key comes from CI secrets, never from the repository. It is the same
// key as Djungleskwidgets and Funhouse, so the secrets keep that first app's GLIMMER_* names.
val familyKeystore = System.getenv("GLIMMER_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "app.djungle.backbeat"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.djungle.backbeat"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = buildVersionName
    }

    signingConfigs {
        if (familyKeystore != null) {
            create("family") {
                storeFile = familyKeystore
                storePassword = System.getenv("GLIMMER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GLIMMER_KEY_ALIAS") ?: "glimmer"
                keyPassword = System.getenv("GLIMMER_KEY_PASSWORD") ?: System.getenv("GLIMMER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the family key the APK still installs, but every CI machine has its own
            // debug key, so such builds cannot update one another in place.
            signingConfig = signingConfigs.findByName("family") ?: signingConfigs.getByName("debug")
        }
        // The release build plus a demo activity, used by CI to screenshot the app on an emulator.
        create("preview") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
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
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
