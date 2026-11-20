plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "dev.spawnkit"
version = "1.0.0"
application {
    mainClass = "dev.spawnkit.ApplicationKt"
}

sourceSets.main { resources.srcDir(rootProject.tasks.named("bundleWebPlayer")) }

dependencies {
    implementation(project(":core:editing"))
    implementation(project(":core:gameplay"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
