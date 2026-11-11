package kr.carlog.pleos

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

const val VEHICLE_MANAGEMENT_MAX_BYTES = 5 * 1024 * 1024
private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L

/** Active vehicle-management records only; this format is not a PWA backup or a sync protocol. */
fun exportVehicleManagementJson(snapshot: VehicleManagementExportData, exportedAt: Long): String {
    fun id(value: String): String {
        require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
        return UUID.fromString(value).toString()
    }
    fun nullable(value: Any?): Any = value ?: JSONObject.NULL
    fun time(value: Long?): Any = value?.let {
        val instant = Instant.ofEpochMilli(it)
        require(instant.atOffset(ZoneOffset.UTC).year in 1..9999) { "Timestamp outside exchange range" }
        instant.toString()
    } ?: JSONObject.NULL
    fun finite(value: Double?): Any = value?.let { require(it.isFinite()); it } ?: JSONObject.NULL
    val car = snapshot.vehicle
    require(validVehicleInfo(car.name, car.initialKm, car.year, car.fuelType))
    val carId = id(car.id)
    val vehicle = JSONObject().put("id", carId).put("name", car.name).put("initialOdometerKm", car.initialKm)
        .put("maker", nullable(car.maker)).put("model", nullable(car.model)).put("year", nullable(car.year)).put("fuelType", nullable(car.fuelType))
    val records = JSONArray()
    snapshot.records.sortedWith(compareBy<RecordEntity> { it.timestamp }.thenBy { it.id }).forEach { record ->
        require(record.vehicleId == car.id && record.deletedAt == null && record.cost <= MAX_SAFE_JSON_INTEGER)
        require(validRecordInput(record.type, record.label, record.odometer.takeIf { record.odometerKnown }, record.amount,
            record.cost, record.engineHours, record.engineHoursMode, record.relatedEventId, record.repairOutcome))
        val type = when (record.type) { "주유" -> "fuel"; "지출" -> "expense"; "정비" -> "maintenance"; "주행거리" -> "odometer"; else -> error("Invalid record type") }
        records.put(JSONObject().put("id", id(record.id)).put("type", type).put("occurredAt", time(record.timestamp))
            .put("odometerKm", finite(record.odometer.takeIf { record.odometerKnown })).put("amountLiters", finite(record.amount.takeIf { type == "fuel" }))
            .put("costKrw", record.cost).put("label", record.label).put("fullTank", nullable(record.full.takeIf { type == "fuel" }))
            .put("category", nullable(record.category)).put("expenseCategoryCode", nullable(if (type == "expense") record.category?.let(::pwaExpenseCode) else null))
            .put("engineHours", finite(record.engineHours)).put("engineHoursMode", nullable(record.engineHoursMode))
            .put("updatedAt", time(record.updatedAt)).put("relatedEventId", nullable(record.relatedEventId?.let(::id)))
            .put("repairOutcome", nullable(record.repairOutcome)))
    }
    val schedules = JSONArray()
    snapshot.schedules.sortedBy { it.id }.forEach { schedule ->
        require(schedule.vehicleId == car.id && schedule.deletedAt == null)
        require(schedule.lastKm.isFinite() && schedule.intervalKm.isFinite())
        require(schedule.intervalMonths == null || schedule.intervalMonths in -MAX_SAFE_JSON_INTEGER..MAX_SAFE_JSON_INTEGER)
        schedules.put(JSONObject().put("id", id(schedule.id)).put("label", schedule.label)
            .put("lastOdometerKm", schedule.lastKm).put("intervalKm", schedule.intervalKm)
            .put("intervalMonths", nullable(schedule.intervalMonths)).put("lastDoneAt", time(schedule.lastDoneAt))
            .put("intervalEngineHours", finite(schedule.intervalEngineHours)).put("category", nullable(schedule.category))
            .put("updatedAt", time(schedule.updatedAt)))
    }
    val json = JSONObject().put("format", "carlog-vehicle-records").put("schemaVersion", 1)
        .put("exportedAt", time(exportedAt)).put("vehicle", vehicle).put("records", records).put("schedules", schedules).toString(2)
    require(json.toByteArray(Charsets.UTF_8).size <= VEHICLE_MANAGEMENT_MAX_BYTES) { "Vehicle export is too large" }
    // A snapshot can contain distinct database IDs that normalize to the same
    // exchange UUID. Never write a file that this app would reject on import.
    parseVehicleManagementJson(json)
    return json
}
