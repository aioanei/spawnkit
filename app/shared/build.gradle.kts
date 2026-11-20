plugins {
    id("spawnkit.kmp.compose")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            implementation(project(":core:editing"))
            implementation(project(":render"))
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        jvmTest.dependencies {
            // Compose UI tests and offscreen screenshots of the builder at phone and desktop sizes.
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
