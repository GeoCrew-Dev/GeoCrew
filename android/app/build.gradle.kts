import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.geocrew.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.geocrew.android"
        minSdk = 31 // ADR 0002
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // 64-bit only (ADR 0002)
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// GeoCrew-Native's AAR (GDAL, PROJ, GEOS), put there by scripts/fetch-native.sh (ADR 0016).
val nativeAar = rootProject.file("native/geocrew-native.aar")

tasks.named("preBuild") {
    doFirst { check(nativeAar.exists()) { "${nativeAar.path} is missing: run android/scripts/fetch-native.sh" } }
}

dependencies {
    implementation(files(nativeAar))
    implementation(project(":storage"))
    implementation(project(":core:files"))
    implementation(libs.maplibre)
    implementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
}
