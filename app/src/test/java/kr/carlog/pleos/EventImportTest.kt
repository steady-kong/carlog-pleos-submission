package kr.carlog.pleos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EventImportTest {
    @Test fun nonzeroSignalValuesMustNotSilentlyUnderflowToZero() {
        val example = requireNotNull(javaClass.getResourceAsStream("/event-v1.json")).bufferedReader().use { it.readText() }
        fun value(token: String) = example.replaceFirst(Regex("\\\"value\\\"\\s*:\\s*12500\\.5"), "\"value\": $token")
        assertEquals(0.0, parseEventJson(value("0e-999")).samples.first().value, 0.0)
        assertTrue(parseEventJson(value("5e-324")).samples.first().value > 0.0)
        for (token in listOf("1e-999", "-1e-999")) {
            try { parseEventJson(value(token)); fail("underflow must not silently become zero") }
            catch (_: IllegalArgumentException) { }
        }
    }
    private val event = EventEntity(id = "5a18ab5a-0a1e-4c91-b208-208a9e5cbf1d", vehicleId = "bcf4b06f-7654-4955-8d68-67c55fc01887", timestamp = 100_000L,
        category = "도로 상태", memo = "도로 메모", source = "MOCK", status = "complete")
    private val samples = listOf(SampleEntity(event.id, 99_000L, "fuel", 50.0, "%"), SampleEntity(event.id, 101_000L, "latitude", 37.0, "deg"))

    @Test fun rejectsTimesJavaWouldSilentlyNormalize() {
        val root = JSONObject(exportEventJson(event, emptyList()))
        for (invalid in listOf("2016-12-31T23:59:60Z", "2020-01-01T24:00:00Z")) {
            try { parseEventJson(root.put("occurredAt", invalid).toString()); fail("timestamp must not be normalized") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(1L, parseExchangeTimestamp("1970-01-01T00:00:00.001Z"))
        val badSample = JSONObject(exportEventJson(event, samples))
        badSample.getJSONArray("samples").getJSONObject(1).put("timestamp", "2020-01-01T24:00:00Z")
        try { parseEventJson(badSample.toString()); fail("invalid sample time") }
        catch (e: ExchangeTimestampException) { assertEquals("신호 2번째 시각", e.location) }
    }

    @Test fun directParserEnforcesUtf8ByteLimitAtBoundary() {
        val root = JSONObject(exportEventJson(event.copy(memo = ""), emptyList()))
        val empty = root.toString()
        val available = EVENT_EXCHANGE_MAX_BYTES - empty.toByteArray(Charsets.UTF_8).size
        val prefix = "가".repeat(available / 3) + "x".repeat(available % 3)
        val exact = root.put("memo", prefix).toString()
        assertEquals(EVENT_EXCHANGE_MAX_BYTES, exact.toByteArray(Charsets.UTF_8).size)
        assertEquals(prefix.length, parseEventJson(exact).event.memo.length)
        val over = root.put("memo", prefix + "x").toString()
        try { parseEventJson(over); fail("oversized UTF-8 input must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun familiarSignalLabelsPreserveUnknownNamesAndExchangeIdentifiers() {
        assertEquals("연료 잔량", signalDisplayName("fuel"))
        assertEquals("EV 배터리 충전율", signalDisplayName("ev_battery_soc"))
        assertEquals("custom_sensor", signalDisplayName("custom_sensor"))
        assertEquals("fuel", parseEventJson(exportEventJson(event, samples)).samples.first().name)
    }

    @Test fun documentedExchangeExampleCanBeImportedAndReexported() {
        val text = javaClass.getResourceAsStream("/event-v1.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val imported = parseEventJson(text)
        assertEquals("MOCK", imported.event.source)
        assertEquals("도로 상태", imported.event.category)
        assertEquals(2, imported.samples.size)
        assertTrue(JSONObject(text).similar(JSONObject(exportEventJson(imported.event, imported.samples))))
        assertEquals(imported, parseEventJson(exportEventJson(imported.event, imported.samples)))
    }

    @Test fun documentedV2ExampleRetainsTimestampProvenance() {
        val text = javaClass.getResourceAsStream("/event-v2.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val imported = parseEventJson(text)
        assertTrue(imported.samples.any { it.timestampKind == "RECEIVED" })
        assertTrue(imported.samples.any { it.timestampKind == "MEASURED" })
        assertTrue(JSONObject(text).similar(JSONObject(exportEventJson(imported.event, imported.samples))))
        assertEquals(imported, parseEventJson(exportEventJson(imported.event, imported.samples)))
    }

    @Test fun exportedEventRoundTripsWithoutChangingValues() {
        val imported = parseEventJson(exportEventJson(event, samples))
        assertEquals(event, imported.event)
        assertEquals(samples, imported.samples)
    }

    @Test fun coverageDescribesObservedSamplesWithoutAssumingContinuity() {
        assertEquals(EventCoverage(0, 0, null, null), eventCoverage(emptyList(), event.timestamp))
        val values = samples + SampleEntity(event.id, event.timestamp, "odometer", 100.0, "km")
        assertEquals(EventCoverage(2, 1, 99_000L, 101_000L), eventCoverage(values, event.timestamp))
    }

    @Test fun locationRequiresValidDegreeCoordinatesForMap() {
        val latitude = SampleEntity(event.id, 99_000, "latitude", 90.0, "deg")
        val longitude = SampleEntity(event.id, 99_000, "longitude", -180.0, "deg")
        assertTrue(isValidEventLocation(latitude, longitude))
        assertFalse(isValidEventLocation(null, longitude))
        assertFalse(isValidEventLocation(latitude.copy(value = 90.1), longitude))
        assertFalse(isValidEventLocation(latitude, longitude.copy(value = -180.1)))
        assertFalse(isValidEventLocation(latitude.copy(unit = "rad"), longitude))
        assertFalse(isValidEventLocation(latitude, longitude.copy(value = Double.NaN)))
        assertFalse(isValidEventLocation(longitude, latitude))
        assertFalse(isValidEventLocation(latitude, longitude.copy(timestamp = latitude.timestamp + 1)))
        assertFalse(isValidEventLocation(latitude, longitude.copy(eventId = "another-event")))
    }

    @Test fun rejectsDuplicateSampleKeys() {
        val json = JSONObject(exportEventJson(event, samples))
        val items = json.getJSONArray("samples")
        items.put(items.getJSONObject(0))
        try { parseEventJson(json.toString()); fail("duplicate keys should be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsDuplicateEventFieldsBeforeImport() {
        val json = exportEventJson(event, samples).replaceFirst("{", "{\"id\":\"${event.id}\",")
        try { parseEventJson(json); fail("duplicate id should be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsCollectingEventsAndSubMillisecondTimes() {
        val json = JSONObject(exportEventJson(event, samples))
        json.put("status", "collecting")
        try { parseEventJson(json.toString()); fail("collecting event should be rejected") }
        catch (_: IllegalArgumentException) { }
        json.put("status", "complete")
        json.put("occurredAt", "1970-01-01T00:01:40.000001Z")
        try { parseEventJson(json.toString()); fail("precision loss should be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsUnknownFieldsAndOutOfWindowSamples() {
        val json = JSONObject(exportEventJson(event, samples))
        json.put("unexpected", true)
        try { parseEventJson(json.toString()); fail("unknown fields should be rejected") }
        catch (_: IllegalArgumentException) { }
        json.remove("unexpected")
        json.getJSONArray("samples").getJSONObject(0).put("timestamp", "1970-01-01T00:00:01Z")
        try { parseEventJson(json.toString()); fail("outside window should be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun exportRejectsDataThatTheImporterCannotAccept() {
        fun rejected(value: EventEntity = event, entries: List<SampleEntity> = samples) {
            try { exportEventJson(value, entries); fail("unsupported export should be rejected") }
            catch (_: IllegalArgumentException) { }
        }
        rejected(value = event.copy(status = "collecting"))
        rejected(entries = samples + samples.first())
        rejected(entries = listOf(samples.first().copy(timestamp = 1)))
        rejected(entries = List(EVENT_EXCHANGE_MAX_SAMPLES + 1) { samples.first().copy(timestamp = 40_000L + it) })
        rejected(value = event.copy(memo = "가".repeat(EVENT_EXCHANGE_MAX_BYTES / 3 + 1)))
    }
}
