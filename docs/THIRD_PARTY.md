# Third-party components and assets

| Component | Use | License |
|---|---|---|
| [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain) wrapper scripts (JetBrains) | build bootstrap (`kotlin`, `kotlin.bat`, release 0.14.1) | Apache-2.0 |
| Gradle Wrapper | build bootstrap during the Toolchain migration (`gradle/wrapper`) | Apache-2.0 |
| [Kotlin agent skills](https://github.com/Kotlin/kotlin-agent-skills) (JetBrains) | coding-agent instructions (`.claude/skills`, `.agents/skills`, locked by `skills-lock.json`) | Apache-2.0 |
| [ktlint](https://github.com/pinterest/ktlint) rule engine and standard rules | build-time lint in `plugins/ktlint` (not shipped in apps) | MIT |
| [Lilita One font (Juan Montoreano)](https://github.com/google/fonts/tree/main/ofl/lilitaone) | in-game HUD text (`render/composeResources/font/lilita_one.ttf`) | SIL Open Font License 1.1 — [OFL-LilitaOne.txt](licenses/OFL-LilitaOne.txt) |
| Kotlin Multiplatform wizard output (JetBrains, kmp.jetbrains.com) | initial project skeleton and iOS Xcode project | generated code, used under the wizard's terms |

Library dependencies are declared in `gradle/libs.versions.toml`. Fonts, images and sounds added to the repository
are listed here with their source and license when they are added.
