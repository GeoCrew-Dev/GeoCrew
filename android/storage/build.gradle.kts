import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Layers kept in files, read and written through GeoCrew-Native's GDAL (ADR 0004,
// 0005). An Android library because it needs the native library (ADR 0016).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Put there by scripts/fetch-native.sh. The app packages it; a library can only
// compile against a local AAR, since AGP does not bundle one AAR into another.
val nativeAar = rootProject.file("native/geocrew-native.aar")

android {
    namespace = "org.geocrew.storage"
    compileSdk = 36

    defaultConfig {
        minSdk = 31 // ADR 0002
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:project"))
    implementation(project(":core:layers"))
    compileOnly(files(nativeAar))
    androidTestImplementation(files(nativeAar))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(kotlin("test-junit"))
}
