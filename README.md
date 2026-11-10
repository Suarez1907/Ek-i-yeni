# Ekşi+ (Ek-i-yeni)

Android client for Ekşi Sözlük. Kotlin, Jetpack Compose, Hilt, Room, Paging 3, OkHttp.

The application id stays `com.aistudio.eksinoled.trkxqp` and the namespace stays `com.example`. Changing either would break updates for existing installs.

## Debug builds

Debug does not need a release keystore or API secrets. The Android Gradle Plugin signs debug APKs with its own debug key.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The Gemini API key is entered in the app and stored with the Android Keystore. It is not compiled into the APK.

## Release signing

Release builds enable R8 (minify + resource shrinking). They are signed only when a real upload keystore is provided. The workflow does not generate a new keystore.

Put these in the environment, or in a gitignored `keystore.properties` file at the repo root:

| Name | Meaning |
| --- | --- |
| `RELEASE_STORE_FILE` | Absolute path to the `.jks` / `.keystore` file |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `RELEASE_KEY_PASSWORD` | Key password |

Example `keystore.properties` (do not commit it):

```properties
RELEASE_STORE_FILE=/absolute/path/to/upload.jks
RELEASE_STORE_PASSWORD=your-store-password
RELEASE_KEY_ALIAS=upload
RELEASE_KEY_PASSWORD=your-key-password
```

### GitHub Actions secrets

The debug job (`testDebugUnitTest` + `assembleDebug`) does not use secrets.

The manual **release** job (`workflow_dispatch`) expects these repository secrets:

| Secret | Meaning |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64 of the upload keystore file (`base64 -w0 upload.jks`) |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Use the same keystore for every release. A new keystore produces an APK that cannot update the previous install.

## What you need to do once

1. Create one upload keystore and store it outside the repo.
2. Add the four GitHub Actions secrets above if you want CI release APKs.
3. Keep the keystore backed up. It cannot be recovered from the APK.

## Baseline profile

A startup profile and a baseline profile are already checked in under `app/src/release/generated/baselineProfiles/`. They were collected on an Android 15 (`aosp_atd` x86_64) emulator by cold-starting the app. Regenerate them on a phone or emulator with:

```bash
./gradlew :app:generateBaselineProfile
```

That writes the profile into the app module. The next release build installs it with ProfileInstaller. The generator needs a writable device (`adb devices` shows one). An emulator image such as `system-images;android-35;aosp_atd;x86_64` is enough.

## Compose stability reports

`compose_compiler.conf` marks the list and entry models as stable. A debug compile writes reports and metrics to:

- `app/build/compose-reports`
- `app/build/compose-metrics`

```bash
./gradlew :app:compileDebugKotlin
```
