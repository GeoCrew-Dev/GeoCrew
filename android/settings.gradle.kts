pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GeoCrew"

include(":app")

// Pure Kotlin, no Android: domain model, geodesy, file formats, protocol client.
include(":core:model")
include(":core:formats")

// Android infrastructure shared by features.
include(":core:data")
include(":core:ui")
include(":core:map")
include(":core:sensors")
include(":core:network")
include(":core:gis")
include(":core:scan")
include(":core:speech")
include(":core:actions")
include(":feature:assistant")

// One module per screen or flow.
include(":feature:map")
include(":feature:settings")
include(":feature:record")
include(":feature:compass")
include(":feature:transfer")
include(":feature:team")
include(":feature:projects")
include(":feature:analysis")
// Example of an analysis plugin in a separate app (docs/plugins.md in geocrew-protocol).
include(":samples:plugin")
