package com.example.weathertool

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker that runs every hour to:
 * 1. Obtain the device's current GPS location.
 * 2. Resolve it to a CWA county/city name.
 * 3. Call the CWA open-data API (F-C0032-001) for the 36-hour forecast.
 * 4. Extract the current period's precipitation probability (PoP).
 * 5. Post a notification if PoP exceeds the user-configured threshold.
 */
class WeatherWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    companion object {
        const val WORK_NAME = "WeatherCheckWork"

        /**
         * Enqueues a unique periodic work request that fires every hour.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling after boot
         * or settings change does not reset the timer unnecessarily.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WeatherWorker>(
                1, TimeUnit.HOURS
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
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Cancels the scheduled periodic work. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefHelper = PreferenceHelper(applicationContext)

        if (!prefHelper.monitoringEnabled) {
            return@withContext Result.success()
        }

        val apiKey = prefHelper.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                workDataOf("error" to "API key not configured")
            )
        }

        try {
            // Step 1: Get GPS location
            val locationHelper = LocationHelper(applicationContext)
            val location = locationHelper.getCurrentLocation()
                ?: return@withContext Result.retry()

            // Step 2: Resolve to CWA county/city name
            val cityName = locationHelper.getCityFromLocation(location)
                ?: return@withContext Result.retry()

            prefHelper.lastLocation = cityName

            // Step 3: Call CWA API
            val response = WeatherApiService.create().getWeatherForecast(
                authorization = apiKey,
                locationName = cityName
            )

            if (response.success != "true") {
                return@withContext Result.retry()
            }

            // Step 4: Extract PoP for the current time period
            val pop = extractPoP(response, cityName)

            if (pop >= 0) {
                prefHelper.lastPop = pop
                prefHelper.lastCheckTime = System.currentTimeMillis()

                // Step 5: Notify if PoP exceeds threshold
                val threshold = prefHelper.rainThreshold
                if (pop > threshold) {
                    NotificationHelper(applicationContext).showRainAlert(cityName, pop, threshold)
                }
            }

            return@withContext Result.success()
        } catch (e: Exception) {
            return@withContext Result.retry()
        }
    }

    /**
     * Extracts the nearest upcoming PoP value from the API response.
     *
     * The CWA F-C0032-001 response contains 3 time slots (12-hour windows) for each location.
     * We take the first slot as "current period".
     *
     * @return Integer percentage (0–100), or -1 if parsing fails.
     */
    private fun extractPoP(response: WeatherResponse, cityName: String): Int {
        val location = response.records?.location
            ?.find { it.locationName == cityName }
            ?: response.records?.location?.firstOrNull()
            ?: return -1

        val popElement = location.weatherElement
            ?.find { it.elementName == "PoP" }
            ?: return -1

        return popElement.time
            ?.firstOrNull()
            ?.parameter
            ?.parameterName
            ?.toIntOrNull()
            ?: -1
    }
}
