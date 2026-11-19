# ADR-0004: Deterministic simulation on every target

- Status: accepted (2026-11-19)

Revision note (2026-11-20): existing guarantees/tests remain binding. The proposed general engine extends the tested
deterministic profile to scheduling, programs and registered package state; arbitrary third-party native code is not
automatically deterministic. See [ADR-0006](0006-general-purpose-engine-and-editor.md); no existing check is weakened.

## Context

Validation (reachability, playtest bot, economy checks), AI repair loops, replays and multiplayer-style features
all require that the same inputs produce the same game state — on the JVM backend, in the browser, and on phones.
Transcendental math functions are not guaranteed to be bit-identical across the JVM, JavaScript engines and
Kotlin/Native.

## Decision

- Fixed time step: 60 ticks per second (`DT = 1.0 / 60`); rendering interpolates between ticks; a frame never runs
  more than `MAX_TICKS_PER_FRAME = 5` ticks.
- Simulation code uses only IEEE-754 exact operations on `Double`: `+ - * /`, `sqrt`, `floor`, `abs`, `min`, `max`
  and comparisons. Integer exponents are computed by repeated multiplication. No `pow`, trigonometry, `exp`, `ln`.
- No wall-clock time, platform APIs or unseeded randomness inside the simulation; a seeded PRNG implemented in
  common code is the only randomness source.
- `RuntimeWorld.stateHash()` is FNV-1a over `Double.toRawBits()` of dynamic state.
- A golden test asserts the same hash for a scripted run on JVM, JS, Wasm, mingwX64, linuxX64 and the iOS simulator.
  A mismatch is a bug in the engine; per-platform golden values are not allowed.

## Consequences

- Validators and AI agents can trust headless simulation results to match what players experience.
- Some math (rotation, curves) needs deterministic approximations written in common code.
