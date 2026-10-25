# Android setup

## Requirements

- JDK 17
- Android SDK platform 35 and build tools 35.0.0
- Android Studio or the Android command-line tools
- An Android 8.0 (API 26) or newer device or emulator for device tests

Open the repository root in Android Studio and synchronize Gradle. The project uses the checked-in Gradle Wrapper; a separate Gradle installation is not required. Android Studio may create `local.properties` with the local SDK path. That file is not versioned.

For PowerShell command-line builds, configure `JAVA_HOME` and `ANDROID_HOME` for your own machine.

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew.bat connectedDebugAndroidTest
```

The second command requires a dedicated test emulator or device without real accounts:
instrumented authentication journeys clear this app's local session. Compiling instrumentation
tests does not execute them. See [testing.md](testing.md) for the Espresso + Compose HTTP
suite and the separately selected E2E against a disposable real backend.

## Initial technical baseline

The initial versions come from the previous Android repository: Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20, Compose BOM 2025.03.01 and Hilt 2.56.1. The version catalog contains only the dependencies used by this scaffold.

The application ID is `co.solventa.mobile.pf2`, so this app can coexist with the previous prototype. Version information is maintained in `version.properties`. No signed release or store publication is configured.

The single `app` module contains an application class, an activity and a minimal Compose entry point. Feature packages, repository contracts and the design system will be added with the corresponding approved work. The entry point and the temporary Android framework icon are not business designs or replacements for the Figma design. App backup and data transfer are disabled in this initial setup.

## Continuous integration

`.github/workflows/android-ci.yml` runs on pull requests to `develop` and `master`, and on pushes to those branches. It uses GitHub-hosted Linux runners, JDK 17 and the Gradle Wrapper.

- `Android verification` runs unit tests, Android Lint, debug compilation and instrumentation-test compilation.
- `Android UI tests` runs the existing layout/storage tests and the Espresso + Compose
  authentication HTTP journeys on an Android 35 emulator after verification succeeds.
- Both jobs upload their available test reports, including on failure.

Lint errors fail verification. Dependency-update advisories remain visible as warnings because the initial toolchain is deliberately kept consistent with the previous project; updating it requires a compatibility review.

The instrumented HTTP suite now covers registration, login, session restoration/rotation,
logout and error handling through the real application. Its HTTP server is a test double,
not a real backend. The opt-in real-backend E2E and the requirements still missing from
Android are documented in [testing.md](testing.md); a successful default CI run does not
claim E2E or full business-requirement coverage.

The workflow has read-only repository permissions and does not use signing credentials. Actions are pinned to commit hashes, and the Gradle distribution is checked against its published SHA-256 checksum.

`Android verification` and `Android UI tests` are required in branch protection for `develop` and `master`, along with one approval from a team member with write access and conversation resolution.

## Jira scope

Repository setup is associated with SOL-146. CI work is related to SOL-151, but that story currently names Jenkins in its acceptance criterion. GitHub Actions is the chosen Android approach for now; this does not resolve or complete that criterion for backend and frontend. No Jira fields are changed by this setup.
