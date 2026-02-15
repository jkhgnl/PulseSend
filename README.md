# PulseSend

PulseSend is a cross-platform local transfer project with:
- Android app (Kotlin + Jetpack Compose)
- Windows desktop app (Avalonia + .NET)

## Project Structure
- `app/`: Android client
- `windows/`: Windows desktop and shared core

## Build
### Android
- `./gradlew :app:assembleDebug`
- `./gradlew :app:installDebug`

### Windows
Open the `windows/` solution/project in your .NET IDE and build in Debug/Release mode.

## Tests
- Android unit tests: `./gradlew :app:testDebugUnitTest`
- Android instrumented tests: `./gradlew :app:connectedDebugAndroidTest`

## License
This project is licensed under **CC BY-NC 4.0**.
Commercial use is not allowed.
See `LICENSE` for details.
