package com.example.weathertool

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that:
 * 1. Obtains the device's current GPS location, falling back to
 *    [PreferenceHelper.DEFAULT_FALLBACK_CITY] if no fix is available or reverse-geocoding fails.
 * 2. Resolves it to a CWA county/city name.
 * 3. Calls the CWA open-data API (F-C0032-001) for the 36-hour forecast.
 * 4. Extracts the current period's precipitation probability (PoP).
 * 5. Posts a notification if PoP exceeds the user-configured threshold.
 *
 * Runs on a periodic schedule (interval configured via [PreferenceHelper.checkIntervalMinutes])
 * or as a one-off triggered by the "即時偵測" button ([enqueueImmediateCheck]).
 */
class WeatherWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    companion object {
        const val WORK_NAME = "WeatherCheckWork"
        const val MANUAL_WORK_NAME = "WeatherManualCheckWork"
        const val KEY_MANUAL_CHECK = "manual_check"

        /**
         * Enqueues a unique periodic work request at the user-configured interval
         * ([PreferenceHelper.checkIntervalMinutes]). Uses [ExistingPeriodicWorkPolicy.UPDATE]
         * so that changing the interval in Settings takes effect on the next run instead
         * of being ignored in favor of a stale schedule.
         */
        fun schedule(context: Context) {
            val intervalMinutes = PreferenceHelper(context).checkIntervalMinutes.coerceAtLeast(15)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WeatherWorker>(
                intervalMinutes.toLong(), TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Cancels the scheduled periodic work. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Enqueues a single immediate check ("即時偵測" button), bypassing the
         * monitoring-enabled toggle. Replaces any not-yet-run manual check so
         * repeated taps don't pile up duplicate requests.
         */
        fun enqueueImmediateCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<WeatherWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_MANUAL_CHECK to true))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /** Outcome of resolving the city to query, including whether the fallback was used. */
        internal data class ResolvedLocation(val cityName: String, val isFallback: Boolean)

        /**
         * Decides whether this run should proceed: manual checks ("即時偵測") always run,
         * scheduled runs only proceed while monitoring is enabled.
         */
        internal fun shouldRun(isManualCheck: Boolean, monitoringEnabled: Boolean): Boolean =
            isManualCheck || monitoringEnabled

        /**
         * Resolves the city to query from the geocoded result, falling back to
         * [PreferenceHelper.DEFAULT_FALLBACK_CITY] when GPS/geocoding produced nothing.
         */
        internal fun resolveLocation(geocodedCity: String?): ResolvedLocation =
            if (geocodedCity != null) {
                ResolvedLocation(geocodedCity, isFallback = false)
            } else {
                ResolvedLocation(PreferenceHelper.DEFAULT_FALLBACK_CITY, isFallback = true)
            }

        /** Whether the observed [pop] warrants a notification for the given [threshold]. */
        internal fun shouldNotify(pop: Int, threshold: Int): Boolean = pop > threshold

        /**
         * Whether [slotStartTime] represents a forecast window we haven't already notified
         * about, so a rain condition that keeps triggering on every periodic check within the
         * same window (e.g. every 15 minutes) only pushes one notification instead of one per
         * check. A `null` slot never qualifies as "new" since there's nothing to identify it by.
         */
        internal fun isNewNotifiableSlot(slotStartTime: String?, lastNotifiedSlotStart: String): Boolean =
            slotStartTime != null && slotStartTime != lastNotifiedSlotStart

        /**
         * Whether an HTTP [statusCode] from the CWA API indicates a problem that retrying
         * won't fix (e.g. an invalid/expired API key), as opposed to a transient server or
         * rate-limiting issue that a later retry might succeed at.
         */
        internal fun isNonRetryableHttpError(statusCode: Int): Boolean =
            statusCode == 401 || statusCode == 403

        /** CWA forecast timestamps are plain "yyyy-MM-dd HH:mm:ss" in Taiwan local time, no offset. */
        private const val CWA_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
        private val TAIPEI_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Taipei")

