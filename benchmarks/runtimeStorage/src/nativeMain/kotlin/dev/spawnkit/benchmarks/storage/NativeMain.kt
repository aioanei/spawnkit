package dev.spawnkit.benchmarks.storage

import kotlin.experimental.ExperimentalNativeApi

/** Kotlin/Native entry point; allocation is reported as unavailable. */
@OptIn(ExperimentalNativeApi::class)
fun main() {
    runStorageBenchmarks(
        "native (${Platform.osFamily} ${Platform.cpuArchitecture}, debug=${Platform.isDebugBinary})",
        allocatedBytes = null,
    )
}
