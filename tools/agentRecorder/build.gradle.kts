// Records live assistant sessions for labelled replays (C7, issue #69). See tools/agentRecorder/module.yaml.
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

application {
    mainClass = "dev.spawnkit.tools.recorder.MainKt"
}

dependencies {
    implementation(project(":ai"))
    implementation(project(":core:presets"))
    testImplementation(libs.kotlin.testJunit)
}
