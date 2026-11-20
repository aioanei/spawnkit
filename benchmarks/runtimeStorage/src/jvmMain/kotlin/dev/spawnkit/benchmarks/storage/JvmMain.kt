package dev.spawnkit.benchmarks.storage

import java.lang.management.ManagementFactory

/** JVM entry point; thread allocation comes from the HotSpot `com.sun.management.ThreadMXBean` extension. */
fun main() {
    val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    val allocation = threads?.takeIf { it.isThreadAllocatedMemorySupported }?.let { bean -> { bean.currentThreadAllocatedBytes } }
    val vm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"
    val os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
    runStorageBenchmarks("jvm ($vm; $os)", allocation)
}
