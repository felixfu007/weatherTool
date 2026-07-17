---
name: verify
description: Run WeatherTool's unit tests, build the debug APK, install it on the local Android emulator, and drive the UI to confirm a change actually works — not just that it compiles.
---

Project-specific verify skill for WeatherTool (single-module Kotlin Android app). Use this after any non-trivial code change, before reporting the work as done or committing.

## 0. Environment (one-time per machine)

If `./gradlew test` fails with `sdk.dir` / `ANDROID_HOME` errors, or `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`, the machine isn't set up yet — follow CLAUDE.md's "全新機器環境建置" section first, then come back here. Once set up, every command below assumes these are exported in the shell:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"   # any JDK 17+ works
export ANDROID_HOME="/c/Users/$USER/AppData/Local/Android/Sdk"
```

## 1. Unit tests

```bash
cd "c:\Users\felixfu007\Documents\weatherTool"
./gradlew test --console=plain
```

Must show `BUILD SUCCESSFUL`. If you added logic, add a JVM unit test for it in `app/src/test/java/com/example/weathertool/WeatherDataTest.kt` first — this project has no `androidTest`/Robolectric, so anything you want covered must be plain-Kotlin-testable (no Context). See `NotificationHelper.buildAlertMessage()` for the pattern: pull the string-building logic into a Context-free companion function instead of `context.getString(...)`, so it's testable.

## 2. Build + install on the emulator

```bash
ADB="$ANDROID_HOME/platform-tools/adb.exe"

# Reuse a running emulator if there is one; otherwise boot it.
if ! "$ADB" devices | grep -q "device$"; then
  "$ANDROID_HOME/emulator/emulator.exe" -avd weathertool_avd -no-snapshot -netdelay none -netspeed full -gpu auto &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done
fi

./gradlew installDebug --console=plain
```

## 3. Launch and drive the app

```bash
export MSYS_NO_PATHCONV=1   # required in Git Bash or /sdcard/... paths get mangled into Windows paths
"$ADB" shell am force-stop com.example.weathertool
"$ADB" shell am start -n com.example.weathertool/.MainActivity
```

**Do not guess tap coordinates from a screenshot's displayed pixel size.** That was the single biggest source of wasted turns in earlier sessions (mis-tapped buttons, wrong screen, repeated retries). Instead, dump the real view hierarchy and read exact bounds:

```bash
"$ADB" shell uiautomator dump /sdcard/window_dump.xml
"$ADB" pull /sdcard/window_dump.xml ./scratch_dump.xml
```

Read `scratch_dump.xml` (it's plain XML — grep for the `text=` or `resource-id=` you're targeting) and get its `bounds="[x1,y1][x2,y2]"`. Tap the center of that rect directly in real device pixels:

```bash
"$ADB" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
```

Delete `scratch_dump.xml` when done. Only fall back to screenshot + eyeballed coordinates for things uiautomator can't see (rare).

To confirm visually after an action, screenshot and read it with the Read tool:

```bash
"$ADB" shell screencap -p /sdcard/screen.png
"$ADB" pull /sdcard/screen.png ./scratch_screen.png
```
(delete `scratch_screen*.png` afterward — don't leave them in the repo.)

## 4. If the app doesn't behave as expected

```bash
"$ADB" logcat -d | grep -B5 -A 40 "FATAL EXCEPTION"
```

For WorkManager-related checks (periodic/manual weather check), confirm it actually ran:

```bash
"$ADB" logcat -d | grep "WM-WorkerWrapper.*WeatherWorker"
```

Note: this emulator has no real GPS fix, so `LocationHelper.getCurrentLocation()` returns null and `WeatherWorker` falls back to 臺北市 (see `PreferenceHelper.locationIsFallback`) — that's expected here, not a bug, unless you're specifically testing real-location behavior.

## 5. After verifying

Report what you actually observed (screenshot/logcat evidence), not just "tests pass." Follow the repo's normal commit process — stage only the files you touched, write a commit message explaining *why*, and confirm with the user before pushing.
