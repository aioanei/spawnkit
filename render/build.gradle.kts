plugins {
    id("spawnkit.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:engine"))
        }
    }
}
