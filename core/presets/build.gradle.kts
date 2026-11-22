plugins {
    id("spawnkit.templates")
}

// Preset games (C10): presets/<id>/project.json, embedded as dev.spawnkit.presets.EmbeddedPresets.
spawnkitTemplates {
    directory.set("presets")
    packageName.set("dev.spawnkit.presets")
    objectName.set("EmbeddedPresets")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:editing"))
        }
        commonTest.dependencies {
            implementation(project(":core:gameplay"))
        }
    }
}
