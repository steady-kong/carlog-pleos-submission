package kr.carlog.pleos

import kotlin.math.floor
import java.math.BigInteger
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeMap

class RecordEditConflictException : IllegalStateException("Record changed after editing started")

val vehicleFuelTypes = listOf("gasoline" to "휘발유", "diesel" to "경유", "lpg" to "LPG", "hybrid" to "하이브리드", "ev" to "전기")
fun validVehicleInfo(name: String, initialKm: Double, year: Int?, fuelType: String?): Boolean =
    name.isNotBlank() && initialKm.isFinite() && initialKm >= 0 && (year == null || year in 1886..2100) &&
        (fuelType == null || vehicleFuelTypes.any { it.first == fuelType })

/** Distances are km, fuel is L, money is KRW; timestamps are UTC epoch milliseconds. */
/** Validates records at the persistence boundary as well as in the form. */
fun validRecordInput(type: String, label: String, odometer: Double?, amount: Double, cost: Long,
    engineHours: Double?, engineHoursMode: String?, relatedEventId: String?, repairOutcome: String?): Boolean {
    if (type !in setOf("주유", "정비", "지출", "주행거리") || label.isBlank() || cost < 0) return false
    if (odometer == null && type != "지출") return false
    if (odometer != null && (!odometer.isFinite() || odometer < 0)) return false
    if (!amount.isFinite() || if (type == "주유") amount <= 0 else amount != 0.0) return false
    if (type == "주행거리" && cost != 0L) return false
    if (engineHours != null && (type != "주유" || !engineHours.isFinite() || engineHours < 0)) return false
    if (engineHoursMode != null && (engineHours == null || engineHoursMode !in setOf("incremental", "cumulative"))) return false
    if (type != "정비" && (relatedEventId != null || repairOutcome != null)) return false
    return true
}

/** Show the stored fields that distinguish common records in the history list. */
fun recordDetails(record: RecordEntity): String? = when (record.type) {
    "주유" -> buildList {
        add("주유량 ${record.amount} L")
        add(if (record.full) "가득 주유" else "가득 주유 아님")
        record.engineHours?.let { hours ->
            val mode = if (record.engineHoursMode == "incremental") "증가분" else "교체 후 누적"
            add("엔진 가동시간 $hours 시간 ($mode)")
        }
    }.joinToString(" · ")
    "지출" -> "분류: ${record.category?.takeIf { it.isNotBlank() } ?: "기타"}"
    "정비" -> if (record.category == "engine_oil") "엔진오일" else null
    else -> null
}

/** Compact summary for the dashboard; missing mileage is not represented as zero. */
fun dashboardRecordSummary(record: RecordEntity): String = buildList {
    add("${record.type} ${record.label}")
    if (record.type == "주유") add("${record.amount} L")
    add(if (record.odometerKnown) "${record.odometer} km" else "주행거리 미입력")
    if (record.type in setOf("주유", "정비", "지출")) add("${record.cost}원")
}.joinToString(" · ")

