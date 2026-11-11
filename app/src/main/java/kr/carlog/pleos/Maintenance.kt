package kr.carlog.pleos

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

/** Optional fields mirror the independent distance, calendar, and engine-hour intervals in Carlog. */
data class MaintenanceRule(
    val intervalDistance: Double? = null,
    val lastDoneOdometer: Double? = null,
    val intervalMonths: Long? = null,
    val lastDoneAt: Long? = null,
    val intervalEngineHours: Double? = null,
    val remindBeforeDistance: Double = 1000.0,
    val remindBeforeDays: Double = 14.0,
)

data class MaintenanceDue(val status: Due, val remaining: Double?, val unit: String?)

data class ScheduledDue(val schedule: ScheduleEntity, val due: MaintenanceDue, val missingEngineHours: Boolean = false, val invalidCalendarInterval: Boolean = false,
    val invalidConfiguration: Boolean = false)

/** Preserve imported legacy values, but do not present an incomplete rule as fully checked. */
fun invalidScheduleConfiguration(schedule: ScheduleEntity): Boolean =
    schedule.label.isBlank() || !schedule.lastKm.isFinite() || schedule.lastKm < 0 ||
        !schedule.intervalKm.isFinite() || schedule.intervalKm < 0 ||
        (schedule.intervalKm > 0 && !(schedule.lastKm + schedule.intervalKm).isFinite()) ||
        (schedule.intervalMonths != null && (schedule.intervalMonths <= 0 || schedule.lastDoneAt == null)) ||
        (schedule.intervalEngineHours != null && (!schedule.intervalEngineHours.isFinite() || schedule.intervalEngineHours <= 0 ||
            schedule.category != "engine_oil" || schedule.lastDoneAt == null)) ||
        (schedule.intervalKm == 0.0 && schedule.intervalMonths == null && schedule.intervalEngineHours == null)

fun scheduledMaintenanceDue(schedule: ScheduleEntity, currentOdometer: Double, now: Long, fuels: List<EngineHoursEntry>, oilChanges: List<OilChange>, zone: ZoneId = ZoneId.systemDefault()): ScheduledDue {
    val hours = if (schedule.category == "engine_oil") engineHoursSinceScheduleReplacement(fuels, oilChanges, schedule.lastDoneAt) else null
    val rule = MaintenanceRule(intervalDistance = schedule.intervalKm, lastDoneOdometer = schedule.lastKm,
        intervalMonths = schedule.intervalMonths, lastDoneAt = schedule.lastDoneAt, intervalEngineHours = schedule.intervalEngineHours)
    return ScheduledDue(schedule, maintenanceDue(rule, currentOdometer, now, hours, zone),
        missingEngineHours = schedule.intervalEngineHours?.let { it > 0 } == true && hours == null,
        invalidCalendarInterval = schedule.intervalMonths?.let { it > 0 } == true && schedule.lastDoneAt != null &&
            calendarDueAt(schedule.lastDoneAt, schedule.intervalMonths, zone) == null,
        invalidConfiguration = invalidScheduleConfiguration(schedule))
}

/** Urgency first; sort comparable remaining amounts without comparing unlike units. */
fun prioritizeMaintenance(items: List<ScheduledDue>): List<ScheduledDue> {
    val ordered = items.sortedByDescending { it.due.status.ordinal }.toMutableList()
    val slots = ordered.indices.filter { ordered[it].due.remaining != null && ordered[it].due.unit != null }
        .groupBy { ordered[it].due.status to ordered[it].due.unit }
    for (indices in slots.values) {
        val sorted = indices.map { ordered[it] }.sortedBy { it.due.remaining }
        indices.forEachIndexed { offset, index -> ordered[index] = sorted[offset] }
    }
    return ordered
}

fun maintenanceDueLabel(due: MaintenanceDue): String {
    val remaining = due.remaining ?: return "주기 미설정 또는 가동시간 기록 필요"
    val amount = "%.0f".format(kotlin.math.abs(remaining))
    val unit = due.unit ?: ""
    return when {
        remaining > 0 -> "$amount $unit 남음"
        remaining < 0 -> "$amount $unit 초과"
        else -> if (unit == "일") "오늘" else "지금 점검"
    }
}

