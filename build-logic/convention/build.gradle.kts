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
        register("spawnkitTemplates") {
            id = "spawnkit.templates"
            implementationClass = "SpawnkitTemplatesPlugin"
        }
        register("spawnkitQuality") {
            id = "spawnkit.quality"
            implementationClass = "SpawnkitQualityPlugin"
        }
    }
}

// On Windows developer machines we observed an empty compileKotlin result being stored in the local build cache and
// restored afterwards (plugins then fail with "Could not find implementation class"). build-logic compiles in seconds,
// so it is never cached.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    outputs.cacheIf { false }
}