data class Fuel(val date: Long, val odometer: Double, val amount: Double, val full: Boolean, val deleted: Boolean = false)
data class FuelPoint(val distance: Double, val amount: Double, val trusted: Boolean) { val kmPerL get() = distance / amount }
data class FuelStats(
    val points: List<FuelPoint>, val average: Double?, val recent: Double?,
    val best: Double?, val worst: Double?, val trustedCount: Int, val fillCount: Int,
)
fun fuelStats(records: List<Fuel>): FuelStats {
    val sorted = records.filterNot { it.deleted }.sortedWith(compareBy<Fuel> { it.odometer }.thenBy { it.date })
    val points = sorted.zipWithNext().mapNotNull { (a, b) ->
        val distance = b.odometer - a.odometer
        if (!distance.isFinite() || distance <= 0 || !b.amount.isFinite() || b.amount <= 0 || !(distance / b.amount).isFinite()) null
        else FuelPoint(distance, b.amount, a.full && b.full)
    }
    val trusted = points.filter { it.trusted }
    val average = trusted.takeIf { it.isNotEmpty() }?.let { values ->
        val distance = values.sumOf(FuelPoint::distance)
        val amount = values.sumOf(FuelPoint::amount)
        if (distance.isFinite() && amount.isFinite()) (distance / amount).takeIf { it.isFinite() }
        else {
            val exactDistance = values.fold(BigDecimal.ZERO) { sum, point -> sum + BigDecimal.valueOf(point.distance) }
            val exactAmount = values.fold(BigDecimal.ZERO) { sum, point -> sum + BigDecimal.valueOf(point.amount) }
            exactDistance.divide(exactAmount, MathContext.DECIMAL128).toDouble().takeIf { it.isFinite() }
        }
    }
    return FuelStats(points, average,
        (trusted.lastOrNull() ?: points.lastOrNull())?.kmPerL, trusted.maxOfOrNull { it.kmPerL },
        trusted.minOfOrNull { it.kmPerL }, trusted.size, sorted.size)
}
enum class Due { OK, SOON, DUE, OVERDUE }
fun distanceDue(remaining: Double, threshold: Double = 1000.0): Due = when {
    remaining > threshold -> Due.OK
    remaining > 0 -> Due.SOON
    remaining >= -threshold -> Due.DUE
    else -> Due.OVERDUE
}
data class Reading(val date: Long, val km: Double)
fun estimateOdometer(readings: List<Reading>, initial: Double, now: Long): Double {
    if (readings.isEmpty()) return initial
    val base = readings.maxWith(compareBy<Reading> { it.km }.thenBy { it.date })
    val span = (readings.maxOf { it.date }.toDouble() - readings.minOf { it.date }.toDouble()) / 86400000.0
    if (span < 14) return base.km
    val daily = ((readings.maxOf { it.km } - readings.minOf { it.km }) / span).coerceIn(0.0, 300.0)
    val estimate = base.km + daily * ((now.toDouble() - base.date.toDouble()) / 86400000.0).coerceAtLeast(0.0)
    return floor(estimate + 0.5)
}

/** A projection is only shown with enough valid, non-deleted input history. */
fun projectedOdometer(records: List<RecordEntity>, now: Long): Double? {
    val readings = records.filter { it.deletedAt == null && it.odometerKnown && it.odometer.isFinite() && it.odometer >= 0 }
        .map { Reading(it.timestamp, it.odometer) }
    if (readings.size < 2 || readings.maxOf { it.date }.toDouble() - readings.minOf { it.date }.toDouble() < 14 * 86_400_000.0) return null
    return estimateOdometer(readings, 0.0, now).takeIf { it.isFinite() }
}

data class MonthlySpend(val month: String, val total: BigInteger) {
    constructor(month: String, total: Long) : this(month, BigInteger.valueOf(total))
}
data class CategorySpend(val key: String, val label: String, val total: BigInteger)
enum class SpendingPeriod(val label: String, val months: Long?) {
    THIS_MONTH("이번 달", null), THREE_MONTHS("3개월", 3), SIX_MONTHS("6개월", 6),
    TWELVE_MONTHS("12개월", 12), ALL("전체", null)
}

/** Matches the PWA: local month start or the same local time N months ago, clamped at month end. */
fun spendingPeriodStart(period: SpendingPeriod, now: ZonedDateTime): Long? = when (period) {
    SpendingPeriod.ALL -> null
    SpendingPeriod.THIS_MONTH -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
    else -> now.minusMonths(requireNotNull(period.months)).toInstant().toEpochMilli()
}
data class SpendingStats(
    val total: BigInteger,
    val fuel: BigInteger,
    val maintenance: BigInteger,
    val other: BigInteger,
    val byCategory: List<CategorySpend>,
    val distance: Double,
    val costPerKm: Double?,
    val monthly: List<MonthlySpend>,
)

