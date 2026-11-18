import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing config stays out of git: android/keystore.properties supplies it.
// The Lux3D key is entered by the user at runtime; debug builds may prefill a
// developer-only key from android/local.properties (git-ignored).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val debugApiKey = localProperties.getProperty("LUX3D_DEBUG_API_KEY").orEmpty()
fun String.quotedForBuildConfig(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.animo.ar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.animo.ar"
        minSdk = 24
        targetSdk = 36
        versionCode = 16
        versionName = "0.5.0"
        // Filament ships every ABI; phones are arm64 and the emulator is not used for AR.
        ndk { abiFilters += listOf("arm64-v8a") }
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
            buildConfigField("String", "LUX3D_DEBUG_KEY", "\"\"")
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "LUX3D_DEBUG_KEY", debugApiKey.quotedForBuildConfig())
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

    testImplementation("junit:junit:4.13.2")
    // JVM unit tests need a real org.json implementation (the Android jar stubs it).
    testImplementation("org.json:json:20240303")
}
