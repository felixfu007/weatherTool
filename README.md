# WeatherTool – 台灣降雨提醒 Android App

這是一個阿符用來練習AI寫CODE的小工具。

## 下載安裝

**[📱 下載最新版 APK（v1.2）](https://raw.githubusercontent.com/felixfu007/weatherTool/dev/release/weathertool-v1.2.apk)**

用手機瀏覽器開啟上面的連結即可直接下載 `.apk` 檔。這是 debug 簽章版本（未上架 Google Play），安裝前請先在手機設定中允許「安裝不明來源的應用程式」：

1. 點擊下載完成的通知或檔案，選擇「安裝」。
2. 若跳出「不允許安裝」的警告，前往設定 → 允許瀏覽器（或檔案管理員）安裝未知來源 App，再回頭重新安裝。
3. 安裝完成後開啟 App，依照下方「快速開始」設定 API 金鑰即可使用。

每次發新版本時，`release/` 資料夾內會新增一個帶版本號的 APK（例如 `weathertool-v1.2.apk`），上面的連結會固定指向目前最新版本。

## 功能說明

此 Android App 可以：

1. **取得 GPS 位置** — 透過手機 GPS 偵測目前所在縣市；若偵測不到 GPS，會自動改用「臺北市」作為預設地區，並在畫面上提示。
2. **呼叫中央氣象署開放資料 API** — 使用 [F-C0032-001](https://opendata.cwa.gov.tw/dist/opendata-swagger.html) 36小時天氣預報 API，依使用者設定的頻率（15 分鐘～24 小時）自動在背景查詢。
3. **降雨機率提醒** — 若當地降雨機率（PoP）超過使用者設定的閾值，即跳出通知提醒帶傘。
4. **即時偵測** — 主畫面提供「及時偵測」按鈕，不必等排程，隨時手動立即查詢一次天氣。
5. **背景可靠執行** — App 會請求電池優化白名單，讓排程檢查在 App 沒有開啟時也能準時觸發，減少被系統 Doze 機制延遲或攔截的機率。

## 快速開始

### 1. 申請 API 金鑰

前往 [中央氣象署開放資料平臺](https://opendata.cwa.gov.tw/) 免費申請授權碼（API Key），步驟如下：

1. 開啟平臺網站，點選右上角「會員註冊」，填寫 email、密碼完成註冊。
2. 至註冊信箱收取驗證信，點擊信中連結完成信箱驗證。
3. 用剛註冊的帳號登入平臺。
4. 登入後點選右上角帳號選單中的「會員資料」／「API 授權碼」，即可看到系統核發的授權碼（格式類似 `CWA-XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX`）。
5. 複製此授權碼，稍後貼到 App 的「設定」畫面即可。

授權碼免費、無使用期限，設定完成後就能開始使用下方功能。

### 2. 設定 App

開啟 App 後，點選「前往設定」：

- **API 金鑰**：貼上剛申請的授權碼。
- **提醒閾值**：設定降雨機率門檻（預設 50%）。超過此值時 App 會推送通知。
- **檢查頻率**：選擇背景自動檢查天氣的間隔（15 分鐘～24 小時，預設 1 小時）。
- **啟用監控**：開啟後，系統會依上方設定的頻率在背景自動檢查天氣。
- **背景執行**：若顯示「可能受電池優化限制」，點選按鈕前往系統設定排除限制，避免通知延遲或收不到。

### 3. 授予權限

App 首次啟動時會依序請求：

| 權限 | 用途 |
|------|------|
| 通知（POST_NOTIFICATIONS） | 推送降雨提醒 |
| 精確／概略位置（ACCESS_FINE/COARSE_LOCATION） | 取得 GPS 座標 |
| 背景位置（ACCESS_BACKGROUND_LOCATION） | WorkManager 背景執行時存取位置 |
| 電池優化白名單（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS） | 確保 App 未開啟時背景檢查仍能準時執行 |

每一步不論使用者同意與否都會繼續往下走，App 會優雅降級而不會卡住流程。

## 專案架構

```
app/src/main/java/com/example/weathertool/
├── MainActivity.kt         # 主畫面：狀態顯示、權限請求、即時偵測
├── SettingsActivity.kt     # 設定畫面：API Key、閾值、檢查頻率、監控開關、電池優化
├── WeatherWorker.kt        # WorkManager 排程任務（含即時偵測、GPS 失敗預設地區）
├── LocationHelper.kt       # GPS 取得 + 座標→縣市轉換
├── WeatherApiService.kt    # Retrofit 介面（中央氣象署 API）
├── WeatherResponse.kt      # API 回應資料類別
├── NotificationHelper.kt   # 降雨通知
├── PreferenceHelper.kt     # SharedPreferences 封裝
└── BootReceiver.kt         # 開機後自動重啟監控
```

## 使用技術

- **語言**：Kotlin
- **排程**：WorkManager（可設定頻率）
- **定位**：FusedLocationProviderClient
- **API 呼叫**：Retrofit 2 + OkHttp
- **資料解析**：Gson
- **非同步**：Kotlin Coroutines
- **UI**：Material Components

## 建置方式

需要安裝 Android Studio 或 Android SDK（compileSdk 34, minSdk 26）。詳細環境建置步驟（含全新機器、模擬器測試流程）請參考 [CLAUDE.md](CLAUDE.md)。

```bash
./gradlew assembleDebug
```

## 授權

MIT License
