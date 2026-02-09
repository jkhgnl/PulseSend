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
- `./gradlew :app:assembleDebug` builds a debug APK.
- `./gradlew :app:installDebug` installs the debug build on a device/emulator.
- `./gradlew :app:testDebugUnitTest` runs local JVM unit tests.
- `./gradlew :app:connectedDebugAndroidTest` runs instrumented tests on a device.
- `./gradlew build` builds all modules and runs checks.

## Coding Style & Naming Conventions
- Language: Kotlin (JVM target 11).
- Indentation: 4 spaces, no tabs (Android Studio default).
- Files and classes: `PascalCase` (e.g., `MainActivity.kt`).
- Functions/vars: `camelCase` (e.g., `addition_isCorrect` in tests).
- Packages: lowercase with dots, matching folder structure (e.g., `com.example.firstapp`).
- Formatting: no explicit formatter; use Android Studio¡¯s Kotlin style and organize imports.

## Testing Guidelines
- Frameworks: JUnit4 for unit tests; AndroidX JUnit runner and Espresso for instrumented tests.
- Naming: end test files with `*Test.kt` and use descriptive verb-style test names.
- Run JVM tests with `./gradlew :app:testDebugUnitTest` and device tests with `./gradlew :app:connectedDebugAndroidTest`.

## Commit & Pull Request Guidelines
- No Git history is available in this workspace, so no established commit conventions.
- Use concise, imperative messages (e.g., `Add onboarding screen`).
- PRs should include a short summary, rationale, tests run (commands + results), and screenshots/recordings for UI changes.

## Configuration & Local Environment
- `local.properties` holds the Android SDK path and is machine-specific.
- Avoid committing secrets (e.g., keystores) or user-specific paths.
