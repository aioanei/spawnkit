import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Configures the SpawnKit multiplatform target set.
 *
 * @param nativeDesktop adds `mingwX64` and `linuxX64` (pure-Kotlin core modules only; Compose has no such targets).
 * @param nodeTests adds Node.js test environments for JS and Wasm (Compose modules need a browser instead).
 */
internal fun Project.configureSpawnkitTargets(
    kmp: KotlinMultiplatformExtension,
    nativeDesktop: Boolean,
    nodeTests: Boolean,
) {
    configureKotlinToolchainLayout(kmp)
    kmp.jvm()
    kmp.iosArm64()
    kmp.iosSimulatorArm64()
    kmp.js {
        browser()
        if (nodeTests) nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    kmp.wasmJs {
        browser()
        if (nodeTests) nodejs()
    }
    if (nativeDesktop) {
        kmp.mingwX64()
        kmp.linuxX64()
    }
    (kmp as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
        namespace = spawnkitNamespace()
        compileSdk = intVersion("android-compileSdk")
        minSdk = intVersion("android-minSdk")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        withHostTest {}
    }
    kmp.sourceSets.getByName("commonTest").dependencies {
        implementation(libs.findLibrary("kotlin-test").get())
    }
}
