import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "dev.spawnkit"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.spawnkit"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // Kotlin Toolchain android/app layout (issue #58): src/AndroidManifest.xml, src/ and res/ at the module root.
    sourceSets.getByName("main") {
        manifest.srcFile("src/AndroidManifest.xml")
        kotlin.srcDir("src")
        res.srcDir("res")
    }
}

abstract class CopyWebPlayerResources : DefaultTask() {
    @get:InputDirectory
    abstract val bundle: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copyBundle() {
        val file = "spawnkit/web-player.zip"
        bundle
            .file(file)
            .get()
            .asFile
            .copyTo(output.file(file).get().asFile, overwrite = true)
    }
}

androidComponents.onVariants { variant ->
    val resources =
        tasks.register<CopyWebPlayerResources>("copy${variant.name.replaceFirstChar { it.uppercase() }}WebPlayer") {
            dependsOn(rootProject.tasks.named("bundleWebPlayer"))
            bundle.set(rootProject.layout.buildDirectory.dir("tasks/_server_bundle@web-player-bundle"))
        }
    variant.sources.resources?.addGeneratedSourceDirectory(resources, CopyWebPlayerResources::output)
}
