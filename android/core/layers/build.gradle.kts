import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The layer list as a tree (ADR 0017): plain Kotlin, no Android, tested on the JVM (ADR 0016).
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
    testImplementation(kotlin("test-junit"))
}
