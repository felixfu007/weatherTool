---
name: release-apk
description: Bump WeatherTool's app version, build a debug-signed APK, add it to release/, and update the README download link. Use when asked to "package a release", "cut a new APK", "打包一版 APK", or "update the download link".
---

Project-specific release skill for WeatherTool. Produces the same kind of debug-signed, sideload-only APK already sitting in `release/` (e.g. `weathertool-v1.1.apk`) — this project has no release signing configured (see CLAUDE.md if that ever changes; a previous planning pass on this deliberately deferred it since generating a real signing key is a consequential, hard-to-reverse decision the user has to make).

Run the [verify skill](../verify/SKILL.md)'s environment setup first if `./gradlew` isn't already working (`JAVA_HOME`/`ANDROID_HOME`).

## 1. Decide the version

Read the current version from `app/build.gradle.kts`:

```bash
grep -E "versionCode|versionName" app/build.gradle.kts
```

Unless the user specified an exact version, bump `versionName`'s patch number by one (e.g. `1.1` → `1.2`) and `versionCode` by exactly `+1`. Don't reuse or skip a versionCode — Android treats it as a strictly increasing install-ordering key.

## 2. Test gate before packaging

Never package a version that doesn't pass its own test suite:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
./gradlew test lint --console=plain
```

Must be `BUILD SUCCESSFUL`. Stop and fix (or ask the user) if not — don't package a red build.

## 3. Bump the version and build

Edit `app/build.gradle.kts`'s `defaultConfig` block (`versionCode`, `versionName`), then:

```bash
./gradlew assembleDebug --console=plain
```

## 4. Copy into release/

```bash
cp app/build/outputs/apk/debug/app-debug.apk "release/weathertool-v<NEW_VERSION>.apk"
```

Keep older versioned APKs in `release/` — don't delete them, they're the version history.

## 5. Verify the actual file you're about to publish

Don't just trust the build output — install the exact file that landed in `release/`, since that's the one users will download:

```bash
export ANDROID_HOME="/c/Users/$USER/AppData/Local/Android/Sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1

"$ADB" install -r "release/weathertool-v<NEW_VERSION>.apk"
"$ADB" shell dumpsys package com.example.weathertool | grep -E "versionName|versionCode"   # must match what you set in step 1
"$ADB" shell am start -n com.example.weathertool/.MainActivity
sleep 3
"$ADB" logcat -d | grep -i "FATAL EXCEPTION"   # must be empty
```

(Boot the emulator first per the verify skill if nothing's running.)

## 6. Update the README download link

In `README.md`, update both:

- The bold download line: `**[📱 下載最新版 APK（v<NEW_VERSION>）](https://raw.githubusercontent.com/felixfu007/weatherTool/<BRANCH>/release/weathertool-v<NEW_VERSION>.apk)**`
- The trailing sentence's example filename (`weathertool-v<NEW_VERSION>.apk`)

**`<BRANCH>` must be whichever branch the new APK file actually exists on** (check with `git branch --show-current`) — a `raw.githubusercontent.com` link to a branch that doesn't have the file 404s. This repo's convention (see git log: "publish v1.1 release APK" followed later by "point APK download link at main now that it's merged") is to point at the working branch first, then repoint the link at `main` in a follow-up commit once that branch is actually merged. If you're releasing from a non-main branch, tell the user the link is pinned to that branch and will need a follow-up update after merging.

## 7. Hand back to the normal commit flow

Don't auto-commit. Report what changed (`app/build.gradle.kts` version bump, the new `release/*.apk`, `README.md`) and follow the repo's normal process: stage exactly those files, write a commit message, confirm with the user before pushing — same as any other change.
