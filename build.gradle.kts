plugins {
    // Load plugins once in the root classloader; modules apply them via convention plugins or aliases.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.spotless) apply false
    id("spawnkit.quality")
}

// Gradle has no Wasm player module. Both builds ship the same distribution produced by the pinned Toolchain.
val kotlinWrapper = rootProject.file(if (System.getProperty("os.name").startsWith("Windows")) "kotlin.bat" else "kotlin")
val buildWebPlayer by tasks.registering(Exec::class) {
    commandLine(kotlinWrapper, "build", "-m", "webPlayer", "-v", "release")
}
val bundleWebPlayer by tasks.registering(Exec::class) {
    dependsOn(buildWebPlayer)
    commandLine(kotlinWrapper, "task", ":server:bundle@web-player-bundle")
    inputs.dir(layout.buildDirectory.dir("tasks/_webPlayer_buildWasmJsAppWasmJsRelease"))
    inputs.dir("plugins/web-player-bundle")
    outputs.dir(layout.buildDirectory.dir("tasks/_server_bundle@web-player-bundle"))
}
