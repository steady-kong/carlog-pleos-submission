package kr.carlog.pleos

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/** Validates the complete exchange snapshot without writing to Room. */
fun parseVehicleManagementJson(text: String): VehicleManagementExportData {
    require(text.toByteArray(Charsets.UTF_8).size <= VEHICLE_MANAGEMENT_MAX_BYTES)
    requireUnambiguousJson(text, setOf(1))
    val root = JSONObject(text)
    fun JSONObject.keysExactly(vararg allowed: String) {
        val expected = allowed.toSet()
        require(expected.all(::has) && keys().asSequence().all { it in expected }) { "Unexpected or missing field" }
    }
    fun JSONObject.string(key: String): String = get(key) as? String ?: throw IllegalArgumentException("Invalid $key")
    fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else string(key)
    fun JSONObject.double(key: String): Double = (get(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        ?: throw IllegalArgumentException("Invalid $key")
    fun JSONObject.optionalDouble(key: String): Double? = if (isNull(key)) null else double(key)
    fun JSONObject.safeLong(key: String): Long {
        val number = get(key)
        // Only integer tokens are accepted: parsing decimal tokens as Double can silently round money.
        val value = when (number) {
            is Int -> number.toLong()
            is Long -> number
            else -> throw IllegalArgumentException("Invalid integer $key")
        }
        require(value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) { "Invalid $key" }
        return value
    }
    fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else safeLong(key)
    fun id(value: String): String {
        require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
        return UUID.fromString(value).toString()
    }
    fun time(value: String, location: String): Long = parseExchangeTimestamp(value, location)
    fun JSONObject.optionalTime(key: String, location: String): Long? = optionalString(key)?.let { time(it, location) }
    root.keysExactly("format", "schemaVersion", "exportedAt", "vehicle", "records", "schedules")
    require(root.string("format") == "carlog-vehicle-records" && root.double("schemaVersion") == 1.0)
    time(root.string("exportedAt"), "파일 생성 시각")
    val v = root.get("vehicle") as? JSONObject ?: throw IllegalArgumentException("Invalid vehicle")
    v.keysExactly("id", "name", "initialOdometerKm", "maker", "model", "year", "fuelType")
    val year = v.optionalLong("year")?.also { require(it in 1886L..2100L) }?.toInt()
    val vehicle = VehicleEntity(id(v.string("id")), v.string("name"), v.double("initialOdometerKm"), v.optionalString("maker"), v.optionalString("model"), year, v.optionalString("fuelType"))
    require(validVehicleInfo(vehicle.name, vehicle.initialKm, vehicle.year, vehicle.fuelType))
    val recordsJson = root.get("records") as? JSONArray ?: throw IllegalArgumentException("Invalid records")
    val records = (0 until recordsJson.length()).map { index ->
        val item = recordsJson.get(index) as? JSONObject ?: throw IllegalArgumentException("Invalid record")
        item.keysExactly("id", "type", "occurredAt", "odometerKm", "amountLiters", "costKrw", "label", "fullTank", "category", "expenseCategoryCode", "engineHours", "engineHoursMode", "updatedAt", "relatedEventId", "repairOutcome")
        val type = when (item.string("type")) { "fuel" -> "주유"; "expense" -> "지출"; "maintenance" -> "정비"; "odometer" -> "주행거리"; else -> throw IllegalArgumentException("Invalid record type") }
        val km = item.optionalDouble("odometerKm")
        val amount = item.optionalDouble("amountLiters")
        val full = if (item.isNull("fullTank")) null else item.get("fullTank") as? Boolean ?: throw IllegalArgumentException("Invalid fullTank")
        require(if (type == "주유") amount != null && full != null else amount == null && full == null)
        val category = item.optionalString("category")
        val code = item.optionalString("expenseCategoryCode")
        require(code == if (type == "지출") category?.let(::pwaExpenseCode) else null) { "Expense category mismatch" }
        val hours = item.optionalDouble("engineHours")
        val mode = item.optionalString("engineHoursMode")
        val eventId = item.optionalString("relatedEventId")?.let(::id)
        val outcome = item.optionalString("repairOutcome")
        val cost = item.safeLong("costKrw")
        val label = item.string("label")
        require(validRecordInput(type, label, km, amount ?: 0.0, cost, hours, mode, eventId, outcome))
        RecordEntity(id = id(item.string("id")), vehicleId = vehicle.id, type = type, timestamp = time(item.string("occurredAt"), "차량 기록 ${index + 1}번째 시각"),
            odometer = km ?: 0.0, odometerKnown = km != null, amount = amount ?: 0.0, cost = cost, label = label, full = full ?: false,
            category = category, engineHours = hours, engineHoursMode = mode, updatedAt = item.optionalTime("updatedAt", "차량 기록 ${index + 1}번째 수정 시각"), relatedEventId = eventId, repairOutcome = outcome)
    }
    require(records.map { it.id }.toSet().size == records.size) { "Duplicate record ID" }
    val schedulesJson = root.get("schedules") as? JSONArray ?: throw IllegalArgumentException("Invalid schedules")
    val schedules = (0 until schedulesJson.length()).map { index ->
        val item = schedulesJson.get(index) as? JSONObject ?: throw IllegalArgumentException("Invalid schedule")
        item.keysExactly("id", "label", "lastOdometerKm", "intervalKm", "intervalMonths", "lastDoneAt", "intervalEngineHours", "category", "updatedAt")
        ScheduleEntity(id = id(item.string("id")), vehicleId = vehicle.id, label = item.string("label"), lastKm = item.double("lastOdometerKm"),
            intervalKm = item.double("intervalKm"), intervalMonths = item.optionalLong("intervalMonths"), lastDoneAt = item.optionalTime("lastDoneAt", "소모품 주기 ${index + 1}번째 교체 시각"),
            intervalEngineHours = item.optionalDouble("intervalEngineHours"), category = item.optionalString("category"), updatedAt = item.optionalTime("updatedAt", "소모품 주기 ${index + 1}번째 수정 시각"))
    }
    require(schedules.map { it.id }.toSet().size == schedules.size) { "Duplicate schedule ID" }
    return VehicleManagementExportData(vehicle, records, schedules)
}
