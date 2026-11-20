import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        // Kotlin Toolchain wasm-js/app layout (issue #58): src/ and resources/ at the module root.
        webMain {
            kotlin.srcDir("src")
            resources.srcDir("resources")
        }
        commonMain.dependencies {
            implementation(project(":app:shared"))

            implementation(libs.compose.ui)
        }
    }
}

// index.html uses the Kotlin Toolchain's `{{kotlin.scripts}}` placeholder; Gradle's webpack bundle is `webApp.js`.
// Remove together with this Gradle build once the Toolchain owns the web creator (issue #58).
tasks.withType<ProcessResources>().configureEach {
    filesMatching("index.html") {
        filter { line -> line.replace("{{kotlin.scripts}}", "<script type=\"application/javascript\" src=\"webApp.js\"></script>") }
    }
}
