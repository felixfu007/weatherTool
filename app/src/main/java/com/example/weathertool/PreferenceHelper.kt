package com.example.weathertool

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wrapper around SharedPreferences for storing app settings and state.
 *
 * Non-sensitive settings (thresholds, intervals, last-check results) live in a regular
 * SharedPreferences file.  The CWA API key is stored in a separate
 * [EncryptedSharedPreferences] file so it is protected by AES-256 at rest.
 * On the first read after an upgrade from an older build the key is migrated
 * automatically from the plain-text store.
 */
class PreferenceHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("weather_tool_prefs", Context.MODE_PRIVATE)

    /**
     * Encrypted store for the API key.  Falls back to a plain [SharedPreferences]
     * instance (read-only migration source) when the keystore is unavailable on
     * emulators or locked devices — in that case the migration simply won't run,
     * but the app continues to function normally.
     */
    private val encryptedPrefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "weather_tool_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.onFailure { e ->
        Log.w("PreferenceHelper", "EncryptedSharedPreferences unavailable; falling back to plain prefs", e)
    }.getOrNull()

    companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_THRESHOLD = "rain_threshold"
        const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        const val KEY_LAST_CHECK_TIME = "last_check_time"
        const val KEY_LAST_LOCATION = "last_location"
        const val KEY_LAST_POP = "last_pop"
        const val KEY_CHECK_INTERVAL_MINUTES = "check_interval_minutes"
        const val KEY_LOCATION_IS_FALLBACK = "location_is_fallback"
        const val KEY_LAST_NOTIFIED_SLOT_START = "last_notified_slot_start"
        const val KEY_MANUAL_CITY = "manual_city"

        const val DEFAULT_THRESHOLD = 50
        const val DEFAULT_MONITORING_ENABLED = false
        const val DEFAULT_CHECK_INTERVAL_MINUTES = 60
        const val DEFAULT_LOCATION_IS_FALLBACK = false
        const val DEFAULT_LAST_NOTIFIED_SLOT_START = ""

        /** Selectable background check frequencies, in minutes. WorkManager enforces a 15-minute minimum. */
        val INTERVAL_OPTIONS_MINUTES = intArrayOf(15, 30, 60, 120, 180, 360, 720, 1440)

        /** Used when GPS/location resolution fails, so a check can still run instead of retrying forever. */
        const val DEFAULT_FALLBACK_CITY = "臺北市"
    }

    /** CWA (中央氣象署) open data API authorization key — stored encrypted at rest. */
    var apiKey: String
        get() {
            // If the encrypted store is available, use it — migrating from the legacy
            // plain-text entry on first access so existing installations keep their key.
            if (encryptedPrefs != null) {
                val encrypted = encryptedPrefs.getString(KEY_API_KEY, null)
                if (encrypted != null) return encrypted

                // One-time migration: copy from plain prefs → encrypted prefs, then delete.
                val plain = prefs.getString(KEY_API_KEY, null)
                if (plain != null) {
                    encryptedPrefs.edit().putString(KEY_API_KEY, plain).apply()
                    prefs.edit().remove(KEY_API_KEY).apply()
                    return plain
                }

                return BuildConfig.CWA_API_KEY
            }
            // Fallback (encrypted store unavailable): plain prefs or build-time default.
            return prefs.getString(KEY_API_KEY, BuildConfig.CWA_API_KEY) ?: BuildConfig.CWA_API_KEY
        }
        set(value) {
            if (encryptedPrefs != null) {
                encryptedPrefs.edit().putString(KEY_API_KEY, value).apply()
                // Ensure the plain-text copy is removed if it still exists.
                prefs.edit().remove(KEY_API_KEY).apply()
            } else {
                prefs.edit().putString(KEY_API_KEY, value).apply()
            }
        }

    /** Rainfall probability threshold (0–100 %). Alert fires when PoP > threshold. */
    var rainThreshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) = prefs.edit().putInt(KEY_THRESHOLD, value).apply()

    /** Whether the hourly monitoring WorkManager job is active */
    var monitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING_ENABLED, DEFAULT_MONITORING_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING_ENABLED, value).apply()

    /** Epoch-millis timestamp of the last successful API check */
    var lastCheckTime: Long
        get() = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK_TIME, value).apply()

    /** CWA location name (縣市) resolved from last GPS fix */
    var lastLocation: String
        get() = prefs.getString(KEY_LAST_LOCATION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_LOCATION, value).apply()

    /** Most recently observed precipitation probability (-1 = unknown) */
    var lastPop: Int
        get() = prefs.getInt(KEY_LAST_POP, -1)
        set(value) = prefs.edit().putInt(KEY_LAST_POP, value).apply()

    /** How often (in minutes) the background check runs. One of [INTERVAL_OPTIONS_MINUTES]. */
    var checkIntervalMinutes: Int
        get() = prefs.getInt(KEY_CHECK_INTERVAL_MINUTES, DEFAULT_CHECK_INTERVAL_MINUTES)
        set(value) = prefs.edit().putInt(KEY_CHECK_INTERVAL_MINUTES, value).apply()

    /** Whether [lastLocation] is [DEFAULT_FALLBACK_CITY] because GPS/location resolution failed. */
    var locationIsFallback: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_IS_FALLBACK, DEFAULT_LOCATION_IS_FALLBACK)
        set(value) = prefs.edit().putBoolean(KEY_LOCATION_IS_FALLBACK, value).apply()

    /**
     * [TimeData.startTime] of the forecast slot that last triggered a rain-alert notification.
     * Lets a run skip re-notifying while the same slot's PoP keeps exceeding the threshold on
     * every periodic check, instead of pushing a duplicate notification each time.
     */
    var lastNotifiedSlotStart: String
        get() = prefs.getString(KEY_LAST_NOTIFIED_SLOT_START, DEFAULT_LAST_NOTIFIED_SLOT_START)
            ?: DEFAULT_LAST_NOTIFIED_SLOT_START
        set(value) = prefs.edit().putString(KEY_LAST_NOTIFIED_SLOT_START, value).apply()

    /**
     * A city name explicitly chosen by the user in Settings.
     * When non-empty, this is used as the query city if GPS/geocoding fails, instead of
     * the hardcoded [DEFAULT_FALLBACK_CITY].  An empty string means "use GPS automatically".
     */
    var manualCity: String
        get() = prefs.getString(KEY_MANUAL_CITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MANUAL_CITY, value).apply()

    /**
     * True when the API key is stored in [EncryptedSharedPreferences].
     * False means the Android keystore was unavailable at startup and the key falls back
     * to plain SharedPreferences.  Callers (e.g. [MainActivity]) can surface a warning
     * to inform the user that encryption is not active.
     */
    val isApiKeyEncrypted: Boolean get() = encryptedPrefs != null
}
