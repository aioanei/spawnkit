plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.composeCompiler.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("spawnkitCore") {
            id = "spawnkit.kmp.core"
            implementationClass = "SpawnkitCorePlugin"
        }
        register("spawnkitCompose") {
            id = "spawnkit.kmp.compose"
            implementationClass = "SpawnkitComposePlugin"
        }
        register("spawnkitQuality") {
            id = "spawnkit.quality"
            implementationClass = "SpawnkitQualityPlugin"
        }
    }
}