        /**
         * Picks the time slot that actually covers [now] out of the CWA F-C0032-001 response's
         * 12-hour windows, instead of always assuming the array's first entry is "current".
         * Falls back to the first not-yet-ended window if none of them (a slot whose window has
         * already fully elapsed relative to [now]) contain it, or to the last window if every
         * window has already ended (e.g. the response is stale).
         */
        internal fun selectTimeSlot(times: List<TimeData>, now: Date = Date()): TimeData? {
            if (times.isEmpty()) return null

            val format = SimpleDateFormat(CWA_TIME_PATTERN, Locale.TAIWAN).apply {
                timeZone = TAIPEI_TIME_ZONE
            }

            val notYetEnded = times.firstOrNull { timeData ->
                val end = runCatching { format.parse(timeData.endTime) }.getOrNull()
                end != null && now.before(end)
            }

            return notYetEnded ?: times.last()
        }

        /** [pop] extracted for the slot starting at [slotStartTime] (null if extraction failed). */
        internal data class PopResult(val pop: Int, val slotStartTime: String?)

        /**
         * Extracts the PoP value for the time slot covering [now] from the API response, along
         * with that slot's start time (used to dedupe repeat notifications for the same window).
         *
         * The CWA F-C0032-001 response contains 3 time slots (12-hour windows) for each location.
         *
         * @return [PopResult] with pop as an integer percentage (0–100), or -1 if parsing fails.
         */
        internal fun extractPoP(response: WeatherResponse, cityName: String, now: Date = Date()): PopResult {
            val location = response.records?.location
                ?.find { it.locationName == cityName }
                ?: response.records?.location?.firstOrNull()
                ?: return PopResult(-1, null)

            val popElement = location.weatherElement
                ?.find { it.elementName == "PoP" }
                ?: return PopResult(-1, null)

            val slot = selectTimeSlot(popElement.time ?: emptyList(), now)
                ?: return PopResult(-1, null)

            val pop = slot.parameter?.parameterName?.toIntOrNull() ?: -1
            return PopResult(pop, if (pop >= 0) slot.startTime else null)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefHelper = PreferenceHelper(applicationContext)
        val isManualCheck = inputData.getBoolean(KEY_MANUAL_CHECK, false)

        if (!shouldRun(isManualCheck, prefHelper.monitoringEnabled)) {
            return@withContext Result.success()
        }

        val apiKey = prefHelper.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                workDataOf("error" to "API key not configured")
            )
        }

        try {
            // Steps 1–2: Get GPS location and resolve to a CWA county/city name.
            // If either fails (no GPS fix, or reverse-geocoding fails), fall back to
            // PreferenceHelper.DEFAULT_FALLBACK_CITY instead of retrying forever, so the
            // user still gets alerts and a clear "GPS not detected" status.
            val locationHelper = LocationHelper(applicationContext)
            val location = locationHelper.getCurrentLocation()
            val geocodedCity = if (location != null) locationHelper.getCityFromLocation(location) else null

            val resolved = resolveLocation(geocodedCity)
            prefHelper.locationIsFallback = resolved.isFallback
            prefHelper.lastLocation = resolved.cityName

            // Step 3: Call CWA API
            val response = WeatherApiService.create().getWeatherForecast(
                authorization = apiKey,
                locationName = resolved.cityName
            )

            if (response.success != "true") {
                return@withContext Result.retry()
            }

            // Step 4: Extract PoP for the current time period
            val popResult = extractPoP(response, resolved.cityName)
            val pop = popResult.pop

            if (pop >= 0) {
                prefHelper.lastPop = pop
                prefHelper.lastCheckTime = System.currentTimeMillis()

                // Step 5: Notify if PoP exceeds threshold, unless we already notified for
                // this exact forecast window on an earlier check.
                val threshold = prefHelper.rainThreshold
                if (shouldNotify(pop, threshold) &&
                    isNewNotifiableSlot(popResult.slotStartTime, prefHelper.lastNotifiedSlotStart)
                ) {
                    NotificationHelper(applicationContext).showRainAlert(resolved.cityName, threshold)
                    prefHelper.lastNotifiedSlotStart = popResult.slotStartTime ?: ""
                }
            }

            return@withContext Result.success()
        } catch (e: HttpException) {
            // Retrying a request the server has already rejected as unauthorized (e.g. an
            // invalid/expired CWA API key) just burns battery and rate-limit budget for no
            // benefit; surface it as a failure instead so WorkManager stops trying.
            return@withContext if (isNonRetryableHttpError(e.code())) {
                Result.failure(
                    workDataOf("error" to "CWA API rejected the request (HTTP ${e.code()}); check the configured API key")
                )
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            return@withContext Result.retry()
        }
    }
}
