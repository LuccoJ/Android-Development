# Android Development

Recipe for getting Android apps compiled without Google's SDK.

`setup-sdk.sh` assembles an Android SDK from openly licensed parts, so Gradle and the Android
Gradle Plugin (AGP) can build APKs without anyone accepting Google's Android SDK License
Agreement. It's meant for throwaway Linux containers (Ubuntu/Debian, root, JDK 17+), such as
cloud coding sessions.

## Use

```bash
./setup-sdk.sh --sdk ~/oss-android-sdk --api 37 --build-tools 36.0.0
```
```bash
export ANDROID_HOME=~/oss-android-sdk
```

Then build the app as usual (`./gradlew assembleDebug`). Building the SDK takes a couple of
minutes, most of it downloading the ~220 MB framework jar.

Which `--api` and `--build-tools` to pass: the app's `compileSdk`, and the build-tools version
of its AGP. If a value is wrong, the build fails with "Failed to install the following Android
SDK packages as some licences have not been accepted", naming the exact packages it wants
(e.g. `platforms;android-37.0`, `build-tools;36.0.0`). Rerun the script with those. Both options
can be repeated. Each API level is provided as both `android-N` and `android-N.0`, since newer
AGP versions ask for the latter when `compileSdk` sets a minor API level.

## Where the parts come from

| SDK part | Source | License |
|---|---|---|
| `android.jar`: framework classes and resources | AOSP framework built by Robolectric, `org.robolectric:android-all` on Maven Central | Apache-2.0 |
| `android.jar`: `java.*`, `javax.*`, `org.w3c.*`, `org.xml.*` | The local OpenJDK | GPLv2 + Classpath Exception |
| `core-for-system-modules.jar` | The `java.*` part of the above | as above |
| `framework.aidl` | Generated: every public `Parcelable` class in the framework | n/a |
| `build-tools/X/`: `aidl`, `aapt`, `aapt2`, `zipalign`, `apksigner` | Debian/Ubuntu packages `aidl`, `android-sdk-build-tools` | Apache-2.0 |

AGP itself and the app's libraries come from Google Maven and Maven Central as in any Gradle
build; downloading those involves no click-through license.

`tools/StubJar.java` reduces the framework and the JDK classes to their public and protected
API, like Google's `android.jar`. It also keeps the non-public supertypes that compilers need,
and replaces every method body with `throw new RuntimeException("Stub!")` (constructors first
call a superclass constructor), which AGP's unit-test support relies on.

`gradle/central-mirror.gradle` is installed as a Gradle init script. It fetches Maven Central
through Google's public mirror, because Central rate-limits busy shared IPs (HTTP 429).

## Limitations

- **`java.*` is a desktop JDK's**, a superset of Android's. A call to a JDK-only method compiles
  but fails at runtime on the phone. Code that also builds against the real SDK is unaffected.
- **API levels 21–37 only**, those Robolectric publishes, and only stable releases. The script
  checks the jar has compiled resources (`resources.arsc`); so far only API 37 has been tried.
- **No `dexdump`**: AGP requires the file to exist, so it's a placeholder that fails if run.
  `core-lambda-stubs.jar` is empty.
- **No platform-tools (`adb`)**: AGP warns that their license isn't accepted, then carries on.
- **No emulator, system images, NDK or CMake**: no native code, and no instrumented tests.
- **`build.prop` and layoutlib metadata are minimal**: fine for building, untested with
  Android Studio's layout preview.

## Tested with

| App | compileSdk | AGP | Result |
|---|---|---|---|
| Gadgetbridge (Codeberg, Oct 2026) | 37 (`android-37.0`) | 9.4.1 | Debug APK builds |
| [WakeBridge](https://github.com/LuccoJ/WakeBridge) | 37 | 9.4.1 | APK builds, lint and Robolectric unit tests pass |

Robolectric unit tests download their own Android framework jars from Maven Central at run time;
see WakeBridge's README for pointing them at a mirror.
