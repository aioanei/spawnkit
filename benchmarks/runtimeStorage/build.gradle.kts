import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Runtime storage benchmark harness (issue #26). Not shipped; measures only the public World API so the same
// harness can be run against different store implementations. Android/iOS are omitted: they need device runs.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set("dev.spawnkit.benchmarks.storage.JvmMainKt")
        }
    }
    js {
        nodejs()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }
    mingwX64 {
        binaries.executable { entryPoint = "dev.spawnkit.benchmarks.storage.main" }
    }
    linuxX64 {
        binaries.executable { entryPoint = "dev.spawnkit.benchmarks.storage.main" }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":samples:runtimeProbe"))
        }
    }
}
