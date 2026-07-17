# CLAUDE.md

本檔案提供 Claude Code（claude.ai/code）在此儲存庫中操作程式碼時所需的指引。

## 專案概覽

WeatherTool 是一個單模組的 Android App（Kotlin），會在背景每小時輪詢一次台灣中央氣象署（CWA）開放資料 API，當當地降雨機率（PoP）超過使用者設定的閾值時推播通知。使用者面向的功能說明與權限列表請參考 [README.md](README.md)。

## 常用指令

需要安裝 JDK + Android SDK（或 Android Studio），並具備 `compileSdk 34` / `minSdk 26`。

```bash
# 建置 debug APK
./gradlew assembleDebug

# 執行所有單元測試（JVM，不需模擬器）
./gradlew test

# 執行單一測試類別
./gradlew testDebugUnitTest --tests "com.example.weathertool.WeatherDataTest"

# 執行單一測試方法
./gradlew testDebugUnitTest --tests "com.example.weathertool.WeatherDataTest.alert should fire when pop exceeds threshold"

# Lint 檢查
./gradlew lint
```

目前尚未建立 `androidTest`（儀器化測試）source set —— 所有測試都放在 `app/src/test` 下並以 JVM 執行。

### 本機 API 金鑰設定

App 會呼叫 CWA API，需要在 https://opendata.cwa.gov.tw/ 免費申請授權碼。本機建置時，請在專案根目錄建立 `local.properties`（已列入 .gitignore）並加入：

```properties
CWA_API_KEY=your-key-here
```

`app/build.gradle.kts` 會在建置時讀取此檔案，並將其暴露為 `BuildConfig.CWA_API_KEY`，供 `PreferenceHelper.apiKey` 在使用者尚未於設定畫面輸入金鑰時作為預設值。即使不設定，App 仍可正常建置與執行，只是使用者必須先到「設定」畫面貼上金鑰，監控功能才會生效。

## 架構說明

所有原始碼都放在同一個 package：`app/src/main/java/com/example/weathertool/`。專案沒有分層為多個模組——每個類別各司其職、職責單一，彼此透過 `PreferenceHelper`（SharedPreferences）作為共享狀態溝通，而非透過 DI 容器。

**每小時檢查的資料流**（`WeatherWorker.doWork()`），是整個 App 的核心邏輯：

1. `WeatherWorker`（透過 WorkManager 每小時排程的 `CoroutineWorker`）從 `PreferenceHelper` 讀取設定；若監控未啟用或未設定 API 金鑰，會提早結束。
2. `LocationHelper.getCurrentLocation()` 透過 `FusedLocationProviderClient` 取得最近一次已知的 GPS 定位，接著 `getCityFromLocation()` 使用 `Geocoder` 搭配 `CITY_NAME_MAP` 對照表反向地理編碼為 CWA 標準縣市名稱（涵蓋台灣全部 22 個行政區，並正規化「台」／「臺」異體字）。
3. `WeatherApiService.create().getWeatherForecast(...)`（Retrofit + OkHttp + Gson）呼叫 CWA 的 `F-C0032-001` 端點，並反序列化為 `WeatherResponse` → `WeatherRecords` → `LocationData` → `WeatherElement` → `TimeData` → `Parameter` 這條資料類別鏈（定義於 `WeatherResponse.kt`）。
4. `WeatherWorker.extractPoP()` 從上述回應樹中取出對應城市第一個時段的 PoP（`parameterName`）數值。
5. 若 PoP 超過 `PreferenceHelper.rainThreshold`，`NotificationHelper.showRainAlert()` 會推送通知（通知頻道在其 `init` 中延遲建立）。
6. 定位或 API 呼叫任一步驟失敗時一律回傳 `Result.retry()`，交由 WorkManager 內建的指數退避機制處理（設定於 `WeatherWorker.schedule()`），不額外實作自訂重試邏輯。

**排程與生命週期：**
- `WeatherWorker.schedule()` / `.cancel()` 是唯一會操作 WorkManager 的進入點；分別由 `MainActivity`（切換按鈕）、`SettingsActivity`（監控開關）、`BootReceiver`（在 `BOOT_COMPLETED` 時，僅當先前已啟用監控才觸發）呼叫——確保這三處 UI／生命週期觸發點行為一致。
- 刻意使用 `ExistingPeriodicWorkPolicy.KEEP`，讓重新排程（例如開機後）不會重置每小時的計時器。

**UI：**
- `MainActivity` 只負責顯示狀態（來自 `PreferenceHelper`）並驅動權限請求流程：POST_NOTIFICATIONS（Android 13+）→ ACCESS_FINE/COARSE_LOCATION → ACCESS_BACKGROUND_LOCATION（Android 10+），不論使用者是否同意，每一步都會繼續往下走，讓 App 優雅降級而非卡住流程。
- `SettingsActivity` 直接對 `PreferenceHelper` 讀寫 API 金鑰／閾值／監控開關；切換監控開關會立即呼叫 `WeatherWorker.schedule/cancel`，不需等待儲存動作。
- 兩個 Activity 皆使用 view binding（`ActivityMainBinding` / `ActivitySettingsBinding`）；對應設定為 `app/build.gradle.kts` 中的 `buildFeatures.viewBinding = true`。

**狀態管理：** `PreferenceHelper` 是唯一的狀態來源，同時保存使用者設定（API 金鑰、閾值、監控開關）與最近一次觀測結果（最後定位、最後 PoP、最後檢查時間）——專案中沒有資料庫或 repository 層。
