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
