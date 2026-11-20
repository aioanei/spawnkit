# Renderer verification captures

These are captures of the real bundled Platformer Adventure project running through `GameView` in Wasm,
with the [test-only preview](../../tools/render-smoke/README.md). Touch controls are explicitly enabled by that
fixture. The creator app still has its initial shell; these images do not claim otherwise.

| Capture | Viewport | Verified appearance |
|---|---|---|
| [Initial view](wasm-gameview-wide.png) | 1600×1000 | Readable bundled-font Coins/Deaths HUD, real scene, touch buttons |
| [After collection](wasm-gameview-collected.png) | 1600×1000 | Player moved through browser keyboard input and HUD updated to Coins 1 |
| [Tall viewport](wasm-gameview-tall.png) | 1080×2400 | Centered full virtual viewport, vertical letterboxing, readable HUD and touch controls |
| [Ultra-wide viewport](wasm-gameview-ultrawide.png) | 3440×1000 | Centered full virtual viewport and horizontal letterboxing |

The [browser smoke report](renderer-smoke-report.json) records Chrome's version and six passing check groups:
font/world startup, keyboard movement/collection/release, jumping, real tab blur and refocus, two simultaneous
touch points and cancellation, and viewport captures. The run ended with 3 coins, 0 deaths and no uncaught
browser exceptions. The PNGs were also inspected for readable text and correct framing.

The focus-loss check failed before lifecycle-based input cleanup: returning from another tab without a key-up
left the player moving. It passes with that fix. Touch is browser-emulated; physical Android/iOS device checks
remain part of the exported-player task, not a claim made by these images.

## Kotlin Toolchain builds (issue #58)

Artifacts built by the Kotlin Toolchain 0.14.1 rather than Gradle, captured on 2026-11-20:

| Capture | Artifact | Verified behaviour |
|---|---|---|
| [Android rules played](android-toolchain-rules-play.png) | `kotlin build -m androidApp` debug APK on the API 36 emulator | `tools/android-rules-smoke.py` built the Score + collect rules with real taps; play shows "Score: 1" |
| [Desktop builder](desktop-toolchain-builder.png) | `kotlin package -m desktopApp` executable JAR | The builder window opens with no errors on stderr |
| [Web builder](web-toolchain-builder.png) | `kotlin build -m webApp` Wasm bundle, served locally | Headless Chrome renders the expanded builder; no uncaught exceptions |
