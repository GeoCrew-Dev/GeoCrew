import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Sync with a team's server, protocol v1 (ADR 0008, 0009): plain Kotlin, no Android, tested on the JVM (ADR 0016)
// against the cases protocol/testdata shares with the Go server.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
}
