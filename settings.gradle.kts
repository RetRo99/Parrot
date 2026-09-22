rootProject.name = "Parrot"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":composeApp")
include(":base")
include(":base-ui")
include(":translations")
include(":lib:network:api")
include(":lib:network:implementation")
include(":feature:login:data")
include(":feature:login:domain")
include(":feature:login:ui")
include(":feature:home:data")
include(":feature:home:domain")
include(":feature:home:ui")
include(":lib:preferences:api")
include(":lib:preferences:implementation")
include(":lib:analytics:api")
include(":lib:analytics:implementation")
include(":lib:database:api")
include(":lib:database:implementation")
include(":feature:auth:domain")
include(":feature:auth:data")
include(":feature:books:data")
include(":feature:books:domain")
include(":feature:books:ui")
include(":feature:reader:data")
include(":feature:reader:domain")
include(":feature:reader:ui")
include(":feature:settings:data")
include(":feature:settings:domain")
include(":feature:settings:ui")
include(":feature:statistics:data")
include(":feature:statistics:domain")
include(":feature:statistics:ui")
include(":lib:server:api")
include(":lib:server:implementation")
include(":lib:server-storyteller")
include(":lib:server-audiobookshelf")
include(":lib:server-local")
include(":lib:server-parrot-cloud")
include(":lib:user:api")
include(":lib:user:implementation")
include(":lib:cloud:implementation")
include(":feature:cloud-account:domain")
include(":feature:cloud-account:data")
include(":feature:cloud-account:ui")
include(":feature:sync:domain")
