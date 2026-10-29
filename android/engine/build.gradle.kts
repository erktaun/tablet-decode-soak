// Pure Kotlin (JVM) engine: all of the restaurant-analytics logic, no Android. Runs on the laptop (replay, tests)
// and inside the Android app.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// Bytecode for Java 17, which the Android app also targets; builds with whichever JDK runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
}

application { mainClass.set("com.ald.ra.engine.ReplayKt") }

tasks.test { useJUnitPlatform() }
