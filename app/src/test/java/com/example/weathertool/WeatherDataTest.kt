package com.example.weathertool

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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
        val result = WeatherWorker.extractPoP(response, "臺北市")
        assertEquals(70, result.pop)
        assertEquals("2024-01-01 06:00:00", result.slotStartTime)
    }

    @Test
    fun `PoP falls back to the first location when the requested city is missing`() {
        val response = buildResponse("高雄市", "40")
        assertEquals(40, WeatherWorker.extractPoP(response, "臺北市").pop)
    }

    @Test
    fun `PoP extraction returns -1 and no slot when element list is empty`() {
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
        val result = WeatherWorker.extractPoP(response, "臺北市")
        assertEquals(-1, result.pop)
        assertNull(result.slotStartTime)
    }

    @Test
    fun `PoP extraction returns -1 when location list is null`() {
        val response = WeatherResponse(success = "true", records = WeatherRecords(null, null))
        assertEquals(-1, WeatherWorker.extractPoP(response, "臺北市").pop)
    }

    // -----------------------------------------------------------------------
    // WeatherWorker – time-slot selection (picks the window covering "now",
    // not just the array's first entry)
    // -----------------------------------------------------------------------

    private val taipeiFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).apply {
        timeZone = TimeZone.getTimeZone("Asia/Taipei")
    }

    /** Three consecutive 12-hour windows: 00:00–12:00, 12:00–24:00, 24:00(next day)–12:00. */
    private val threeSlots = listOf(
        TimeData("2024-06-01 00:00:00", "2024-06-01 12:00:00", Parameter("20", "百分比")),
        TimeData("2024-06-01 12:00:00", "2024-06-02 00:00:00", Parameter("50", "百分比")),
        TimeData("2024-06-02 00:00:00", "2024-06-02 12:00:00", Parameter("80", "百分比"))
    )

    @Test
    fun `selectTimeSlot picks the window that contains now`() {
        val now = taipeiFormat.parse("2024-06-01 18:00:00")!!
        assertEquals(threeSlots[1], WeatherWorker.selectTimeSlot(threeSlots, now))
    }

    @Test
    fun `selectTimeSlot picks the first window when now is before all windows`() {
        val now = taipeiFormat.parse("2024-05-31 08:00:00")!!
        assertEquals(threeSlots[0], WeatherWorker.selectTimeSlot(threeSlots, now))
    }

    @Test
    fun `selectTimeSlot falls back to the last window when now is after all windows`() {
        val now = taipeiFormat.parse("2024-06-03 00:00:00")!!
        assertEquals(threeSlots[2], WeatherWorker.selectTimeSlot(threeSlots, now))
    }

    @Test
    fun `selectTimeSlot returns null for an empty list`() {
        assertNull(WeatherWorker.selectTimeSlot(emptyList()))
    }

    @Test
    fun `extractPoP uses the slot covering now, not just the first entry`() {
        val response = WeatherResponse(
            success = "true",
            records = WeatherRecords(
                datasetDescription = null,
                location = listOf(LocationData("臺北市", listOf(WeatherElement("PoP", threeSlots))))
            )
        )
        val now = taipeiFormat.parse("2024-06-01 18:00:00")!!
        val result = WeatherWorker.extractPoP(response, "臺北市", now)
        assertEquals(50, result.pop)
        assertEquals("2024-06-01 12:00:00", result.slotStartTime)
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
    // WeatherWorker – dedupe repeat notifications for the same forecast slot
    // -----------------------------------------------------------------------

    @Test
    fun `a slot never notified before is new`() {
        assertTrue(WeatherWorker.isNewNotifiableSlot("2024-06-01 06:00:00", lastNotifiedSlotStart = ""))
    }

    @Test
    fun `the same slot already notified is not new`() {
        assertFalse(
            WeatherWorker.isNewNotifiableSlot(
                "2024-06-01 06:00:00",
                lastNotifiedSlotStart = "2024-06-01 06:00:00"
            )
        )
    }

    @Test
    fun `a different slot than the last notified one is new`() {
        assertTrue(
            WeatherWorker.isNewNotifiableSlot(
                "2024-06-01 18:00:00",
                lastNotifiedSlotStart = "2024-06-01 06:00:00"
            )
        )
    }

    @Test
    fun `a null slot is never notifiable`() {
        assertFalse(WeatherWorker.isNewNotifiableSlot(null, lastNotifiedSlotStart = ""))
    }

    // -----------------------------------------------------------------------
    // WeatherWorker – retryable vs. non-retryable HTTP errors
    // -----------------------------------------------------------------------

    @Test
    fun `invalid API key status codes are not retryable`() {
        assertTrue(WeatherWorker.isNonRetryableHttpError(401))
        assertTrue(WeatherWorker.isNonRetryableHttpError(403))
    }

    @Test
    fun `transient or unrelated status codes remain retryable`() {
        assertFalse(WeatherWorker.isNonRetryableHttpError(429))
        assertFalse(WeatherWorker.isNonRetryableHttpError(500))
        assertFalse(WeatherWorker.isNonRetryableHttpError(503))
        assertFalse(WeatherWorker.isNonRetryableHttpError(404))
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

    @Test
    fun `no forecast slot has been notified about by default`() {
        assertEquals("", PreferenceHelper.DEFAULT_LAST_NOTIFIED_SLOT_START)
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
