package kr.carlog.pleos

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDateTime
import java.time.DateTimeException
import java.time.format.DateTimeFormatter
import java.util.UUID

const val EVENT_EXCHANGE_MAX_BYTES = 5 * 1024 * 1024
const val EVENT_EXCHANGE_MAX_SAMPLES = 10_000
enum class EventExportLimit { SAMPLE_COUNT, FILE_SIZE }
class EventExportLimitException(val limit: EventExportLimit) : IllegalArgumentException("Event export limit: $limit")
class ExchangeTimestampException(val location: String? = null, cause: Throwable? = null) : IllegalArgumentException("Invalid exchange timestamp", cause)
private val exchangeTimestampPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")
/** Reject leap seconds and 24:00 rather than silently normalizing them. */
fun parseExchangeTimestamp(value: String, location: String? = null): Long {
    if (!exchangeTimestampPattern.matches(value)) throw ExchangeTimestampException(location)
    val local = try { LocalDateTime.parse(value.dropLast(1), DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
    catch (e: DateTimeException) { throw ExchangeTimestampException(location, e) }
    if (local.year !in 1..9999 || local.nano % 1_000_000 != 0) throw ExchangeTimestampException(location)
    return local.toInstant(ZoneOffset.UTC).toEpochMilli()
}
fun eventExportLimitMessage(limit: EventExportLimit): String = when (limit) {
    EventExportLimit.SAMPLE_COUNT -> "신호가 1만 개를 넘어 이벤트 JSON을 내보낼 수 없습니다. 원본 기록은 유지되며 정비 참고 리포트를 이용할 수 있습니다."
    EventExportLimit.FILE_SIZE -> "이벤트 JSON이 5 MiB를 넘어 내보낼 수 없습니다. 원본 기록은 유지되며 정비 참고 리포트를 이용할 수 있습니다."
}

/** Maps the stored Korean UI categories to stable v1 exchange identifiers. */
fun eventCategoryCode(category: String): String = when (category) {
    "차량 이상" -> "vehicle_issue"
    "도로 상태" -> "road_condition"
    "특이 주행 상황" -> "unusual_driving"
    "위치 메모" -> "location_note"
    "일반 이벤트" -> "general"
    else -> throw IllegalArgumentException("Unknown event category: $category")
}

/** A compact preview, while the complete memo remains stored unchanged. */
fun eventMemoPreview(memo: String): String {
    if (memo.isBlank()) return "메모 없음"
    val count = memo.codePointCount(0, memo.length)
    val end = memo.offsetByCodePoints(0, minOf(count, 60))
    val prefix = memo.substring(0, end).replace('\n', ' ').replace('\r', ' ')
    return prefix + if (count > 60) "…" else ""
}

fun exportEventJson(event: EventEntity, samples: List<SampleEntity>): String {
    require(event.source in setOf("MOCK", "VEHICLE"))
    require(event.status in setOf("complete", "interrupted"))
    if (samples.size > EVENT_EXCHANGE_MAX_SAMPLES) throw EventExportLimitException(EventExportLimit.SAMPLE_COUNT)
    require(samples.all { it.eventId == event.id && it.value.isFinite() })
    require(samples.all { validTimestampKind(it.timestampKind) }) { "Invalid timestamp provenance" }
    // Keep legacy files readable by v1 clients when no provenance is known.
    val version = if (samples.any { it.timestampKind != null }) 2 else 1
    val items = JSONArray()
    samples.sortedWith(compareBy<SampleEntity> { it.timestamp }.thenBy { it.name }).forEach { sample ->
        val item = JSONObject().put("timestamp", Instant.ofEpochMilli(sample.timestamp).toString())
            .put("name", sample.name).put("value", sample.value).put("unit", sample.unit)
        if (version == 2) item.put("timestampKind", sample.timestampKind ?: JSONObject.NULL)
        items.put(item)
    }
    val json = JSONObject().put("schemaVersion", version).put("id", event.id).put("vehicleId", event.originalVehicleId ?: event.vehicleId)
        .put("occurredAt", Instant.ofEpochMilli(event.timestamp).toString())
        .put("category", eventCategoryCode(event.category)).put("memo", event.memo)
        .put("source", event.source).put("status", event.status).put("samples", items).toString(2)
    if (json.toByteArray(Charsets.UTF_8).size > EVENT_EXCHANGE_MAX_BYTES) throw EventExportLimitException(EventExportLimit.FILE_SIZE)
    parseEventJson(json)
    return json
}

/** Each signal keeps its own most recent timestamp at or before the event. */
fun eventSnapshot(samples: List<SampleEntity>, at: Long): Map<String, SampleEntity> =
    samples.filter { it.timestamp <= at }.groupBy { it.name }.mapValues { (_, values) -> values.maxBy { it.timestamp } }

/** Different units and timestamp origins are distinct observations, not interchangeable values. */
fun eventSeriesSnapshot(samples: List<SampleEntity>, at: Long): List<SampleEntity> =
    samples.filter { it.timestamp <= at }.groupBy(::signalSeriesKey).values.map { values -> values.maxBy { it.timestamp } }

/** Only same-event, same-timestamp and same-kind WGS84 pairs can be handed to a map application. */
fun isValidEventLocation(latitude: SampleEntity?, longitude: SampleEntity?): Boolean =
    latitude != null && longitude != null && latitude.name == "latitude" && longitude.name == "longitude" &&
        latitude.eventId == longitude.eventId && latitude.timestamp == longitude.timestamp &&
        validTimestampKind(latitude.timestampKind) && latitude.timestampKind == longitude.timestampKind &&
        latitude.unit == "deg" && longitude.unit == "deg" && latitude.value.isFinite() && longitude.value.isFinite() &&
        latitude.value in -90.0..90.0 && longitude.value in -180.0..180.0

/** Select a complete observed pair, never mixing timestamps or using data after the event. */
fun eventLocation(samples: List<SampleEntity>, eventId: String, at: Long): Pair<SampleEntity, SampleEntity>? {
    val candidates = samples.filter { it.eventId == eventId && it.timestamp <= at && it.name in setOf("latitude", "longitude") }
        .groupBy { it.timestamp }.toSortedMap(compareByDescending { it })
    for ((_, values) in candidates) {
        val latitude = values.firstOrNull { it.name == "latitude" }
        val longitude = values.firstOrNull { it.name == "longitude" }
        if (isValidEventLocation(latitude, longitude)) return latitude!! to longitude!!
    }
    return null
}

/** Describes observed samples only; a time range does not establish continuous coverage. */
data class EventCoverage(val beforeOrAt: Int, val after: Int, val earliest: Long?, val latest: Long?)
/** Offsets describe observed timestamps, not continuous collection or sensor latency. */
fun eventOffset(timestamp: Long, eventAt: Long): String {
    val delta = java.math.BigInteger.valueOf(timestamp).subtract(java.math.BigInteger.valueOf(eventAt))
    return (if (delta.signum() > 0) "+" else "") + delta.toString() + "ms"
}
fun eventCoverage(samples: List<SampleEntity>, eventAt: Long): EventCoverage = EventCoverage(
    beforeOrAt = samples.count { it.timestamp <= eventAt },
    after = samples.count { it.timestamp > eventAt },
    earliest = samples.minOfOrNull { it.timestamp },
    latest = samples.maxOfOrNull { it.timestamp },
)

/** Unknown imported names stay visible instead of being relabeled or discarded. */
fun signalDisplayName(name: String): String = when (name) {
    "odometer" -> "누적 주행거리"
    "fuel" -> "연료 잔량"
    "ev_battery_soc" -> "EV 배터리 충전율"
    "brake_pedal_pressed" -> "브레이크 페달 눌림"
    "latitude" -> "위도"
    "longitude" -> "경도"
    else -> name
}

/** Never draw timestamps with different meanings as a single continuous series. */
data class SignalSeriesKey(val name: String, val unit: String, val timestampKind: String?)
fun signalSeriesKey(sample: SampleEntity) = SignalSeriesKey(sample.name, sample.unit, sample.timestampKind)

/** Imported source labels are claims; the app has no mechanism to verify their origin. */
fun eventSourceLabel(source: String): String = when (source) {
    "MOCK" -> "시뮬레이션 (MOCK)"
    "VEHICLE" -> "차량 데이터로 기록됨 (VEHICLE, 실차 여부 미확인)"
    else -> "알 수 없는 기록값 ($source)"
}

/** The report is a reference to recorded data, not a diagnosis or proof of source. */
fun maintenanceReport(event: EventEntity, samples: List<SampleEntity>, repairs: List<RecordEntity>, vehicle: VehicleEntity? = null): String {
    fun formatTime(timestamp: Long) = Instant.ofEpochMilli(timestamp).toString()
    val snapshot = eventSnapshot(samples, event.timestamp)
    val coverage = eventCoverage(samples, event.timestamp)
    val coverageText = if (coverage.earliest == null || coverage.latest == null) "저장된 신호 없음" else
        "발생 전·시점 ${coverage.beforeOrAt}개, 이후 ${coverage.after}개 · 기록 범위 ${formatTime(coverage.earliest)} ~ ${formatTime(coverage.latest)}"
    val lat = snapshot["latitude"]; val lon = snapshot["longitude"]
    val location = eventLocation(samples, event.id, event.timestamp)
    val locationWarning = when {
        location != null -> "\n발생 전 마지막 좌표: ${location.first.value}, ${location.second.value} (${formatTime(location.first.timestamp)}, ${timestampKindLabel(location.first.timestampKind)})"
        lat != null || lon != null -> "\n위치 신호는 단위·범위 또는 좌표 쌍의 동일 시각과 시각 종류를 확인할 수 없습니다."
        else -> ""
    }
    fun describe(values: Collection<SampleEntity>): String = values.sortedBy { it.name }
        .joinToString {
            val label = signalDisplayName(it.name)
            val identifier = if (label == it.name) label else "$label (${it.name})"
            "$identifier ${it.value} ${it.unit} (${formatTime(it.timestamp)}, ${timestampKindLabel(it.timestampKind)})"
        }.ifBlank { "기록 없음" }
    val snapshotText = describe(eventSeriesSnapshot(samples, event.timestamp))
    // A last observed value is not necessarily the event-end state or a continuous history.
    val after = samples.filter { it.timestamp > event.timestamp }.groupBy(::signalSeriesKey)
        .values.map { values -> values.maxBy { it.timestamp } }
    val afterText = describe(after)
    // Bound the added breakdown if an imported event contains many distinct names.
    val series = samples.groupBy(::signalSeriesKey).entries
        .sortedWith(compareBy({ it.key.name }, { it.key.unit }, { it.key.timestampKind.orEmpty() }))
    val seriesText = series.take(100).joinToString("\n") { (key, values) ->
        val observed = eventCoverage(values, event.timestamp)
        val label = signalDisplayName(key.name)
        val identifier = if (label == key.name) label else "$label (${key.name})"
        "$identifier · ${key.unit} · ${timestampKindLabel(key.timestampKind)}: 전·시점 ${observed.beforeOrAt}개, 이후 ${observed.after}개 · ${formatTime(requireNotNull(observed.earliest))} ~ ${formatTime(requireNotNull(observed.latest))} · 발생 대비 ${eventOffset(observed.earliest, event.timestamp)} ~ ${eventOffset(observed.latest, event.timestamp)}"
    }.ifBlank { "기록 없음" } + if (series.size > 100) "\n나머지 ${series.size - 100}개 시계열은 리포트에서 생략됨" else ""
    val repairText = repairs.filter { it.deletedAt == null && it.type == "정비" && it.relatedEventId == event.id && it.vehicleId == event.vehicleId }
        .sortedByDescending { it.timestamp }
        .joinToString("\n") { "${formatTime(it.timestamp)} · ${it.label} · ${it.repairOutcome ?: "결과 미입력"}" }
        .ifBlank { "연결된 정비 없음" }
    val originalCar = event.originalVehicleId?.let { "\n원본 차량 ID: $it" } ?: ""
    val carDetails = vehicle?.takeIf { it.id == event.vehicleId }?.let { car ->
        val fuel = car.fuelType?.let { type -> vehicleFuelTypes.firstOrNull { it.first == type }?.second ?: "연료 코드 $type" }
        val details = listOfNotNull(car.name, car.maker, car.model, car.year?.let { "${it}년" }, fuel).joinToString(" · ")
        "현재 등록 차량 정보: $details"
    } ?: "현재 등록 차량 정보: 확인할 수 없음"
    val statusText = when (event.status) {
        "collecting" -> "수집 중 (미완료). 이 리포트는 미리보기를 연 시점까지의 기록입니다."
        "complete" -> "수집 종료. 모든 신호의 전후 구간이 채워졌다는 뜻은 아닙니다."
        "interrupted" -> "수집 중단. 후반 구간이 완성되지 않았을 수 있습니다."
        else -> "알 수 없는 상태 (${event.status})"
    }
    return "Carlog 정비 참고 리포트\n시각: UTC (ISO 8601)\n이벤트 ID: ${event.id}\n차량 ID: ${event.vehicleId}$originalCar\n" +
        "$carDetails\n${formatTime(event.timestamp)} · ${event.category}\n출처(기록값): ${eventSourceLabel(event.source)} · 수집: $statusText\n" +
        "메모: ${event.memo}\n발생 시점 또는 그 이전의 마지막 관측값: $snapshotText\n발생 후 마지막 관측값: $afterText$locationWarning\n$coverageText\n신호별 관측 범위:\n$seriesText\n" +
        "기록 범위는 연속 수집을 보장하지 않습니다. 진단 결과가 아닌 운전자 참고 기록입니다.\n연결된 정비:\n$repairText"
}

data class ImportedEvent(val event: EventEntity, val samples: List<SampleEntity>)

/** Strict import avoids silently truncating timestamps or overwriting duplicate sample keys. */
fun parseEventJson(text: String): ImportedEvent {
    require(text.length <= EVENT_EXCHANGE_MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= EVENT_EXCHANGE_MAX_BYTES) { "Event file too large" }
    requireUnambiguousJson(text, setOf(1, 2), checkEventSignalNumbers = true)
    val root = JSONObject(text)
    fun JSONObject.requireKeys(allowed: Set<String>) {
        require(keys().asSequence().all { it in allowed }) { "Unknown field" }
    }
    fun JSONObject.string(key: String): String = get(key) as? String ?: throw IllegalArgumentException("Invalid $key")
    fun uuid(value: String): String {
        require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
        return UUID.fromString(value).toString()
    }
    root.requireKeys(setOf("schemaVersion", "id", "vehicleId", "occurredAt", "category", "memo", "source", "status", "samples"))
    val version = (root.get("schemaVersion") as? Number)?.toDouble()
    require(version == 1.0 || version == 2.0) { "Unsupported schema version" }
    val id = uuid(root.string("id"))
    val carId = uuid(root.string("vehicleId"))
    val occurredAt = parseExchangeTimestamp(root.string("occurredAt"), "이벤트 발생 시각")
    val category = when (root.string("category")) {
        "vehicle_issue" -> "차량 이상"
        "road_condition" -> "도로 상태"
        "unusual_driving" -> "특이 주행 상황"
        "location_note" -> "위치 메모"
        "general" -> "일반 이벤트"
        else -> throw IllegalArgumentException("Unknown category")
    }
    val source = root.string("source").also { require(it == "MOCK" || it == "VEHICLE") }
    val status = root.string("status").also { require(it == "complete" || it == "interrupted") { "Event must not be collecting" } }
    val memo = if (root.has("memo")) root.string("memo") else ""
    val array = root.get("samples") as? JSONArray ?: throw IllegalArgumentException("Invalid samples")
    require(array.length() <= EVENT_EXCHANGE_MAX_SAMPLES) { "Too many samples" }
    val samples = (0 until array.length()).map { index ->
        val item = array.get(index) as? JSONObject ?: throw IllegalArgumentException("Invalid sample")
        item.requireKeys(if (version == 2.0) setOf("timestamp", "name", "value", "unit", "timestampKind") else setOf("timestamp", "name", "value", "unit"))
        val kind = if (version == 2.0) {
            require(item.has("timestampKind")) { "Missing timestamp provenance" }
            if (item.isNull("timestampKind")) null else item.string("timestampKind")
        } else null
        require(validTimestampKind(kind)) { "Invalid timestamp provenance" }
        val at = parseExchangeTimestamp(item.string("timestamp"), "신호 ${index + 1}번째 시각")
        require(at in eventWindow(occurredAt)) { "Sample outside event window" }
        val name = item.string("name")
        val unit = item.string("unit")
        require(validSignalText(name, unit)) { "Invalid signal name or unit" }
        val value = (item.get("value") as? Number)?.toDouble() ?: throw IllegalArgumentException("Invalid value")
        require(value.isFinite())
        SampleEntity(id, at, name, value, unit, kind)
    }
    require(samples.map { it.timestamp to it.name }.toSet().size == samples.size) { "Duplicate sample key" }
    return ImportedEvent(EventEntity(id, carId, occurredAt, category, memo, source, status), samples)
}