fun dueStatusLabel(status: Due): String = when (status) {
    Due.OK -> "여유"
    Due.SOON -> "임박"
    Due.DUE -> "도래"
    Due.OVERDUE -> "지남"
}

data class EngineHoursEntry(val timestamp: Long, val hours: Double?, val incremental: Boolean = false, val deleted: Boolean = false)
data class OilChange(val timestamp: Long, val odometer: Double, val deleted: Boolean = false)

/** A cumulative reading replaces the total; an incremental reading adds to it, as in the PWA. */
fun engineHoursSinceOilChange(fuels: List<EngineHoursEntry>, changes: List<OilChange>, vehicleCreatedAt: Long): Double {
    val baseline = changes.filterNot { it.deleted }.maxByOrNull { it.odometer }?.timestamp ?: vehicleCreatedAt
    var total = 0.0
    fuels.filter { !it.deleted && it.hours != null && it.timestamp >= baseline }
        .sortedBy { it.timestamp }
        .forEach { reading -> total = if (reading.incremental) total + reading.hours!! else reading.hours!! }
    return total.coerceAtLeast(0.0)
}

/** A schedule's explicitly recorded replacement may be newer than the last oil maintenance record. */
fun engineHoursSinceScheduleReplacement(fuels: List<EngineHoursEntry>, changes: List<OilChange>, scheduleLastDoneAt: Long?): Double? {
    val baseline = scheduleLastDoneAt ?: 0L
    val eligibleChanges = changes.filter { !it.deleted && it.timestamp >= baseline }
    val effectiveBaseline = eligibleChanges.maxByOrNull { it.odometer }?.timestamp ?: baseline
    if (fuels.none { !it.deleted && it.hours != null && it.timestamp >= effectiveBaseline }) return null
    return engineHoursSinceOilChange(fuels, eligibleChanges, baseline).takeIf { it.isFinite() }
}

/** Invalid external or legacy dates must not crash the dashboard. */
private fun calendarDueAt(lastDoneAt: Long, months: Long, zone: ZoneId): Long? = try {
    Instant.ofEpochMilli(lastDoneAt).atZone(zone).plusMonths(months).toInstant().toEpochMilli()
} catch (_: java.time.DateTimeException) { null }
  catch (_: ArithmeticException) { null }

/** Returns the most urgent configured interval; ties retain distance, date, then hours order. */
fun maintenanceDue(rule: MaintenanceRule, currentOdometer: Double, now: Long, engineHours: Double? = null, zone: ZoneId = ZoneId.systemDefault()): MaintenanceDue {
    val candidates = mutableListOf<MaintenanceDue>()
    if (rule.intervalDistance != null && rule.intervalDistance.isFinite() && rule.intervalDistance > 0 &&
        rule.lastDoneOdometer != null && rule.lastDoneOdometer.isFinite() && currentOdometer.isFinite()) {
        val remaining = rule.lastDoneOdometer + rule.intervalDistance - currentOdometer
        if (remaining.isFinite()) candidates += MaintenanceDue(distanceDue(remaining, rule.remindBeforeDistance), remaining, "km")
    }
    if (rule.intervalMonths != null && rule.intervalMonths > 0 && rule.lastDoneAt != null) {
        calendarDueAt(rule.lastDoneAt, rule.intervalMonths, zone)?.let { dueAt ->
            val remaining = ((dueAt.toDouble() - now.toDouble()) / 86_400_000.0).roundToLong().toDouble()
            candidates += MaintenanceDue(distanceDue(remaining, rule.remindBeforeDays), remaining, "일")
        }
    }
    if (rule.intervalEngineHours != null && rule.intervalEngineHours > 0 && engineHours != null) {
        val remaining = rule.intervalEngineHours - engineHours
        candidates += MaintenanceDue(distanceDue(remaining, maxOf(5.0, rule.intervalEngineHours * 0.1)), remaining, "시간")
    }
    return candidates.maxByOrNull { it.status.ordinal } ?: MaintenanceDue(Due.OK, null, null)
}
