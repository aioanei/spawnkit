# Renderer browser smoke check

This test runs the real bundled Platformer Adventure project through `GameView` in an isolated headless Chrome
process. It does not use a saved browser profile or change the creator's shipped entry point. The fixture only
reports read-only `RuntimeWorld` state; keyboard and touch events arrive through Chrome's normal input path.

Requirements: the normal SpawnKit build prerequisites, Node.js 22 or newer, and an installed Google Chrome.
No npm packages or new Kotlin libraries are needed. On Windows the script uses Chrome's standard installation
path; on other systems it uses `google-chrome`. Set `CHROME_BIN` to override the executable path.

From the repository root, start the test-only preview:

```text
./gradlew --console=plain --no-configuration-cache -I tools/render-smoke/preview.init.gradle :app:webApp:wasmJsBrowserDevelopmentRun
```

When webpack reports its local URL, run this in a second terminal (adjust the URL if its port differs):

```text
node tools/render-smoke/check.mjs http://127.0.0.1:8081/
```

The process exits nonzero on a failed assertion. It verifies:

- A real running world and successful loading of the bundled Lilita One font.
- Keyboard control after entering the browser canvas, movement, coin collection, key release, and jumping.
- A genuine tab/window focus loss while a movement key is held; returning without a key-up must not resume movement.
- Keyboard control after tapping the game again.
- Simultaneous movement/jump touch points and cancellation cleanup.
- No uncaught browser exceptions or player deaths during the smoke sequence.

Results are written to ignored `build/render-smoke/results/`: `report.json`, Chrome diagnostics, and PNGs at
1600×1000, 1080×2400 and 3440×1000. Inspect the PNGs to verify readable HUD text, camera framing and letterboxing;
the test does not pretend that downloading a font alone proves its appearance. Browser-emulated touch checks
do not replace eventual checks on physical Android/iOS devices.

Stop the preview process after the check. Keep this targeted browser test separate from the usual test aggregates,
so routine headless engine tests do not depend on browser availability. Chrome's headless behavior is documented
in the [official Chrome testing guide](https://developer.chrome.com/docs/automation-and-testing/headless).
