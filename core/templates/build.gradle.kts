plugins {
    id("spawnkit.templates")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":core:model"))
        }
    }
}
