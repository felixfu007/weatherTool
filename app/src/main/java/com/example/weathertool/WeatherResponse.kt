package com.example.weathertool

import com.google.gson.annotations.SerializedName

/**
 * Top-level response from CWA (中央氣象署) open-data API endpoint F-C0032-001
 * (36-hour weather forecast).
 */
data class WeatherResponse(
    @SerializedName("success") val success: String,
    @SerializedName("records") val records: WeatherRecords?
)

data class WeatherRecords(
    @SerializedName("datasetDescription") val datasetDescription: String?,
    @SerializedName("location") val location: List<LocationData>?
)

data class LocationData(
    @SerializedName("locationName") val locationName: String,
    @SerializedName("weatherElement") val weatherElement: List<WeatherElement>?
)

data class WeatherElement(
    @SerializedName("elementName") val elementName: String,
    @SerializedName("time") val time: List<TimeData>?
)

data class TimeData(
    @SerializedName("startTime") val startTime: String,
    @SerializedName("endTime") val endTime: String,
    @SerializedName("parameter") val parameter: Parameter?
)

/** Leaf value node in the CWA forecast time series */
data class Parameter(
    @SerializedName("parameterName") val parameterName: String?,
    @SerializedName("parameterUnit") val parameterUnit: String?
)
