# WeatherTool – 台灣降雨提醒 Android App

這是一個阿符用來練習AI寫CODE的小工具。

## 功能說明

此 Android App 可以：

1. **取得 GPS 位置** — 透過手機 GPS 偵測目前所在縣市。
2. **呼叫中央氣象署開放資料 API** — 使用 [F-C0032-001](https://opendata.cwa.gov.tw/dist/opendata-swagger.html) 36小時天氣預報 API，每小時自動查詢一次。
3. **降雨機率提醒** — 若當地降雨機率（PoP）超過使用者設定的閾值，即跳出通知提醒帶傘。

## 快速開始

### 1. 申請 API 金鑰

前往 [中央氣象署開放資料平臺](https://opendata.cwa.gov.tw/) 免費申請授權碼（API Key）。

### 2. 設定 App

開啟 App 後，點選「前往設定」：

- **API 金鑰**：貼上剛申請的授權碼。
- **提醒閾值**：設定降雨機率門檻（預設 50%）。超過此值時 App 會推送通知。
- **啟用監控**：開啟後，系統會每小時在背景自動檢查一次天氣。

### 3. 授予權限

App 首次啟動時會依序請求：

| 權限 | 用途 |
|------|------|
| 通知（POST_NOTIFICATIONS） | 推送降雨提醒 |
| 精確位置（ACCESS_FINE_LOCATION） | 取得 GPS 座標 |
| 背景位置（ACCESS_BACKGROUND_LOCATION） | WorkManager 背景執行時存取位置 |

## 專案架構

```
app/src/main/java/com/example/weathertool/
├── MainActivity.kt         # 主畫面：狀態顯示、權限請求
├── SettingsActivity.kt     # 設定畫面：API Key、閾值、監控開關
├── WeatherWorker.kt        # WorkManager 每小時任務
├── LocationHelper.kt       # GPS 取得 + 座標→縣市轉換
├── WeatherApiService.kt    # Retrofit 介面（中央氣象署 API）
├── WeatherResponse.kt      # API 回應資料類別
├── NotificationHelper.kt   # 降雨通知
├── PreferenceHelper.kt     # SharedPreferences 封裝
└── BootReceiver.kt         # 開機後自動重啟監控
```

## 使用技術

- **語言**：Kotlin
- **排程**：WorkManager（每小時）
- **定位**：FusedLocationProviderClient
- **API 呼叫**：Retrofit 2 + OkHttp
- **資料解析**：Gson
- **非同步**：Kotlin Coroutines
- **UI**：Material Components

## 建置方式

需要安裝 Android Studio 或 Android SDK（compileSdk 34, minSdk 26）。

```bash
./gradlew assembleDebug
```

## 授權

MIT License
