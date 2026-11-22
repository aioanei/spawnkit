rootProject.name = "SpawnKit"

pluginManagement {
    includeBuild("build-logic")
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

include(":ai")
include(":app:androidApp")
include(":app:desktopApp")
include(":app:shared")
include(":app:webApp")
include(":core:model")
include(":core:templates")
include(":core:engine")
include(":core:runtime")
include(":core:program")
include(":core:graphics")
include(":core:editing")
include(":core:gameplay")
include(":core:presets")
include(":player:playerKit")
include(":samples:runtimeProbe")
include(":benchmarks:runtimeStorage")
include(":render")
include(":server")
include(":tools:agentRecorder")
