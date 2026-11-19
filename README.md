# SpawnKit

**A Kotlin Multiplatform app that builds Kotlin Multiplatform games — where AI agents don't just generate content, they playtest it.**

SpawnKit is a no-code 2D game creation studio. One deterministic Kotlin game runtime powers the in-editor preview,
the browser arcade and standalone exported games for Android, iOS, desktop and the web. AI agents built with
[JetBrains Koog](https://github.com/JetBrains/koog) design levels, characters and complete games through the same
editing commands a human uses, and prove their output with the real engine before you accept it.

> Status: under active development for the Kotlin Multiplatform Contest (KotlinConf 2027 edition).
> This README will grow a "try it in 2 minutes" section as features land.

The current M1 work includes the versioned Game Document, the three-level Platformer Adventure template,
the deterministic runtime, and a shared Compose `GameView` with keyboard/touch input and a HUD.
The exported-game shells and browser deployment are the next tasks; the creator app still opens its initial shell.

## Build

Requirements: JDK 21, Android SDK (for Android targets). iOS builds require macOS with Xcode.

| Target | Command |
|---|---|
| Creator app (desktop) | `./gradlew :app:desktopApp:run` |
| Creator app (web, Wasm) | `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun` |
| Creator app (Android) | `./gradlew :app:androidApp:installDebug` |
| Server | `./gradlew :server:run` |

## License

[Apache License 2.0](LICENSE)
