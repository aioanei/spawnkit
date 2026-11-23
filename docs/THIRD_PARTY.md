# Third-party components and assets

| Component | Use | License |
|---|---|---|
| [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain) wrapper scripts (JetBrains) | build bootstrap (`kotlin`, `kotlin.bat`, release 0.14.1) | Apache-2.0 |
| Gradle Wrapper | build bootstrap during the Toolchain migration (`gradle/wrapper`) | Apache-2.0 |
| [Kotlin agent skills](https://github.com/Kotlin/kotlin-agent-skills) (JetBrains) | coding-agent instructions (`.claude/skills`, `.agents/skills`, locked by `skills-lock.json`) | Apache-2.0 |
| [ktlint](https://github.com/pinterest/ktlint) rule engine and standard rules | build-time lint in `plugins/ktlint` (not shipped in apps) | MIT |
| [Lilita One font (Juan Montoreano)](https://github.com/google/fonts/tree/main/ofl/lilitaone) | in-game HUD text (`render/composeResources/font/lilita_one.ttf`) | SIL Open Font License 1.1 — [OFL-LilitaOne.txt](licenses/OFL-LilitaOne.txt) |
| [Koog](https://github.com/JetBrains/koog) 1.3.0 (JetBrains) | live-model adapter in `ai` (`prompt-executor-anthropic-client`, `prompt-executor-model`, `http-client-ktor`), with Ktor client engines (OkHttp, Darwin, JS) | Apache-2.0 |
| [JNA](https://github.com/java-native-access/jna) 5.19.1 (`jna-platform`) | Windows DPAPI encryption of assistant keys in the desktop builder (`app/shared`, JVM only) | Apache-2.0 or LGPL-2.1 (dual license; used under Apache-2.0) |
| [Exposed](https://github.com/JetBrains/Exposed) 1.6.1 (JetBrains) | server database access (`exposed-core`, `exposed-jdbc`) | Apache-2.0 |
| [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) 3.54.1.0 (Xerial), bundling SQLite | the server's SQLite database driver | Apache-2.0 (SQLite itself is public domain) |
| [Bouncy Castle](https://www.bouncycastle.org/) 1.87 (`bcprov-jdk18on`) | Argon2id password hashing on the server | Bouncy Castle License (MIT) |
| [Eclipse Angus Mail](https://eclipse-ee4j.github.io/angus-mail/) 2.0.5, with Jakarta Mail | account emails over SMTP from the server | EPL-2.0, GPL-2.0 with Classpath Exception, or EDL-1.0 (used under EPL-2.0) |
| [Ktor client](https://ktor.io/) core 3.6.1 (JetBrains) | `HostedModel` in `ai` calls SpawnKit's server (already used by Koog) | Apache-2.0 |
| Kotlin Multiplatform wizard output (JetBrains, kmp.jetbrains.com) | initial project skeleton and iOS Xcode project | generated code, used under the wizard's terms |

Library dependencies are declared in `gradle/libs.versions.toml`. Fonts, images and sounds added to the repository
are listed here with their source and license when they are added.
