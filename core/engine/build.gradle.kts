plugins {
    id("spawnkit.kmp.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
        }
        commonTest.dependencies {
            implementation(project(":core:templates"))
        }
    }
}
