rootProject.name = "TeamTalk"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":shared")
include(":server")
include(":app")
include(":android")
include(":desktop")
