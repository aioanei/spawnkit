# Kotlin Toolchain 0.14.1: Wasm browser test setup installs Playwright into a Node project above the repository

**Where to file:** [YouTrack KTC](https://youtrack.jetbrains.com/newIssue?project=KTC) · **Found:** 2026-11-20 (see
[known issue 1](../kotlin-toolchain.md#known-issues))

## Environment

- Kotlin Toolchain (CLI) 0.14.1, Windows 11, pnpm on the `PATH`.
- A KMP project with a `wasmJs` target and tests. Some ancestor directory of the project (for example the user's home
  directory) contains its own `package.json`.

## Steps to reproduce

1. Create a directory holding a `package.json`, for example `~/some-node-project/package.json`, and inside it (at any
   depth) a Toolchain project with a `wasmJs` target and one common test.
2. Run `./kotlin test -p wasmJs` in the Toolchain project.

## Expected

The browser test setup installs Playwright into a location the Toolchain owns, such as its build directory or a
project-local `package.json` that it creates, and nothing outside the project changes.

## Actual

The Toolchain runs `pnpm add playwright@<version>` without a project-local `package.json`. pnpm walks up to the nearest
ancestor `package.json` and changes that unrelated project:
- it adds a `playwright` dependency;
- it creates `pnpm-lock.yaml` and `pnpm-workspace.yaml`;
- it replaces `node_modules` with a pnpm-managed one.

## Impact

Running tests silently modifies a different project on the developer's machine. CI runners are unaffected because
they have no ancestor `package.json`.

## Workaround in SpawnKit

`AGENTS.md` forbids `kotlin test -p wasmJs` where any ancestor holds a `package.json`; CI covers Wasm. The affected
project was restored from its npm lockfile. Building `wasm-js/app` (`npmInstall`) is not affected; it uses its own
directory.

## Suggested fix

Create a `package.json` in the Toolchain's own working directory before `pnpm add` (or pass `--dir`/`--ignore-workspace`
so pnpm never resolves upward).
