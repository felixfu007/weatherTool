package com.example.weathertool

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Build
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Helper class for obtaining the device's GPS location and converting it to a
 * CWA (中央氣象署) county/city name suitable for use in API queries.
 */
class LocationHelper(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    companion object {
        /**
         * Maps Geocoder admin-area strings (which may use simplified or traditional characters,
         * or both forms of "台"/"臺") to the canonical CWA location names used in F-C0032-001.
         */
        val CITY_NAME_MAP: Map<String, String> = mapOf(
            "臺北市" to "臺北市",
            "台北市" to "臺北市",
            "新北市" to "新北市",
            "桃園市" to "桃園市",
            "臺中市" to "臺中市",
            "台中市" to "臺中市",
            "臺南市" to "臺南市",
            "台南市" to "臺南市",
            "高雄市" to "高雄市",
            "基隆市" to "基隆市",
            "新竹市" to "新竹市",
            "嘉義市" to "嘉義市",
            "新竹縣" to "新竹縣",
            "苗栗縣" to "苗栗縣",
            "彰化縣" to "彰化縣",
            "南投縣" to "南投縣",
            "雲林縣" to "雲林縣",
            "嘉義縣" to "嘉義縣",
            "屏東縣" to "屏東縣",
            "宜蘭縣" to "宜蘭縣",
            "花蓮縣" to "花蓮縣",
            "臺東縣" to "臺東縣",
            "台東縣" to "臺東縣",
            "澎湖縣" to "澎湖縣",
            "金門縣" to "金門縣",
            "連江縣" to "連江縣"
        )

        /**
         * Ordered list of canonical CWA city names corresponding to `city_options_display`
         * in strings.xml (index 0 = "auto / empty" placeholder, indices 1–22 = real cities).
         */
        val CITY_OPTIONS_VALUES: List<String> = listOf(
            "",  // "自動偵測（依 GPS）" placeholder
            "臺北市", "新北市", "桃園市", "臺中市", "臺南市", "高雄市",
            "基隆市", "新竹市", "嘉義市",
            "新竹縣", "苗栗縣", "彰化縣", "南投縣", "雲林縣",
            "嘉義縣", "屏東縣", "宜蘭縣", "花蓮縣", "臺東縣",
            "澎湖縣", "金門縣", "連江縣"
        )

        /** Max time to wait for a fresh GPS/network fix before giving up on this check. */
        private const val LOCATION_REQUEST_TIMEOUT_MILLIS = 15_000L
    }

    /**
     * Actively requests a fresh location fix (bounded to
     * [LOCATION_REQUEST_TIMEOUT_MILLIS]) rather than relying on the device's last cached fix,
     * which can be null or stale on a device that hasn't used location recently (e.g. right
     * after boot, or in a freshly-created emulator). Falls back to the last cached fix if the
     * fresh request fails or times out. Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION
     * permission to have been granted before calling.
     *
     * @return A [Location], or null if both the fresh request and the cached fallback fail.
     */
    @Suppress("MissingPermission")
    suspend fun getCurrentLocation(): Location? {
        val freshLocation = try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(LOCATION_REQUEST_TIMEOUT_MILLIS)
                .build()
            fusedLocationClient.getCurrentLocation(request, CancellationTokenSource().token).await()
        } catch (e: Exception) {
            null
        }

        if (freshLocation != null) return freshLocation

        return try {
            fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts GPS [location] to the canonical CWA county/city name via reverse-geocoding.
     *
     * On Android 33+ the non-deprecated async [Geocoder.getFromLocation] overload is used
     * so the IO thread is not blocked waiting for a network-backed geocode response.
     * Older API levels fall back to the synchronous overload.
     *
     * @return A CWA location name (e.g. "臺北市"), or null if reverse-geocoding fails.
     */
    suspend fun getCityFromLocation(location: Location): String? {
        return try {
            // Geocoder requires an underlying geocoding service that is not present on all
            // devices or emulator images; bail out early instead of enqueueing a request
            // that will never be answered.
            if (!Geocoder.isPresent()) return null

            val geocoder = Geocoder(context, Locale.TRADITIONAL_CHINESE)
            val adminArea: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                        // The Geocoder API provides no cancellation handle, so the underlying
                        // geocoding operation continues to completion even if the coroutine is
                        // cancelled.  The `cont.isActive` guard merely prevents calling
                        // `resume` on an already-cancelled coroutine (which would throw).
                        if (cont.isActive) cont.resume(addresses.firstOrNull()?.adminArea)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()?.adminArea
            }
            adminArea?.let { CITY_NAME_MAP[it] ?: it }
        } catch (e: Exception) {
            null
        }
    }
}
