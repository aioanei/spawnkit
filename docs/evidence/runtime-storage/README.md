# Runtime storage benchmark evidence

These are raw outputs of `benchmarks/runtimeStorage` for the [storage decision](../../runtime-storage.md) (issue #26).
All runs were on the same Windows 11 x64 machine, with JVM 21.0.6, Node.js 22.14.0 and Kotlin 2.4.21.

| Files | Store | Notes |
|---|---|---|
| `baseline-{jvm,js,wasm,mingw}.md` | Original map store, commit `9a49597` | One warmed run per target |
| `baseline-jvm-rerun-{1,2}.md` | Original map store, commit `9a49597` | Two extra JVM runs to measure baseline variance |
| `final-{jvm,js,wasm,mingw}-{1,2}.md` | Selected sparse-set store | Two runs per target |
| `results.md` | Both | Comparison tables generated from the files above |