/** Wallet totals include fuel, maintenance, and general expenses without double-counting. */
fun spendingStats(records: List<RecordEntity>, zone: ZoneId = ZoneId.systemDefault(), startMillis: Long? = null): SpendingStats {
    val active = records.filter { it.deletedAt == null && it.cost >= 0 && it.type in setOf("주유", "정비", "지출") && (startMillis == null || it.timestamp >= startMillis) }
    fun List<RecordEntity>.sumCosts(): BigInteger = fold(BigInteger.ZERO) { sum, record -> sum + BigInteger.valueOf(record.cost) }
    val fuel = active.filter { it.type == "주유" }.sumCosts()
    val maintenance = active.filter { it.type == "정비" }.sumCosts()
    val other = active.filter { it.type == "지출" }.sumCosts()
    val generalCategories = active.filter { it.type == "지출" }
        .groupBy { it.category?.takeIf(String::isNotBlank) ?: "기타" }
        .mapValues { (_, values) -> values.sumCosts() }
    val categories = (listOf(CategorySpend("fuel", "주유", fuel), CategorySpend("maintenance", "정비", maintenance)) +
        generalCategories.map { (category, total) -> CategorySpend("expense:$category", category, total) })
        .filter { it.total.signum() > 0 }.sortedByDescending { it.total }
    val odometers = records.asSequence()
        .filter { it.deletedAt == null && it.type in setOf("주유", "정비", "지출", "주행거리") && (startMillis == null || it.timestamp >= startMillis) && it.odometerKnown }
        .map { it.odometer }.filter { it.isFinite() && it >= 0 }.toList()
    val distance = if (odometers.size >= 2) (odometers.max() - odometers.min()).coerceAtLeast(0.0) else 0.0
    val months = active.groupBy {
        DateTimeFormatter.ofPattern("yyyy-MM").withZone(zone).format(Instant.ofEpochMilli(it.timestamp))
    }.map { (month, entries) -> MonthlySpend(month, entries.sumCosts()) }.sortedBy { it.month }
    return SpendingStats(fuel + maintenance + other, fuel, maintenance, other, categories, distance,
        if (distance > 0) ((fuel + maintenance + other).toDouble() / distance).takeIf { it.isFinite() } else null, months)
}

data class Signal(val timestamp: Long, val name: String, val value: Double, val unit: String, val timestampKind: String? = null)
/** Null means unknown, including pre-v9 data. */
fun validTimestampKind(kind: String?): Boolean = kind == null || kind == "MEASURED" || kind == "RECEIVED"
fun timestampKindLabel(kind: String?): String = when (kind) {
    "MEASURED" -> "측정 시각"
    "RECEIVED" -> "수신 시각"
    else -> "시각 종류 미확인"
}
/** JSON Schema string lengths count Unicode code points, not UTF-16 code units. */
fun validSignalText(name: String, unit: String): Boolean = name.isNotBlank() && hasValidUnicodeScalars(name) && hasValidUnicodeScalars(unit) &&
    name.codePointCount(0, name.length) <= 256 && unit.codePointCount(0, unit.length) <= 64
fun maintenanceOdometer(initial: Double, records: List<RecordEntity>, mock: Signal?, useMock: Boolean,
    projection: Double? = null, useProjection: Boolean = false): Double {
    val manual = records.asSequence().filter { it.deletedAt == null && it.odometerKnown && it.odometer.isFinite() && it.odometer >= 0 }
        .maxOfOrNull { it.odometer } ?: 0.0
    val registered = initial.takeIf { it.isFinite() && it >= 0 } ?: 0.0
    val simulated = mock?.takeIf { useMock && it.name == "odometer" && it.unit == "km" && it.value.isFinite() && it.value >= 0 }?.value ?: 0.0
    val estimated = projection?.takeIf { useProjection && it.isFinite() && it >= 0 } ?: 0.0
    return maxOf(registered, manual, simulated, estimated)
}
/** A late sample remains available to event capture but must not replace a newer dashboard reading. */
fun updateLatestSignals(current: Map<String, Signal>, signal: Signal): Map<String, Signal> {
    val previous = current[signal.name]
    return if (previous != null && previous.timestamp > signal.timestamp) current else current + (signal.name to signal)
}
class ConflictingSignalException : IllegalArgumentException("Conflicting signal for the same timestamp and name")
class SignalBufferCapacityException : IllegalArgumentException("Signal buffer capacity exceeded")
const val DEFAULT_SIGNAL_BUFFER_CAPACITY = 100_000

