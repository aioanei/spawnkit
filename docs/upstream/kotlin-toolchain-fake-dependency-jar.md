# Kotlin Toolchain 0.14.1: `kotlin test` crashes in CI on a truncated `~/.gradle/android/FakeDependency.jar`

**Where to file:** [YouTrack KTC](https://youtrack.jetbrains.com/newIssue?project=KTC). The Toolchain's own output asks
for a report there. **Found:** 2026-11-23

## Environment

- Kotlin Toolchain (CLI) 0.14.1 on GitHub Actions `ubuntu-latest`.
- The Toolchain cache is restored with `actions/cache`, with `fail-on-cache-miss: false`.
- The project has Android modules (`android/app`, AGP 9.1.1 inside the Toolchain's Android pipeline) and KMP library
  modules.

## Steps to reproduce

This happened once, in the `kotlin-toolchain` job of
[aioanei/spawnkit run 45254458521, attempt 1](https://github.com/aioanei/spawnkit/actions/runs/45254458521). The steps
before it passed:
1. `./kotlin show modules`
2. `./kotlin check ktlint`
3. `./kotlin build -m webPlayer -v release`

It crashed during:

```
./kotlin test -p jvm -p android -p linuxX64
```

The run had already passed many test containers (`[ 0 tests failed ]` several times) when the Android part crashed.
Rerunning only the failed job was the workaround.

## Expected

The Android test build starts, or the Toolchain repairs or recreates a damaged cached file.

## Actual

```
Caused by: org.gradle.api.reflect.ObjectInstantiationException: Could not create an instance of type
  com.android.build.gradle.internal.services.FakeDependencyJarBuildService.
Caused by: java.lang.IllegalStateException: Could not find EOCD in '/home/runner/.gradle/android/FakeDependency.jar'
	at com.android.zipflinger.ZipMap.parse(ZipMap.java:105)
	at com.android.builder.packaging.JarFlinger.<init>(JarFlinger.java:99)
	at com.android.build.gradle.internal.services.FakeDependencyJarBuildService$FakeDependencyJarCreator.obtain(FakeDependencyJarBuildService.kt:60)
	...
Please file a bug report at https://youtrack.jetbrains.com/newIssue?project=KTC, and attach your logs from
/home/runner/work/spawnkit/spawnkit/build/logs/amper_2026-11-23_20-01-22_3117-2212_test
```

## Analysis

`FakeDependency.jar` is a shared file of the Android Gradle Plugin under the Gradle user home. It had no end of
central directory record, meaning it was truncated. Two causes look likely:
- the Toolchain started more than one Android Gradle build at once, and they created the file concurrently;
- a truncated copy was restored from the CI cache.

In both cases the file lives outside the Toolchain's per-build directories, so one bad copy breaks every later build
that uses it.

## Suggested fix

Give the embedded Android builds a Gradle user home the Toolchain owns, and serialize their first use. Alternatively,
detect a corrupt `FakeDependency.jar`, delete it and let AGP recreate it.

## Evidence

- The full log is in the run above. Attempt 1 failed. Rerunning only the failed job on a fresh runner passed, so the
  failure is intermittent and not caused by the code under test.
- Our own test results in that run were all passing.
