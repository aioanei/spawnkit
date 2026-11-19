plugins {
    id("spawnkit.kmp.core")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core:runtime"))
    }
}
