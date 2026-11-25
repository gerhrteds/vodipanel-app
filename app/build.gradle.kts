plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.vodipanel"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.vodipanel"
        minSdk = 24
        targetSdk = 34
        // CI passes VERSION_CODE / VERSION_NAME; the number must grow with every release
        val vc = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionCode = vc
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        buildConfigField("int", "APP_VERSION_CODE", vc.toString())
        // panel domain
        buildConfigField("String", "PANEL_URL", "\"https://apk.vodisnapp.ir\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Real key when KEYSTORE_FILE is provided (needed so users can update over older installs)
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // sing-box core (build libbox.aar yourself, see BUILD.md) -> put it in app/libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

}
