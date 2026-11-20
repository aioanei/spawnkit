package dev.spawnkit.benchmarks.storage

import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.World
import dev.spawnkit.samples.runtimeprobe.Link
import dev.spawnkit.samples.runtimeprobe.StorageScenario
import kotlin.time.TimeSource

/** One measured operation at one entity count. */
internal class Measurement(
    val case: String,
    val entities: Int,
    val nanosPerOperation: Double,
    val bytesPerOperation: Double?,
)

/**
 * Runs every case through the public runtime API only. Each case is warmed up, then sampled [SAMPLES] times;
 * the median sample is reported. [allocatedBytes] reports cumulative allocation for the current thread when the
 * platform exposes it, otherwise allocation is reported as unavailable rather than estimated.
 */
internal fun runStorageBenchmarks(
    platform: String,
    allocatedBytes: (() -> Long)?,
    sizes: List<Int> = listOf(100, 1_000, 10_000),
    out: (String) -> Unit = ::println,
) {
    out("# SpawnKit runtime storage benchmark")
    out("platform=$platform kotlin=${KotlinVersion.CURRENT} samples=$SAMPLES")
    out("| case | entities | ns/op | bytes/op |")
    out("|---|---:|---:|---:|")
    var sink = 0L
    // One discarded pass over every case and size so JIT/tiering does not favour cases measured later.
    for (size in sizes) {
        for (case in cases) sink += measure(case, size, allocatedBytes) { sink += it }.entities
    }
    for (size in sizes) {
        for (case in cases) {
            val result = measure(case, size, allocatedBytes) { sink += it }
            val bytes = result.bytesPerOperation?.let { formatNumber(it) } ?: "n/a"
            out("| ${result.case} | ${result.entities} | ${formatNumber(result.nanosPerOperation)} | $bytes |")
        }
    }
    out("sink=$sink")
}

private class Case(
    val name: String,
    /** Operations per sample for a given entity count. */
    val operations: (Int) -> Int,
    /** Builds untimed state for one sample. */
    val prepare: (Int) -> Prepared,
)

private class Prepared(
    val world: World,
    val run: (Int) -> Long,
)

private fun scenarioWorld(
    size: Int,
    churnPerTick: Int,
): Pair<StorageScenario, Pair<dev.spawnkit.runtime.Runtime, World>> {
    val builder = RuntimeBuilder()
    val scenario = StorageScenario(builder, churnPerTick = churnPerTick)
    val runtime = builder.build()
    val world = runtime.createWorld()
    scenario.populate(world, size)
    return scenario to (runtime to world)
}

private val cases =
    listOf(
        // Spawn plus 1-3 component attachments per entity, outside a tick. One operation = one entity.
        Case("spawn+attach", operations = { it }) { size ->
            val builder = RuntimeBuilder()
            val scenario = StorageScenario(builder, churnPerTick = 0)
            val world = builder.build().createWorld()
            Prepared(world) { _ ->
                scenario.populate(world, size)
                world.entityCount.toLong()
            }
        },
        // Two-component query over dense components. One operation = one query.
        Case("query dense (position+velocity)", operations = { maxOf(1, 20_000 / it) }) { size ->
            val (scenario, pair) = scenarioWorld(size, 0)
            val world = pair.second
            Prepared(world) { ops ->
                var total = 0L
                repeat(ops) { total += world.entities(scenario.position, scenario.velocity).size }
                total
            }
        },
        // Query over a sparse component (about one quarter of entities). One operation = one query.
        Case("query sparse (marker)", operations = { maxOf(1, 20_000 / it) }) { size ->
            val (scenario, pair) = scenarioWorld(size, 0)
            val world = pair.second
            Prepared(world) { ops ->
                var total = 0L
                repeat(ops) { total += world.entities(scenario.marker).size }
                total
            }
        },
        // Query over a rare component (2% of entities), below the 1/16 scan threshold. One operation = one query.
        Case("query rare (link, 2%)", operations = { maxOf(1, 20_000 / it) }) { size ->
            val (scenario, pair) = scenarioWorld(size, 0)
            val world = pair.second
            val entities = world.entities()
            for (i in entities.indices step 50) {
                world.set(entities[i], scenario.link, Link(entities[(i + 1) % entities.size]))
            }
            Prepared(world) { ops ->
                var total = 0L
                repeat(ops) { total += world.entities(scenario.link).size }
                total
            }
        },
        // Component read per live entity. One operation = one get.
        Case("get", operations = { it * maxOf(1, 2_000 / it) }) { size ->
            val (scenario, pair) = scenarioWorld(size, 0)
            val world = pair.second
            val entities = world.entities()
            Prepared(world) { ops ->
                var total = 0L
                var i = 0
                repeat(ops) {
                    total += world.get(entities[i], scenario.position)?.x ?: 0L
                    i = if (i + 1 == entities.size) 0 else i + 1
                }
                total
            }
        },
        // Steady fixed tick: motion system queries and mutates; no structural change. One operation = one tick.
        Case("step steady", operations = { maxOf(1, 20_000 / it) }) { size ->
            val (_, pair) = scenarioWorld(size, 0)
            val world = pair.second
            Prepared(world) { ops ->
                repeat(ops) { world.step() }
                world.tick
            }
        },
        // Fixed tick with size/10 deferred structural edits. One operation = one tick.
        Case("step churn (size/10 edits)", operations = { maxOf(1, 10_000 / it) }) { size ->
            val (_, pair) = scenarioWorld(size, maxOf(1, size / 10))
            val world = pair.second
            Prepared(world) { ops ->
                repeat(ops) { world.step() }
                world.entityCount.toLong()
            }
        },
        // Full checkpoint capture. One operation = one snapshot.
        Case("snapshot", operations = { maxOf(1, 2_000 / it) }) { size ->
            val (_, pair) = scenarioWorld(size, 0)
            val world = pair.second
            Prepared(world) { ops ->
                var total = 0L
                repeat(ops) { total += world.snapshot().entityCount }
                total
            }
        },
        // Restore into a fresh world. One operation = one restore.
        Case("restore", operations = { maxOf(1, 2_000 / it) }) { size ->
            val (_, pair) = scenarioWorld(size, 0)
            val (runtime, world) = pair
            val snapshot = world.snapshot()
            Prepared(world) { ops ->
                var total = 0L
                repeat(ops) { total += runtime.restore(snapshot).entityCount }
                total
            }
        },
    )

private fun measure(
    case: Case,
    size: Int,
    allocatedBytes: (() -> Long)?,
    sink: (Long) -> Unit,
): Measurement {
    val operations = case.operations(size)
    repeat(WARMUP) {
        val prepared = case.prepare(size)
        sink(prepared.run(operations))
    }
    val nanos = DoubleArray(SAMPLES)
    val bytes = DoubleArray(SAMPLES)
    for (sample in 0 until SAMPLES) {
        val prepared = case.prepare(size)
        val before = allocatedBytes?.invoke() ?: 0L
        val mark = TimeSource.Monotonic.markNow()
        val result = prepared.run(operations)
        val elapsed = mark.elapsedNow().inWholeNanoseconds
        val after = allocatedBytes?.invoke() ?: 0L
        sink(result)
        nanos[sample] = elapsed.toDouble() / operations
        bytes[sample] = (after - before).toDouble() / operations
    }
    nanos.sort()
    bytes.sort()
    return Measurement(case.name, size, nanos[SAMPLES / 2], if (allocatedBytes == null) null else bytes[SAMPLES / 2])
}

private fun formatNumber(value: Double): String {
    val rounded = if (value >= 100) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()
    return rounded
}

private const val WARMUP = 5
private const val SAMPLES = 9
