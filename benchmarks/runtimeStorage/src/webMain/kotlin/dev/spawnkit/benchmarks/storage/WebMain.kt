package dev.spawnkit.benchmarks.storage

/** Node.js entry point for JS and Wasm; allocation is not exposed portably and is reported as unavailable. */
fun main() {
    runStorageBenchmarks("web (Kotlin/JS or Kotlin/Wasm on Node.js)", allocatedBytes = null)
}
