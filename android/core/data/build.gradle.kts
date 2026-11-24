plugins {
    alias(libs.plugins.geocrew.android.library)
    alias(libs.plugins.geocrew.android.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

room {
    // Exported schemas are committed so future migrations can be tested.
    // The Room plugin writes one copy per variant, so parallel debug and
    // release builds no longer race on the same file.
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:formats"))
    api(project(":core:network"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
}
