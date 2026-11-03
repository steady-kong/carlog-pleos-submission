package kr.carlog.pleos

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Maps the stored Korean UI categories to stable v1 exchange identifiers. */
fun eventCategoryCode(category: String): String = when (category) {
    "차량 이상" -> "vehicle_issue"
    "도로 상태" -> "road_condition"
    "특이 주행 상황" -> "unusual_driving"
    "위치 메모" -> "location_note"
    "일반 이벤트" -> "general"
    else -> throw IllegalArgumentException("Unknown event category: $category")
}

fun exportEventJson(event: EventEntity, samples: List<SampleEntity>): String {
    require(event.source in setOf("MOCK", "VEHICLE"))
    require(event.status in setOf("collecting", "complete", "interrupted"))
    require(samples.all { it.eventId == event.id && it.value.isFinite() })
    val items = JSONArray()
    samples.sortedWith(compareBy<SampleEntity> { it.timestamp }.thenBy { it.name }).forEach { sample ->
        items.put(JSONObject().put("timestamp", Instant.ofEpochMilli(sample.timestamp).toString())
            .put("name", sample.name).put("value", sample.value).put("unit", sample.unit))
    }
    return JSONObject().put("schemaVersion", 1).put("id", event.id).put("vehicleId", event.vehicleId)
        .put("occurredAt", Instant.ofEpochMilli(event.timestamp).toString())
        .put("category", eventCategoryCode(event.category)).put("memo", event.memo)
        .put("source", event.source).put("status", event.status).put("samples", items).toString(2)
}

/** Each signal keeps its own most recent timestamp at or before the event. */
fun eventSnapshot(samples: List<SampleEntity>, at: Long): Map<String, SampleEntity> =
    samples.filter { it.timestamp <= at }.groupBy { it.name }.mapValues { (_, values) -> values.maxBy { it.timestamp } }
