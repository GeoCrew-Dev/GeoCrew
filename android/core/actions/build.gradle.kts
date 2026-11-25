plugins {
    alias(libs.plugins.geocrew.android.library)
    alias(libs.plugins.geocrew.android.hilt)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:formats"))
    implementation(project(":core:sensors"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
}
