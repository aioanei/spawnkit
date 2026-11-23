plugins {
    id("spawnkit.kmp.client")
}

// Koog AI Studio (C7). Built by the Kotlin Toolchain (module.yaml) and, while the builds coexist, by Gradle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:editing"))
            api(project(":core:gameplay"))
            // The preset games are the Game Architect's worked examples (C13a).
            implementation(project(":core:presets"))
            api(libs.kotlinx.coroutines.core)
            api(libs.koog.anthropic)
            api(libs.koog.executor)
            implementation(libs.koog.httpKtor)
            api(libs.ktor.clientCore)
        }
        jvmMain.dependencies { implementation(libs.ktor.clientOkhttp) }
        androidMain.dependencies { implementation(libs.ktor.clientOkhttp) }
        iosMain.dependencies { implementation(libs.ktor.clientDarwin) }
        webMain.dependencies { implementation(libs.ktor.clientJs) }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
