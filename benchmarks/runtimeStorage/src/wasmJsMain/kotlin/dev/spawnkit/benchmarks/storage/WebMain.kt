package dev.spawnkit.benchmarks.storage

/** Node.js entry point; allocation is not exposed portably and is reported as unavailable. */
fun main() {
    runStorageBenchmarks("Kotlin/Wasm on Node.js", allocatedBytes = null)
}