/** Normalized chart position; scaling before subtraction avoids overflow for opposite extremes. */
fun chartFraction(value: Double, min: Double, max: Double): Double {
    require(value.isFinite() && min.isFinite() && max.isFinite() && min <= max)
    if (min == max) return 0.5
    val range = max - min
    val fraction = if (range.isFinite()) (value.coerceIn(min, max) - min) / range
    else (value.coerceIn(min, max) / 2 - min / 2) / (max / 2 - min / 2)
    return fraction.coerceIn(0.0, 1.0)
}
/** At most four extrema/edge points per time bucket; raw samples remain unchanged. */
fun graphSamples(samples: List<SampleEntity>, eventAt: Long, bucketCount: Int = 120): List<SampleEntity> {
    require(bucketCount in 1..1_000)
    if (samples.size <= bucketCount * 4) return samples.sortedBy { it.timestamp }
    data class Bucket(var first: SampleEntity, var last: SampleEntity, var min: SampleEntity, var max: SampleEntity)
    val buckets = arrayOfNulls<Bucket>(bucketCount)
    for (sample in samples) {
        val delta = try { Math.subtractExact(sample.timestamp, eventAt) }
        catch (_: ArithmeticException) { if (sample.timestamp < eventAt) Long.MIN_VALUE else Long.MAX_VALUE }
        val position = delta.coerceIn(-60_000, 60_000) + 60_000
        val index = ((position * bucketCount) / 120_001).toInt()
        val bucket = buckets[index]
        if (bucket == null) buckets[index] = Bucket(sample, sample, sample, sample)
        else {
            if (sample.timestamp < bucket.first.timestamp) bucket.first = sample
            if (sample.timestamp > bucket.last.timestamp) bucket.last = sample
            if (sample.value < bucket.min.value) bucket.min = sample
            if (sample.value > bucket.max.value) bucket.max = sample
        }
    }
    return buckets.filterNotNull().flatMap { listOf(it.first, it.min, it.max, it.last).distinct().sortedBy { item -> item.timestamp } }
}

fun eventWindow(at: Long): LongRange = (at - 60_000)..(at + 60_000)
/** Uses sample timestamps, keeps the inclusive 60-second boundary, and accepts out-of-order signals. */
class RingBufferManager(private val windowMs: Long = 60_000, private val maxSamples: Int = DEFAULT_SIGNAL_BUFFER_CAPACITY) {
    init { require(windowMs >= 0 && maxSamples > 0) }
    private val samples = TreeMap<Long, LinkedHashMap<String, Signal>>()
    private var sampleCount = 0
    private fun cutoff(timestamp: Long): Long =
        if (timestamp < Long.MIN_VALUE + windowMs) Long.MIN_VALUE else timestamp - windowMs

    @Synchronized fun add(signal: Signal) {
        val existing = samples[signal.timestamp]?.get(signal.name)
        if (existing != null) {
            if (existing.value != signal.value || existing.unit != signal.unit || existing.timestampKind != signal.timestampKind) throw ConflictingSignalException()
            return
        }
        val newest = if (samples.isEmpty()) signal.timestamp else maxOf(samples.lastKey(), signal.timestamp)
        val earliest = cutoff(newest)
        if (signal.timestamp < earliest) return
        val expired = samples.headMap(earliest, false)
        sampleCount -= expired.values.sumOf { it.size }
        expired.clear()
        if (sampleCount >= maxSamples) throw SignalBufferCapacityException()
        samples.getOrPut(signal.timestamp) { linkedMapOf() }[signal.name] = signal
        sampleCount++
    }
    @Synchronized fun snapshot(at: Long): List<Signal> = snapshotRange(cutoff(at), at)
    @Synchronized fun snapshotRange(from: Long, through: Long): List<Signal> =
        if (from > through) emptyList() else samples.subMap(from, true, through, true).values.flatMap { it.values }
    @Synchronized fun oldestTimestamp(from: Long, through: Long): Long? =
        if (from > through) null else samples.ceilingKey(from)?.takeIf { it <= through }
    @Synchronized fun clear() { samples.clear(); sampleCount = 0 }
}
