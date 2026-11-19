plugins {
    id("spawnkit.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:engine"))
        }
        commonTest.dependencies {
            implementation(project(":core:templates"))
        }
    }
}
