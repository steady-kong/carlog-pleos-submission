package kr.carlog.pleos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EventExchangeTest {
    private val event = EventEntity(id = "e", vehicleId = "v", timestamp = 60_000, category = "도로 상태", memo = "따옴표 \"와 줄\n바꿈", source = "MOCK", status = "complete")
    private val samples = listOf(SampleEntity("e", 61_000, "fuel", 42.0, "%"), SampleEntity("e", 59_000, "fuel", 45.0, "%"), SampleEntity("e", 58_000, "latitude", 37.5, "deg"))

    @Test fun exportUsesVersionedContractAndUtcSampleTimes() {
        val json = JSONObject(exportEventJson(event, samples))
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("road_condition", json.getString("category"))
        assertEquals("MOCK", json.getString("source"))
        assertEquals(event.memo, json.getString("memo"))
        assertEquals("1970-01-01T00:01:00Z", json.getString("occurredAt"))
        assertEquals("1970-01-01T00:00:58Z", json.getJSONArray("samples").getJSONObject(0).getString("timestamp"))
        assertEquals(3, json.getJSONArray("samples").length())
    }

    @Test fun snapshotNeverUsesFutureSampleAndPreservesSignalTimes() {
        val snapshot = eventSnapshot(samples, event.timestamp)
        assertEquals(45.0, snapshot.getValue("fuel").value, 0.0)
        assertEquals(59_000L, snapshot.getValue("fuel").timestamp)
        assertEquals(58_000L, snapshot.getValue("latitude").timestamp)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsSamplesFromAnotherEvent() {
        exportEventJson(event, listOf(SampleEntity("other", 0, "fuel", 0.0, "%")))
    }
}
