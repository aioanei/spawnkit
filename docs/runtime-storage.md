# Runtime component storage — R1c comparison and decision

Tracked by [issue #26](https://github.com/aioanei/spawnkit/issues/26). This completes the storage half of the
remaining R1 work in the [revised plan](plans/2026-11-20-engine-foundation-revision.md#r1--prove-runtime-program-and-hosting-boundaries).
The program/host-effect comparison is still open and separate.

## Decision

`core/runtime` keeps a **SpawnKit-owned store**. The original per-component `HashMap<Int, Any>` is replaced by
sparse-set columns (`ComponentColumn`), indexed by component registration ordinal. Fleks is **rejected at screening**:
its published 2.16 artifacts do not cover required targets. The public `World` API, slot-ordered query semantics,
deferred commands and checkpoint/hash format are unchanged. A golden recorded from the original store before any
change passes unchanged on the new store.

## Candidate screening

The plan rejects a candidate at its first failed mandatory criterion. Required targets come first.

| Criterion | Existing / sparse-set store | Fleks 2.16 (`io.github.quillraven.fleks:Fleks`) |
|---|---|---|
| License | Project code | MIT (POM) |
| Maintenance | Project-owned | Active: Maven metadata last updated 2026-11-18; 2.6–2.16 published |
| Kotlin toolchain | Kotlin 2.4.21 (project) | Requires `kotlin-stdlib` 2.4.21 (Gradle module metadata); compatible |
| jvm | ✅ | ✅ published variant |
| android | ✅ (common Kotlin; Android host tests) | ⚠️ no Android variant; JVM variant only, consumption not verified |
| iosArm64 / iosSimulatorArm64 | ✅ (common Kotlin; iOS CI job) | ❌ no iOS variant in 2.16, and no iOS artifact directory for any version |
| js | ✅ | ❌ `Fleks-js` latest is 2.12; 2.16 publishes no JS variant |
| wasmJs | ✅ | ✅ |
| mingwX64 / linuxX64 | ✅ | ✅ |
| **Result** | **Eligible** | **Rejected: missing iOS and JS** |

Fleks source and published artifacts were inspected on 2026-11-20:

- the Maven Central directory listing for `io/github/quillraven/fleks/`;
- `Fleks-2.16.module` variants: `jvm`, `linuxArm64`, `linuxX64`, `macosArm64`, `mingwX64`, `wasmJs`;
- `Fleks-2.16.pom`.

Adopting Fleks would therefore mean building, publishing and maintaining JS and iOS artifacts from a fork for every
upgrade, or dropping required targets. The plan forbids dropping targets and counts fork maintenance as an ownership
cost. Fleks was not integrated or benchmarked. Published Fleks performance claims are not SpawnKit evidence, so
none are cited.

## Common contract and equivalence evidence

[`StorageScenario`](../samples/runtimeProbe/src/commonMain/kotlin/dev/spawnkit/samples/runtimeprobe/StorageScenario.kt)
lives in the separately compiled consumer and uses public APIs only. It covers:

- four external component types: dense, subset, sparse marker, and an entity reference that can go stale;
- a motion system that folds query visit order into private checkpointed state;
- a churn system that uses a seeded 64-bit LCG to record spawn/destroy/attach/remove commands each tick.

[`StorageEquivalenceTest`](../samples/runtimeProbe/src/commonTest/kotlin/dev/spawnkit/samples/runtimeprobe/StorageEquivalenceTest.kt)
pins the following, with goldens recorded on JVM against the **original map-based store** (commit `9a49597`) before
any storage change:

- the final entity count (298) and state hash (`-1536454527644900448`) after 300 entities and 120 churn ticks;
- ascending, duplicate-free query order for single, dense, sparse and three-component queries;
- equality between a mid-run snapshot → restore → replay and uninterrupted execution;
- last-freed-first slot reuse with fresh generations.

Because query order feeds private state, any ordering difference changes the hash. The same goldens pass on the
sparse-set store on every locally runnable target (see [Verification](#verification)).
[`ComponentStorageTest`](../core/runtime/src/commonTest/kotlin/dev/spawnkit/runtime/ComponentStorageTest.kt)
covers swap-removal, growth, both ordered-iteration strategies, empty queries and handle caching.

## Selected implementation

- **Columns by ordinal.** `RuntimeBuilder` assigns each `ComponentType` its registration ordinal. A world holds
  `Array<ComponentColumn?>`, so component lookup is an array index instead of a hash lookup on the type.
- **Sparse set.** `sparse[slot]` stores dense position + 1. Values and slots are packed in parallel arrays. Removal
  swaps the last entry into the hole, so dense order is never observable.
- **Ordered queries.** The smallest requested column drives the query. When its size × 16 reaches the slot range,
  the query scans slots in order. Otherwise it collects matching slots, sorts them and returns them ascending.
  An unused or empty component returns immediately. Checkpoint capture uses the same rule through
  `forEachInSlotOrder`.
- **Handle cache.** Each live slot caches its immutable `EntityHandle`; destroy clears it with the generation bump.
  Handle equality was already value-based, so repeated queries return equal handles without reallocating them.
- **Restore.** Columns are presized from the checkpoint entry count and slot range.

The store remains single-threaded per world, like the rest of `World`. It is still provisional R1 runtime code;
R2 can extend it (hierarchy, resources, richer queries) behind the same API.

## Benchmark method

[`benchmarks/runtimeStorage`](../benchmarks/runtimeStorage) is an unshipped KMP executable that measures only the
public `World` API, so the identical harness ran against both stores. For each entity count (100, 1,000, 10,000) it
measures the following cases:

| Case | Operation |
|---|---|
| spawn+attach | One entity plus 1–3 components |
| dense two-component query | One `entities(position, velocity)` call |
| sparse query | One `entities(marker)` call; the marker is on about 25% of entities |
| `get` | One component read |
| step steady | One tick of the motion system, no structural change |
| step churn | One tick with size/10 deferred structural edits |
| snapshot | One checkpoint capture |
| restore | One restore into a fresh world |

Before measuring, one discarded pass runs every case at every size. Each case then gets 5 warm-up runs and
9 samples; the table reports the median. Allocation per operation is reported only on JVM, through HotSpot
`ThreadMXBean.getCurrentThreadAllocatedBytes`. Other targets report `n/a` rather than an estimate.

Commands (from the repository root):

```text
./gradlew :benchmarks:runtimeStorage:jvmRun
./gradlew :benchmarks:runtimeStorage:jsNodeProductionRun
./gradlew :benchmarks:runtimeStorage:wasmJsNodeProductionRun
./gradlew :benchmarks:runtimeStorage:runReleaseExecutableMingwX64
```

Environment:

- OS: Windows 11 Pro (x64), single developer machine, not isolated from background load.
- JVM: OpenJDK 21.0.6.
- Node.js: 22.14.0.
- Kotlin: 2.4.21, release/production binaries.
- Baseline: commit `9a49597`, original store.
- Selected store: the final commit on this branch.

Raw outputs: [evidence/runtime-storage](evidence/runtime-storage/).

## Results

Baseline is the original store at `9a49597`. "Selected" is the final store, measured in two separate runs; the
speedup column shows baseline ÷ selected for both runs. Lower ns and B/op are better.

**Summary.**

- **Queries** are 4.4–23× faster on every target. Dense two-component queries no longer scan every slot with
  per-type hash lookups. Sparse queries visit only the smallest column.
- **Ticks** are 1.9–7.4× faster for steady updates and 1.6–5.8× faster with churn. JVM per-tick allocation fell
  from 1.5 MB to 37 KB at 10,000 entities.
- **`get`** is 1.6–3.7× faster on Node and native.
- **Snapshot and restore** are 1.0–4.5× faster outside two inconclusive rows. The gains are smaller than for queries because capture cost is dominated by codecs and copying.
  Two rows are inconclusive:
  - JVM snapshot at 1,000 entities. Three baseline runs measured 89k, 106k and 245k ns; the selected store measured
    148k and 150k ns. Its allocation is lower (409 KB vs 420 KB).
  - Wasm restore at 10,000 entities: 5.8 ms and 2.6 ms across two runs, against a 3.7 ms baseline.

  Both rows are dominated by GC/JIT variance at this sample size. Neither is a demonstrated regression or a
  demonstrated improvement.

Run-to-run noise: JVM `get` at 100 entities (single-digit ns) and some 10,000-entity JVM rows vary by more than 2×
between runs. Read single-run differences under about 1.5× as noise. The baseline JVM reruns
(`baseline-jvm-rerun-*.md`) show the same spread on the original store.

### jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64)

| case | entities | baseline ns | selected ns (run 1 / run 2) | speedup | baseline B/op | selected B/op |
|---|---:|---:|---:|---:|---:|---:|
| spawn+attach | 100 | 110 | 38.0 / 38.0 | 2.9–2.9× | 239 | 213 |
| query dense (position+velocity) | 100 | 2787 | 472 / 474 | 5.9–5.9× | 3360 | 352 |
| query sparse (marker) | 100 | 1796 | 403 / 364 | 4.5–4.9× | 1360 | 192 |
| get | 100 | 8.2 | 18.5 / 2.9 | 0.4–2.8× | 0.0 | 0.0 |
| step steady | 100 | 6434 | 1224 / 1254 | 5.1–5.3× | 4840 | 664 |
| step churn (size/10 edits) | 100 | 9159 | 2086 / 2229 | 4.1–4.4× | 10017 | 1880 |
| snapshot | 100 | 12915 | 12125 / 12400 | 1.0–1.1× | 40847 | 38946 |
| restore | 100 | 17790 | 10195 / 10210 | 1.7–1.7× | 44458 | 24002 |
| spawn+attach | 1000 | 59.6 | 29.6 / 29.3 | 2.0–2.0× | 287 | 198 |
| query dense (position+velocity) | 1000 | 32480 | 3910 / 3900 | 8.3–8.3× | 75946 | 2754 |
| query sparse (marker) | 1000 | 19665 | 1895 / 2060 | 9.5–10.4× | 41490 | 1090 |
| get | 1000 | 9.0 | 2.9 / 2.9 | 3.1–3.1× | 13.9 | 0.0 |
| step steady | 1000 | 70805 | 10165 / 10235 | 6.9–7.0× | 139585 | 3961 |
| step churn (size/10 edits) | 1000 | 98520 | 17740 / 18670 | 5.3–5.6× | 218057 | 18662 |
| snapshot | 1000 | 89350 | 147650 / 149900 | 0.6–0.6× | 419720 | 408600 |
| restore | 1000 | 190600 | 93800 / 55100 | 2.0–3.5× | 476520 | 256632 |
| spawn+attach | 10000 | 95.9 | 32.5 / 32.4 | 3.0–3.0× | 296 | 223 |
| query dense (position+velocity) | 10000 | 472100 | 66100 / 44100 | 7.1–10.7× | 826632 | 26776 |
| query sparse (marker) | 10000 | 319150 | 29450 / 29200 | 10.8–10.9× | 439536 | 10112 |
| get | 10000 | 14.7 | 2.8 / 6.7 | 2.2–5.2× | 15.8 | 0.0 |
| step steady | 10000 | 874350 | 147450 / 118900 | 5.9–7.4× | 1516284 | 36924 |
| step churn (size/10 edits) | 10000 | 1424500 | 247400 / 246100 | 5.8–5.8× | 2356928 | 246272 |
| snapshot | 10000 | 1134600 | 784300 / 806600 | 1.4–1.4× | 4194648 | 4113536 |
| restore | 10000 | 978300 | 602900 / 580400 | 1.6–1.7× | 4363968 | 2585824 |

### Kotlin/JS on Node.js

| case | entities | baseline ns | selected ns (run 1 / run 2) | speedup |
|---|---:|---:|---:|---:|
| spawn+attach | 100 | 3353 | 1769 / 1796 | 1.9–1.9× |
| query dense (position+velocity) | 100 | 17184 | 1040 / 1020 | 16.5–16.8× |
| query sparse (marker) | 100 | 6686 | 997 / 608 | 6.7–11.0× |
| get | 100 | 98.1 | 31 / 54.6 | 1.8–3.2× |
| step steady | 100 | 57926 | 15697 / 15767 | 3.7–3.7× |
| step churn (size/10 edits) | 100 | 56095 | 22324 / 19878 | 2.5–2.8× |
| snapshot | 100 | 54815 | 20515 / 22430 | 2.4–2.7× |
| restore | 100 | 52545 | 25210 / 24690 | 2.1–2.1× |
| spawn+attach | 1000 | 2998 | 2087 / 1658 | 1.4–1.8× |
| query dense (position+velocity) | 1000 | 226445 | 9900 / 9780 | 22.9–23.2× |
| query sparse (marker) | 1000 | 102535 | 4895 / 5680 | 18.1–20.9× |
| get | 1000 | 172 | 46.6 / 87.3 | 2.0–3.7× |
| step steady | 1000 | 588120 | 180610 / 173405 | 3.3–3.4× |
| step churn (size/10 edits) | 1000 | 802610 | 303010 / 250950 | 2.6–3.2× |
| snapshot | 1000 | 595250 | 225800 / 250600 | 2.4–2.6× |
| restore | 1000 | 478700 | 249950 / 309000 | 1.5–1.9× |
| spawn+attach | 10000 | 2269 | 2158 / 1974 | 1.1–1.1× |
| query dense (position+velocity) | 10000 | 1537650 | 202350 / 153200 | 7.6–10.0× |
| query sparse (marker) | 10000 | 798200 | 104550 / 101650 | 7.6–7.9× |
| get | 10000 | 109 | 64.4 / 67.5 | 1.6–1.7× |
| step steady | 10000 | 5339450 | 2323300 / 2752800 | 1.9–2.3× |
| step churn (size/10 edits) | 10000 | 6723500 | 4227200 / 4096500 | 1.6–1.6× |
| snapshot | 10000 | 4674900 | 3916700 / 3313500 | 1.2–1.4× |
| restore | 10000 | 4966000 | 3247500 / 3691800 | 1.3–1.5× |

### Kotlin/Wasm on Node.js

| case | entities | baseline ns | selected ns (run 1 / run 2) | speedup |
|---|---:|---:|---:|---:|
| spawn+attach | 100 | 164 | 145 / 109 | 1.1–1.5× |
| query dense (position+velocity) | 100 | 4948 | 874 / 749 | 5.7–6.6× |
| query sparse (marker) | 100 | 2577 | 369 / 367 | 7.0–7.0× |
| get | 100 | 25.4 | 12.2 / 12.2 | 2.1–2.1× |
| step steady | 100 | 12761 | 3470 / 3492 | 3.7–3.7× |
| step churn (size/10 edits) | 100 | 16028 | 6136 / 6135 | 2.6–2.6× |
| snapshot | 100 | 46230 | 15110 / 15430 | 3.0–3.1× |
| restore | 100 | 23345 | 15270 / 15350 | 1.5–1.5× |
| spawn+attach | 1000 | 141 | 93.7 / 94.5 | 1.5–1.5× |
| query dense (position+velocity) | 1000 | 59230 | 7465 / 7490 | 7.9–7.9× |
| query sparse (marker) | 1000 | 29850 | 3370 / 3420 | 8.7–8.9× |
| get | 1000 | 36.8 | 12.1 / 12.1 | 3.0–3.0× |
| step steady | 1000 | 142465 | 33655 / 33755 | 4.2–4.2× |
| step churn (size/10 edits) | 1000 | 192470 | 65230 / 65290 | 2.9–3.0× |
| snapshot | 1000 | 513800 | 158950 / 156750 | 3.2–3.3× |
| restore | 1000 | 244550 | 157450 / 155550 | 1.6–1.6× |
| spawn+attach | 10000 | 185 | 101 / 101 | 1.8–1.8× |
| query dense (position+velocity) | 10000 | 891500 | 112150 / 111550 | 7.9–8.0× |
| query sparse (marker) | 10000 | 371500 | 41550 / 45050 | 8.2–8.9× |
| get | 10000 | 38.2 | 13.1 / 13.1 | 2.9–2.9× |
| step steady | 10000 | 1757400 | 414400 / 430100 | 4.1–4.2× |
| step churn (size/10 edits) | 10000 | 2451800 | 802900 / 800500 | 3.1–3.1× |
| snapshot | 10000 | 8551900 | 1883600 / 2162800 | 4.0–4.5× |
| restore | 10000 | 3675700 | 5771900 / 2618700 | 0.6–1.4× |

### native (WINDOWS X64, debug=false)

| case | entities | baseline ns | selected ns (run 1 / run 2) | speedup |
|---|---:|---:|---:|---:|
| spawn+attach | 100 | 467 | 289 / 293 | 1.6–1.6× |
| query dense (position+velocity) | 100 | 15928 | 3658 / 3553 | 4.4–4.5× |
| query sparse (marker) | 100 | 7135 | 1409 / 1467 | 4.9–5.1× |
| get | 100 | 106 | 56.9 / 57.4 | 1.8–1.9× |
| step steady | 100 | 37900 | 13848 / 13487 | 2.7–2.8× |
| step churn (size/10 edits) | 100 | 48449 | 20128 / 19833 | 2.4–2.4× |
| snapshot | 100 | 89505 | 49100 / 50340 | 1.8–1.8× |
| restore | 100 | 73785 | 50400 / 53445 | 1.4–1.5× |
| spawn+attach | 1000 | 469 | 279 / 283 | 1.7–1.7× |
| query dense (position+velocity) | 1000 | 165155 | 34465 / 33710 | 4.8–4.9× |
| query sparse (marker) | 1000 | 77420 | 13410 / 14020 | 5.5–5.8× |
| get | 1000 | 108 | 56.7 / 57.5 | 1.9–1.9× |
| step steady | 1000 | 415400 | 132270 / 132610 | 3.1–3.1× |
| step churn (size/10 edits) | 1000 | 573980 | 218310 / 218170 | 2.6–2.6× |
| snapshot | 1000 | 994800 | 494350 / 490800 | 2.0–2.0× |
| restore | 1000 | 740950 | 514900 / 519500 | 1.4–1.4× |
| spawn+attach | 10000 | 552 | 295 / 320 | 1.7–1.9× |
| query dense (position+velocity) | 10000 | 1911950 | 341750 / 369300 | 5.2–5.6× |
| query sparse (marker) | 10000 | 948100 | 135050 / 139500 | 6.8–7.0× |
| get | 10000 | 128 | 57.4 / 59.8 | 2.1–2.2× |
| step steady | 10000 | 4710250 | 1331350 / 1387050 | 3.4–3.5× |
| step churn (size/10 edits) | 10000 | 6365500 | 2244700 / 2328100 | 2.7–2.8× |
| snapshot | 10000 | 13508300 | 6635600 / 6703500 | 2.0–2.0× |
| restore | 10000 | 8622600 | 5933700 / 6473800 | 1.3–1.5× |


## Evidence gaps

- **Android and iOS** were not benchmarked; they need device or emulator runs. The store is platform-free common
  Kotlin and passes Android host tests locally; iOS simulator tests run in CI. Device performance is deferred to the
  R9 performance/allocation gate.
- **linuxX64** benchmark binaries build but were not run on this Windows host. Linux tests run in CI.
- One machine, no CPU isolation. Run-to-run variance at 10,000 entities is large for allocation-heavy cases; see
  the repeated runs above. Allocation is measured on JVM only.
- Fleks performance was not measured because it failed screening. If a future Fleks release publishes JS and iOS
  artifacts, it can be re-screened against the same harness and goldens.
- The scenario is synthetic. Representative game scenes are an R9 measurement.
