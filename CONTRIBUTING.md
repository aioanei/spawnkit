# Contributing to SpawnKit

Project rules for humans and coding agents are in [AGENTS.md](AGENTS.md). In short:

1. Every change starts from a GitHub issue with an objective, requirements and acceptance criteria.
2. Branch from `main`: `feat|fix|chore|docs|test/<issue-number>-<slug>`.
3. Commit with [Conventional Commits](https://www.conventionalcommits.org/) (`feat(engine): add patrol enemies`).
4. Before pushing, run the Kotlin Toolchain gates:
   - `./kotlin do ktlintFormat`
   - `./kotlin check ktlint`
   - `./kotlin test -p jvm -p android`, plus `-p mingwX64` on Windows when changing `core/*`.

   While Gradle remains the required check, also run `./gradlew spotlessApply`, then separately
   `./gradlew spotlessCheck allJvmAndWebTests` (and `./gradlew localNativeTests` on Windows for `core/*`).
5. Open a focused pull request using the template and link the issue. The `build` check must pass.
6. Merges into `main` are authorized by the repository owner.

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
