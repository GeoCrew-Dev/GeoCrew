plugins {
    alias(libs.plugins.geocrew.android.application)
}

// A stand-alone analysis plugin (geocrew-protocol docs/plugins.md). It uses
// nothing from GeoCrew, only the platform's org.json, to show that any app
// can provide a tool.
android {
    namespace = "org.geocrew.sample.plugin"
    defaultConfig {
        applicationId = "org.geocrew.sample.plugin"
        versionCode = 1
        versionName = "1.0"
    }
}
