package kr.carlog.pleos

import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Distances are km, fuel is L, money is KRW; timestamps are UTC epoch milliseconds. */
data class Fuel(val date: Long, val odometer: Double, val amount: Double, val full: Boolean, val deleted: Boolean = false)
data class FuelPoint(val distance: Double, val amount: Double, val trusted: Boolean) { val kmPerL get() = distance / amount }
data class FuelStats(val points: List<FuelPoint>, val average: Double?, val recent: Double?)
fun fuelStats(records: List<Fuel>): FuelStats {
    val sorted = records.filterNot { it.deleted }.sortedWith(compareBy<Fuel> { it.odometer }.thenBy { it.date })
    val points = sorted.zipWithNext().mapNotNull { (a, b) ->
        val distance = b.odometer - a.odometer
        if (distance <= 0 || b.amount <= 0) null else FuelPoint(distance, b.amount, a.full && b.full)
    }
    val trusted = points.filter { it.trusted }
    return FuelStats(points, trusted.takeIf { it.isNotEmpty() }?.let { it.sumOf(FuelPoint::distance) / it.sumOf(FuelPoint::amount) }, (trusted.lastOrNull() ?: points.lastOrNull())?.kmPerL)
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
    val span = (readings.maxOf { it.date } - readings.minOf { it.date }) / 86400000.0
    if (span < 14) return base.km
    val daily = ((readings.maxOf { it.km } - readings.minOf { it.km }) / span).coerceIn(0.0, 300.0)
    return (base.km + daily * ((now - base.date) / 86400000.0).coerceAtLeast(0.0)).roundToInt().toDouble()
}

data class MonthlySpend(val month: String, val total: Long)
data class SpendingStats(
    val total: Long,
    val fuel: Long,
    val maintenance: Long,
    val other: Long,
    val byCategory: Map<String, Long>,
    val distance: Double,
    val costPerKm: Double?,
    val monthly: List<MonthlySpend>,
)

/** Wallet totals include fuel, maintenance, and general expenses without double-counting. */
fun spendingStats(records: List<RecordEntity>): SpendingStats {
    val active = records.filter { it.cost >= 0 }
    val fuel = active.filter { it.type == "주유" }.sumOf { it.cost }
    val maintenance = active.filter { it.type == "정비" }.sumOf { it.cost }
    val other = active.filter { it.type == "지출" }.sumOf { it.cost }
    val categories = active.filter { it.type == "지출" }
        .groupBy { it.category?.takeIf(String::isNotBlank) ?: "기타" }
        .mapValues { (_, values) -> values.sumOf { it.cost } }
        .toSortedMap(compareByDescending<String> { categoriesOrder(active, it) }.thenBy { it })
    val odometers = active.map { it.odometer }.filter { it.isFinite() }
    val distance = if (odometers.size >= 2) (odometers.max() - odometers.min()).coerceAtLeast(0.0) else 0.0
    val months = active.groupBy {
        DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(it.timestamp))
    }.map { (month, entries) -> MonthlySpend(month, entries.sumOf { it.cost }) }.sortedBy { it.month }
    return SpendingStats(fuel + maintenance + other, fuel, maintenance, other, categories, distance,
        if (distance > 0) (fuel + maintenance + other).toDouble() / distance else null, months)
}

private fun categoriesOrder(records: List<RecordEntity>, category: String): Long =
    records.asSequence().filter { it.type == "지출" && (it.category?.takeIf(String::isNotBlank) ?: "기타") == category }.sumOf { it.cost }

data class Signal(val timestamp: Long, val name: String, val value: Double, val unit: String)
fun eventWindow(at: Long): LongRange = (at - 60_000)..(at + 60_000)
/** Uses sample timestamps, keeps the inclusive 60-second boundary, and accepts out-of-order signals. */
class RingBufferManager(private val windowMs: Long = 60_000) {
    private val samples = mutableListOf<Signal>()
    @Synchronized fun add(signal: Signal) {
        samples.add(signal)
        val newest = samples.maxOf { it.timestamp }
        samples.removeAll { it.timestamp < newest - windowMs }
    }
    @Synchronized fun snapshot(at: Long): List<Signal> = samples.filter { it.timestamp in (at - windowMs)..at }.sortedBy { it.timestamp }.toList()
}
