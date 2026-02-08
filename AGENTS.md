# Repository Guidelines

## Project Structure & Module Organization
This is a single-module Android app built with Kotlin and Jetpack Compose.
- App module: `app/`
- Source: `app/src/main/java/com/example/firstapp/`
- Compose theme: `app/src/main/java/com/example/firstapp/ui/theme/`
- Resources: `app/src/main/res/`
- Manifest: `app/src/main/AndroidManifest.xml`
- Unit tests: `app/src/test/java/`
- Instrumented tests: `app/src/androidTest/java/`
- Dependency versions: `gradle/libs.versions.toml`

## Build, Test, and Development Commands
Use the Gradle wrapper from the repo root (PowerShell examples):
- `.\gradlew :app:assembleDebug` builds a debug APK.
- `.\gradlew :app:installDebug` installs the debug build on a device/emulator.
- `.\gradlew :app:testDebugUnitTest` runs local JVM unit tests.
- `.\gradlew :app:connectedDebugAndroidTest` runs instrumented tests on a device.
- `.\gradlew build` builds all modules and runs checks.

## Coding Style & Naming Conventions
- Language: Kotlin (JVM target 11).
- Indentation: 4 spaces, no tabs (Android Studio default).
- Files and classes: `PascalCase` (e.g., `MainActivity.kt`).
- Functions/vars: `camelCase` (e.g., `addition_isCorrect` in tests).
- Packages: lowercase with dots, matching folder structure (e.g., `com.example.firstapp`).
No explicit formatter or linter is configured; use Android Studio’s Kotlin style and organize imports.

## Testing Guidelines
- Frameworks: JUnit4 for unit tests, AndroidX JUnit runner and Espresso for instrumented tests.
- Naming: end test files with `*Test.kt` and test functions with descriptive verbs.
- Place JVM tests under `app/src/test/java/` and device tests under `app/src/androidTest/java/`.

## Commit & Pull Request Guidelines
No Git history is available in this workspace, so there are no established commit conventions. Use concise, imperative messages such as `Add onboarding screen`.
For PRs, include:
- A short summary of the change and rationale.
- Tests run (commands and results).
- Screenshots or screen recordings for UI changes.

## Configuration & Local Environment
`local.properties` is present for the Android SDK path. Keep SDK/local paths machine-specific and avoid sharing secrets (e.g., keystore files) in the repo.
