package com.example.weathertool

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Retrofit interface for the CWA (中央氣象署) Open Data API.
 *
 * API documentation: https://opendata.cwa.gov.tw/dist/opendata-swagger.html
 * Register for a free API key at: https://opendata.cwa.gov.tw/
 *
 * Endpoint F-C0032-001 returns 36-hour weather forecasts by county/city,
 * including PoP (Probability of Precipitation) for each 12-hour window.
 */
interface WeatherApiService {

    /**
     * Fetch 36-hour weather forecast.
     *
     * @param authorization CWA API authorization key (required)
     * @param locationName  Optional county/city name (e.g. "臺北市"). When omitted the API
     *                      returns data for all counties/cities in Taiwan.
     * @param elementName   Comma-separated weather elements to include. "PoP" = precipitation
     *                      probability (降雨機率).
     */
    @GET("F-C0032-001")
    suspend fun getWeatherForecast(
        @Query("Authorization") authorization: String,
        @Query("locationName") locationName: String? = null,
        @Query("elementName") elementName: String = "PoP"
    ): WeatherResponse

    companion object {
        private const val BASE_URL = "https://opendata.cwa.gov.tw/api/v1/rest/datastore/"

        fun create(): WeatherApiService {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(WeatherApiService::class.java)
        }
    }
}
