import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The edit buffer (ADR 0005): plain Kotlin, no Android, tested on the JVM (ADR 0016).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
