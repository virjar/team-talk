rootProject.name = "TeamTalk"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("org.jetbrains.kotlin.")) {
                useVersion("2.4.10")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":shared")
include(":protocol")
include(":richeditor")
include(":rpc-processor")
include(":server")
include(":app")
include(":android")
include(":desktop")
