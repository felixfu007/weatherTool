package com.example.weathertool

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for pure logic in the weather tool app.
 * These tests run on the JVM (no Android framework required).
 */
class WeatherDataTest {

    // -----------------------------------------------------------------------
    // LocationHelper – city name mapping
    // -----------------------------------------------------------------------

    @Test
    fun `city map normalises traditional-character variants`() {
        // Both "台" and "臺" spellings should resolve to the canonical CWA name.
        assertEquals("臺北市", LocationHelper.CITY_NAME_MAP["台北市"])
        assertEquals("臺北市", LocationHelper.CITY_NAME_MAP["臺北市"])
        assertEquals("臺中市", LocationHelper.CITY_NAME_MAP["台中市"])
        assertEquals("臺南市", LocationHelper.CITY_NAME_MAP["台南市"])
        assertEquals("臺東縣", LocationHelper.CITY_NAME_MAP["台東縣"])
    }

    @Test
    fun `city map covers all 22 administrative divisions`() {
        val expected = setOf(
            "臺北市", "新北市", "桃園市", "臺中市", "臺南市", "高雄市",
            "基隆市", "新竹市", "嘉義市",
            "新竹縣", "苗栗縣", "彰化縣", "南投縣", "雲林縣",
            "嘉義縣", "屏東縣", "宜蘭縣", "花蓮縣", "臺東縣",
            "澎湖縣", "金門縣", "連江縣"
        )
        val mappedValues = LocationHelper.CITY_NAME_MAP.values.toSet()
        assertTrue(
            "Missing divisions: ${expected - mappedValues}",
            mappedValues.containsAll(expected)
        )
    }

    // -----------------------------------------------------------------------
    // WeatherWorker – exercises the real companion-object functions directly
    // (not copies of the logic), so a regression in the production code
    // actually fails these tests.
    // -----------------------------------------------------------------------

    private fun buildResponse(locationName: String, popValue: String): WeatherResponse =
        WeatherResponse(
            success = "true",
            records = WeatherRecords(
                datasetDescription = "三十六小時天氣預報",
                location = listOf(
                    LocationData(
                        locationName = locationName,
                        weatherElement = listOf(
                            WeatherElement(
                                elementName = "PoP",
                                time = listOf(
                                    TimeData(
                                        startTime = "2024-01-01 06:00:00",
                                        endTime = "2024-01-01 18:00:00",
                                        parameter = Parameter(
                                            parameterName = popValue,
                                            parameterUnit = "百分比"
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

    @Test
    fun `PoP is correctly read from response for matching city`() {
        val response = buildResponse("臺北市", "70")
        assertEquals(70, WeatherWorker.extractPoP(response, "臺北市"))
    }

    @Test
    fun `PoP falls back to the first location when the requested city is missing`() {
        val response = buildResponse("高雄市", "40")
        assertEquals(40, WeatherWorker.extractPoP(response, "臺北市"))
    }

    @Test
    fun `PoP extraction returns -1 when element list is empty`() {
        val response = WeatherResponse(
            success = "true",
            records = WeatherRecords(
                datasetDescription = null,
                location = listOf(
                    LocationData(
                        locationName = "臺北市",
                        weatherElement = emptyList()
                    )
                )
            )
        )
        assertEquals(-1, WeatherWorker.extractPoP(response, "臺北市"))
    }

    @Test
    fun `PoP extraction returns -1 when location list is null`() {
        val response = WeatherResponse(success = "true", records = WeatherRecords(null, null))
        assertEquals(-1, WeatherWorker.extractPoP(response, "臺北市"))
    }

    @Test
    fun `alert should fire when pop exceeds threshold`() {
        assertTrue(WeatherWorker.shouldNotify(pop = 60, threshold = 50))
    }

    @Test
    fun `alert should not fire when pop equals threshold`() {
        assertFalse(WeatherWorker.shouldNotify(pop = 50, threshold = 50))
    }

    @Test
    fun `alert should not fire when pop is below threshold`() {
        assertFalse(WeatherWorker.shouldNotify(pop = 30, threshold = 50))
    }

    // -----------------------------------------------------------------------
    // WeatherWorker – run gate (manual check bypasses the monitoring toggle)
    // -----------------------------------------------------------------------

    @Test
    fun `scheduled run proceeds only when monitoring is enabled`() {
        assertTrue(WeatherWorker.shouldRun(isManualCheck = false, monitoringEnabled = true))
        assertFalse(WeatherWorker.shouldRun(isManualCheck = false, monitoringEnabled = false))
    }

    @Test
    fun `manual check always proceeds regardless of monitoring toggle`() {
        assertTrue(WeatherWorker.shouldRun(isManualCheck = true, monitoringEnabled = true))
        assertTrue(WeatherWorker.shouldRun(isManualCheck = true, monitoringEnabled = false))
    }

    // -----------------------------------------------------------------------
    // WeatherWorker – location fallback resolution
    // -----------------------------------------------------------------------

    @Test
    fun `resolveLocation uses the geocoded city when available`() {
        val resolved = WeatherWorker.resolveLocation("高雄市")
        assertEquals("高雄市", resolved.cityName)
        assertFalse(resolved.isFallback)
    }

    @Test
    fun `resolveLocation falls back to the default city when geocoding failed`() {
        val resolved = WeatherWorker.resolveLocation(null)
        assertEquals(PreferenceHelper.DEFAULT_FALLBACK_CITY, resolved.cityName)
        assertTrue(resolved.isFallback)
    }

    // -----------------------------------------------------------------------
    // PreferenceHelper defaults (pure constant checks)
    // -----------------------------------------------------------------------

    @Test
    fun `default threshold is 50 percent`() {
        assertEquals(50, PreferenceHelper.DEFAULT_THRESHOLD)
    }

    @Test
    fun `monitoring is disabled by default`() {
        assertFalse(PreferenceHelper.DEFAULT_MONITORING_ENABLED)
    }

    @Test
    fun `default check interval is 60 minutes`() {
        assertEquals(60, PreferenceHelper.DEFAULT_CHECK_INTERVAL_MINUTES)
    }

    @Test
    fun `interval options are ascending and respect WorkManager's 15-minute minimum`() {
        val options = PreferenceHelper.INTERVAL_OPTIONS_MINUTES
        assertEquals(15, options.min())
        assertEquals(options.toList(), options.sorted())
        assertTrue(PreferenceHelper.DEFAULT_CHECK_INTERVAL_MINUTES in options)
    }

    @Test
    fun `location fallback defaults to Taipei and is off by default`() {
        assertEquals("臺北市", PreferenceHelper.DEFAULT_FALLBACK_CITY)
        assertFalse(PreferenceHelper.DEFAULT_LOCATION_IS_FALLBACK)
    }

    // -----------------------------------------------------------------------
    // NotificationHelper – alert message wording
    // -----------------------------------------------------------------------

    @Test
    fun `alert message follows the required wording`() {
        val message = NotificationHelper.buildAlertMessage("臺北市", 50)
        assertEquals("您所在的臺北市，目前降雨機率已超過50%，請多加留意。", message)
    }

    @Test
    fun `alert message substitutes location and threshold correctly`() {
        val message = NotificationHelper.buildAlertMessage("高雄市", 80)
        assertEquals("您所在的高雄市，目前降雨機率已超過80%，請多加留意。", message)
    }
}
