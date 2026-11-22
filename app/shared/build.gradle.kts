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
        androidMain.get().kotlin.srcDir("src@jvmAndAndroid")
        jvmMain.get().kotlin.srcDir("src@jvmAndAndroid")
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            implementation(project(":ai"))
            implementation(project(":core:editing"))
            implementation(project(":core:gameplay"))
            implementation(project(":core:presets"))
            implementation(project(":render"))
            implementation(project(":player:playerKit"))
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        // Assistant keys on desktop: Windows DPAPI through JNA (Apache-2.0 / LGPL-2.1).
        jvmMain.dependencies { implementation(libs.jna.platform) }
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
