plugins {
    id("spawnkit.kmp.core")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core:runtime"))
        api(project(":core:graphics"))
        api(project(":core:program"))
        api(project(":core:editing"))
    }
}
