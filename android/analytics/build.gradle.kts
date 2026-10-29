plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ald.ra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ald.ra"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // Ships as a debug build like the soak app: debuggable (run-as works for the key file and logs).
        debug { }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // The model file comes with the site pack (pushed to the device), not inside the APK.
    packaging { resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**") } }
}

dependencies {
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")  // the engine's config and events types
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")  // order codes at checkout; model bundled, works offline
    val litert = "1.4.2"
    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu-api:$litert")
}
