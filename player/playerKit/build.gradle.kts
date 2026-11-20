plugins {
    id("spawnkit.kmp.compose")
}

// Shared game player (C6). Built by the Kotlin Toolchain (module.yaml) and, while the builds coexist, by Gradle,
// because app/shared depends on it.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:editing"))
            api(project(":core:gameplay"))
            api(project(":render"))
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}
