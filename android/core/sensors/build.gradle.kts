plugins {
    alias(libs.plugins.geocrew.android.library)
    alias(libs.plugins.geocrew.android.hilt)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
}
