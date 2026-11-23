# Upstream reports (JetBrains)

Problems we found in JetBrains products while building SpawnKit, one file per problem, each ready to file as it is.
The owner files them, to show the teams that we use and care about their products. Agents add new ones as they find
them (see [AGENTS.md](../../AGENTS.md#upstream-reports)).

| Report | Product | Where to file | Status |
|---|---|---|---|
| [Anthropic client refuses `AnyOf` tool parameters](koog-anthropic-anyof.md) | Koog 1.3.0 | [GitHub JetBrains/koog issues](https://github.com/JetBrains/koog/issues) | Not filed yet; worked around in `KoogModel` |
| [Wasm test setup changes a Node project above the repository](kotlin-toolchain-wasm-pnpm-ancestor.md) | Kotlin Toolchain 0.14.1 | [YouTrack KTC](https://youtrack.jetbrains.com/newIssue?project=KTC) | Not filed yet; avoided by not running Wasm tests on such machines |
| [`kotlin test` crashes on a truncated `FakeDependency.jar` in CI](kotlin-toolchain-fake-dependency-jar.md) | Kotlin Toolchain 0.14.1 (Android Gradle Plugin inside) | [YouTrack KTC](https://youtrack.jetbrains.com/newIssue?project=KTC) | Not filed yet; rerunning the job passed (intermittent) |

## How each report is written

- **Title.** One line naming the product, the version and the wrong behaviour.
- **Environment.** Versions, operating system, and whether it happened locally or in CI.
- **Steps to reproduce.** The smallest steps, with code or commands that stand alone outside SpawnKit where possible.
- **Expected and actual.** The actual result quotes the error.
- **Workaround.** What SpawnKit does instead, with a link to the code.
- **Evidence.** Logs, test names and dates. Secrets, keys, personal data and the owner's details never go in a report.

When a report is filed, add its link and date to the Status column. When upstream fixes it, note the fixed version and
remove the workaround in a separate change.
