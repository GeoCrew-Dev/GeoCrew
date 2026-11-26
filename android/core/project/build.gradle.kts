import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Projects and their layer templates (ADR 0004): plain Kotlin, no Android, tested on the JVM (ADR 0016).
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
    api(project(":core:edit"))
    testImplementation(kotlin("test-junit"))
}
