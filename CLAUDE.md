# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

WeatherTool is a single-module Android app (Kotlin) that polls Taiwan's Central Weather Administration (CWA) open-data API once an hour in the background and pushes a notification when the local rain probability (PoP) crosses a user-set threshold. See [README.md](README.md) for the end-user feature description and permission list.

## Commands

Requires a JDK + Android SDK (or Android Studio) with `compileSdk 34` / `minSdk 26` installed.

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests (JVM, no emulator needed)
./gradlew test

# Run a single test class
./gradlew testDebugUnitTest --tests "com.example.weathertool.WeatherDataTest"

# Run a single test method
./gradlew testDebugUnitTest --tests "com.example.weathertool.WeatherDataTest.alert should fire when pop exceeds threshold"

# Lint
./gradlew lint
```

There is no `androidTest` (instrumented) source set yet — all current tests live under `app/src/test` and run on the JVM.

### Local API key setup

The app calls the CWA API, which requires a free authorization key from https://opendata.cwa.gov.tw/. For local builds, create `local.properties` in the repo root (already gitignored) with:

```properties
CWA_API_KEY=your-key-here
```

`app/build.gradle.kts` reads this at build time and exposes it as `BuildConfig.CWA_API_KEY`, which `PreferenceHelper.apiKey` falls back to when the user hasn't entered a key in the Settings screen. Without this, the app still builds and runs but requires the user to paste a key into Settings before monitoring works.

## Architecture

All source lives in one package: `app/src/main/java/com/example/weathertool/`. There is no layering into separate modules — each class has a single, narrow responsibility and they compose through `PreferenceHelper` (SharedPreferences) as shared state rather than a DI graph.

**Data flow of the hourly check** (`WeatherWorker.doWork()`), the core of the app:

1. `WeatherWorker` (a `CoroutineWorker` scheduled hourly via WorkManager) reads settings from `PreferenceHelper`; bails out early if monitoring is disabled or no API key is set.
2. `LocationHelper.getCurrentLocation()` gets the last known GPS fix via `FusedLocationProviderClient`, then `getCityFromLocation()` reverse-geocodes it to a CWA-canonical county/city name using `Geocoder` + the `CITY_NAME_MAP` table (normalizes "台"/"臺" character variants across all 22 Taiwan divisions).
3. `WeatherApiService.create().getWeatherForecast(...)` (Retrofit + OkHttp + Gson) calls CWA endpoint `F-C0032-001`, deserializing into the `WeatherResponse` → `WeatherRecords` → `LocationData` → `WeatherElement` → `TimeData` → `Parameter` data-class chain (`WeatherResponse.kt`).
4. `WeatherWorker.extractPoP()` pulls the first PoP time-slot's `parameterName` out of that response tree for the resolved city.
5. If PoP exceeds `PreferenceHelper.rainThreshold`, `NotificationHelper.showRainAlert()` posts a notification (channel created lazily in its `init`).
6. Any failure at the location or API step returns `Result.retry()`, leaning on WorkManager's built-in exponential backoff (configured in `WeatherWorker.schedule()`) rather than custom retry logic.

**Scheduling and lifecycle:**
- `WeatherWorker.schedule()` / `.cancel()` are the only entry points that touch WorkManager; called from `MainActivity` (toggle button), `SettingsActivity` (monitoring switch), and `BootReceiver` (on `BOOT_COMPLETED`, only if monitoring was previously enabled) — keeping all three UI/lifecycle triggers consistent.
- `ExistingPeriodicWorkPolicy.KEEP` is used deliberately so re-scheduling (e.g. after reboot) doesn't reset the hourly timer.

**UI:**
- `MainActivity` only displays status (from `PreferenceHelper`) and drives the permission request chain: POST_NOTIFICATIONS (Android 13+) → ACCESS_FINE/COARSE_LOCATION → ACCESS_BACKGROUND_LOCATION (Android 10+), each step falling through to the next regardless of grant/deny so the app degrades gracefully instead of blocking.
- `SettingsActivity` edits API key / threshold / monitoring toggle directly against `PreferenceHelper`; toggling monitoring here calls `WeatherWorker.schedule/cancel` immediately rather than waiting for a save action.
- Both activities use view binding (`ActivityMainBinding` / `ActivitySettingsBinding`); `buildFeatures.viewBinding = true` in `app/build.gradle.kts`.

**State:** `PreferenceHelper` is the single source of truth for both user settings (API key, threshold, monitoring on/off) and last-observed status (last location, last PoP, last check time) — there is no database or repository layer.
