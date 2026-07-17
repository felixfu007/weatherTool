package com.example.weathertool

import android.content.Context
import android.content.SharedPreferences

/**
 * Wrapper around SharedPreferences for storing app settings and state.
 */
class PreferenceHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("weather_tool_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_THRESHOLD = "rain_threshold"
        const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        const val KEY_LAST_CHECK_TIME = "last_check_time"
        const val KEY_LAST_LOCATION = "last_location"
        const val KEY_LAST_POP = "last_pop"
        const val KEY_CHECK_INTERVAL_MINUTES = "check_interval_minutes"
        const val KEY_LOCATION_IS_FALLBACK = "location_is_fallback"

        const val DEFAULT_THRESHOLD = 50
        const val DEFAULT_MONITORING_ENABLED = false
        const val DEFAULT_CHECK_INTERVAL_MINUTES = 60
        const val DEFAULT_LOCATION_IS_FALLBACK = false

        /** Selectable background check frequencies, in minutes. WorkManager enforces a 15-minute minimum. */
        val INTERVAL_OPTIONS_MINUTES = intArrayOf(15, 30, 60, 120, 180, 360, 720, 1440)

        /** Used when GPS/location resolution fails, so a check can still run instead of retrying forever. */
        const val DEFAULT_FALLBACK_CITY = "臺北市"
    }

    /** CWA (中央氣象署) open data API authorization key */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, BuildConfig.CWA_API_KEY) ?: BuildConfig.CWA_API_KEY
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

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
}
