import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release config stays out of git: android/keystore.properties supplies signing, and
// ANIMO_API_BASE_URL supplies the deployed backend (pass with -P or the env var).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseApiBaseUrl = (project.findProperty("ANIMO_API_BASE_URL") as String?)
    ?: System.getenv("ANIMO_API_BASE_URL")
    ?: ""
val buildingRelease = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true) || it == "build"
}
if (buildingRelease) {
    check(releaseApiBaseUrl.isNotBlank()) {
        "release 构建需要 ANIMO_API_BASE_URL（例如 -PANIMO_API_BASE_URL=https://api.example.com）"
    }
    check(releaseApiBaseUrl.startsWith("https://")) {
        "release 构建的后端地址必须是 https://，当前为：$releaseApiBaseUrl"
    }
}
fun String.quotedForBuildConfig(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.animo.ar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.animo.ar"
        minSdk = 24
        targetSdk = 36
        versionCode = 15
        versionName = "0.4.3"
        // Filament ships every ABI; phones are arm64 and the emulator is not used for AR.
        ndk { abiFilters += listOf("arm64-v8a") }
        // Cloud Anchors API key (Google Cloud project with the ARCore API enabled).
        manifestPlaceholders["arcoreApiKey"] = (project.findProperty("ARCORE_API_KEY") as String?) ?: ""
        // Debug talks to adb reverse / LAN over HTTP; release is HTTPS-only.
        manifestPlaceholders["usesCleartextTraffic"] = "true"
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "API_BASE_URL", releaseApiBaseUrl.quotedForBuildConfig())
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "http://127.0.0.1:8787".quotedForBuildConfig())
            manifestPlaceholders["usesCleartextTraffic"] = "true"
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

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ARCore + Filament wrapper (Sceneform successor). 3.6.2 is the newest line that does not
    // require compileSdk 37, which this machine's SDK does not have.
    implementation("io.github.sceneview:arsceneview:3.6.2")
}
