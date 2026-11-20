plugins {
    id("spawnkit.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:engine"))
            api(project(":core:graphics"))
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(project(":core:templates"))
        }
        jvmTest.dependencies {
            // Offscreen Skia rendering for pixel-level renderer tests and screenshots.
            implementation(compose.desktop.currentOs)
        }
    }
}
