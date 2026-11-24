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

// One module per screen or flow.
include(":feature:map")
include(":feature:settings")
