package kr.carlog.pleos

import org.json.JSONArray
import org.json.JSONObject

data class PwaVehiclePreview(val id: String, val name: String, val deleted: Boolean, val fuels: Int, val maintenance: Int, val expenses: Int)
data class PwaBackupPreview(
    val exportedAt: String?,
    val vehicles: List<PwaVehiclePreview>,
    val totals: Map<String, Int>,
    val deletedRecords: Int,
    val invalidDeletionStatuses: Int,
    val invalidIds: Int,
    val duplicateIds: Int,
    val unknownVehicleRefs: Int,
    val deletedVehicleRefs: Int,
    val ambiguousVehicleRefs: Int,
    val brokenReceiptReferences: Int,
    val brokenRecordReceiptReferences: Int,
    val unknownExpenseCategories: Int,
    val multiItemMaintenance: Int,
    val invalidMaintenanceItems: Int,
    val manualOdometerReadings: Int,
    val derivedOdometerReadings: Int,
    val unknownOdometerSources: Int,
    val brokenOdometerReferences: Int,
    val duplicateDerivedOdometerReadings: Int,
    val missingTables: List<String>,
)

/** Read-only inspection of a Carlog v1 backup. No app database writes are performed. */
fun previewPwaBackup(text: String): PwaBackupPreview {
    requireUnambiguousJson(text, setOf(1))
    val root = JSONObject(text)
    require(root.opt("app") == "carlog") { "Not a Carlog backup" }
    require((root.opt("schemaVersion") as? Number)?.toDouble() == 1.0) { "Unsupported backup version" }
    val data = root.opt("data") as? JSONObject ?: throw IllegalArgumentException("Missing data")
    val names = listOf("vehicles", "odometerReadings", "fuelLogs", "maintenanceLogs", "expenseLogs", "maintenanceSchedules", "receiptImages", "customCategories")
    val tables = names.associateWith { name ->
        if (!data.has(name)) JSONArray() else data.opt(name) as? JSONArray ?: throw IllegalArgumentException("Invalid $name")
    }
    val recordsByTable = tables.mapValues { (name, array) ->
        (0 until array.length()).map { array.opt(it) as? JSONObject ?: throw IllegalArgumentException("Invalid $name record") }
    }
    fun items(name: String): List<JSONObject> = recordsByTable.getValue(name)
    fun JSONObject.string(key: String): String? = opt(key) as? String
    fun JSONObject.deleted(): Boolean = !isNull("deletedAt") && string("deletedAt")?.isNotBlank() == true
    fun JSONObject.invalidDeletionStatus(): Boolean = has("deletedAt") && !isNull("deletedAt") &&
        (string("deletedAt")?.isNotBlank() != true)
    val vehicles = items("vehicles")
    val vehicleIds = vehicles.mapNotNull { it.string("id") }.toSet()
    val vehiclesById = vehicles.mapNotNull { vehicle -> vehicle.string("id")?.takeIf { it.isNotBlank() }?.let { it to vehicle } }
        .groupBy({ it.first }, { it.second })
    val fuels = items("fuelLogs")
    val maint = items("maintenanceLogs")
    fun validMaintenanceItems(record: JSONObject): JSONArray? = (record.opt("items") as? JSONArray)?.takeIf { array ->
        array.length() > 0 && (0 until array.length()).all { array.opt(it) is JSONObject }
    }
    val expenses = items("expenseLogs")
    val odometers = items("odometerReadings").filterNot { it.deleted() }
    val sourceRecords = mapOf("fuel" to fuels, "maintenance" to maint, "expense" to expenses)
    val sourcesById = sourceRecords.mapValues { (_, records) -> records.asSequence()
        .filterNot { it.deleted() }.mapNotNull { record -> record.string("id")?.takeIf { it.isNotBlank() }?.let { it to record } }
        .groupBy({ it.first }, { it.second }) }
    fun hasUniqueSource(type: String?, id: String?, vehicle: String?): Boolean {
        if (type == null || id.isNullOrBlank() || vehicle.isNullOrBlank()) return false
        val source = sourcesById[type]?.get(id)?.singleOrNull() ?: return false
        return source.string("vehicleId") == vehicle
    }
    val manualOdometers = odometers.count { it.string("source") == "manual" }
    val derivedOdometers = odometers.count { it.string("source") in sourceRecords.keys }
    val unknownOdometers = odometers.size - manualOdometers - derivedOdometers
    val brokenOdometers = odometers.count { reading ->
        val type = reading.string("source")
        if (type !in sourceRecords.keys) return@count false
        val vehicle = reading.string("vehicleId")
        val ref = reading.string("refId")
        !hasUniqueSource(type, ref, vehicle)
    }
    val duplicateDerivedOdometers = odometers.mapNotNull { reading ->
        val type = reading.string("source")
        val ref = reading.string("refId")
        val vehicle = reading.string("vehicleId")
        if (hasUniqueSource(type, ref, vehicle)) Triple(type, ref, vehicle) else null
    }.groupingBy { it }.eachCount().values.sumOf { it - 1 }
    val recordTables = listOf("odometerReadings", "fuelLogs", "maintenanceLogs", "expenseLogs", "maintenanceSchedules", "receiptImages")
    val referenced = recordTables.flatMap { items(it) }
    val receipts = items("receiptImages")
    val receiptsById = receipts.mapNotNull { receipt -> receipt.string("id")?.takeIf { it.isNotBlank() }?.let { it to receipt } }
        .groupBy({ it.first }, { it.second })
    val brokenReceipts = receipts.count { receipt ->
        if (receipt.deleted()) return@count false
        val type = receipt.string("refType")
        val vehicle = receipt.string("vehicleId")
        val ref = receipt.string("refId")
        !hasUniqueSource(type, ref, vehicle)
    }
    val brokenRecordReceipts = sourceRecords.entries.sumOf { (type, records) -> records.count { record ->
        if (record.deleted() || !record.has("receiptImageId") || record.isNull("receiptImageId")) return@count false
        val receiptId = record.string("receiptImageId")
        val candidates = receiptsById[receiptId]
        val receipt = candidates?.singleOrNull()
        receipt == null || receipt.deleted() || !hasUniqueSource(type, record.string("id"), record.string("vehicleId")) ||
            receipt.string("vehicleId") != record.string("vehicleId") || receipt.string("refType") != type || receipt.string("refId") != record.string("id")
    } }
    var invalidIds = 0
    var duplicateIds = 0
    names.forEach { name ->
        val ids = mutableSetOf<String>()
        items(name).forEach { item ->
            val id = item.string("id")
            if (id.isNullOrBlank()) invalidIds++ else if (!ids.add(id)) duplicateIds++
        }
    }
    fun countsByVehicle(records: List<JSONObject>): Map<String, Int> = records.asSequence()
        .filterNot { it.deleted() }.mapNotNull { it.string("vehicleId") }.groupingBy { it }.eachCount()
    val fuelCounts = countsByVehicle(fuels)
    val maintenanceCounts = countsByVehicle(maint)
    val expenseCounts = countsByVehicle(expenses)
    val vehiclePreviews = vehicles.map { vehicle ->
        val id = vehicle.string("id") ?: ""
        PwaVehiclePreview(id, vehicle.string("nickname") ?: "이름 없음", vehicle.deleted(),
            fuelCounts[id] ?: 0, maintenanceCounts[id] ?: 0, expenseCounts[id] ?: 0)
    }
    return PwaBackupPreview(root.opt("exportedAt") as? String, vehiclePreviews,
        names.associateWith { tables.getValue(it).length() }, referenced.count { it.deleted() },
        names.sumOf { name -> items(name).count { it.invalidDeletionStatus() } }, invalidIds, duplicateIds,
        referenced.count { it.string("vehicleId").isNullOrBlank() || it.string("vehicleId") !in vehicleIds },
        referenced.count { !it.deleted() && vehiclesById[it.string("vehicleId")]?.let { matches -> matches.size == 1 && matches.single().deleted() } == true },
        referenced.count { !it.deleted() && (vehiclesById[it.string("vehicleId")]?.size ?: 0) > 1 },
        brokenReceipts,
        brokenRecordReceipts,
        expenses.count { !it.deleted() && it.string("category")?.let(::appExpenseCategory) == null },
        maint.count { !it.deleted() && (validMaintenanceItems(it)?.length() ?: 0) > 1 },
        maint.count { !it.deleted() && validMaintenanceItems(it) == null },
        manualOdometers, derivedOdometers, unknownOdometers, brokenOdometers, duplicateDerivedOdometers, names.filterNot(data::has))
}
