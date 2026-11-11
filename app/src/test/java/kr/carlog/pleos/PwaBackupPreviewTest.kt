package kr.carlog.pleos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PwaBackupPreviewTest {
    @Test fun malformedDeletionMarkersAreReportedWithoutChangingBackup() {
        val value = backup()
        val fuels = value.getJSONObject("data").getJSONArray("fuelLogs")
        fuels.put(JSONObject().put("id", "numeric").put("vehicleId", "car-1").put("deletedAt", 123))
        fuels.put(JSONObject().put("id", "blank").put("vehicleId", "car-1").put("deletedAt", " "))
        fuels.put(JSONObject().put("id", "null").put("vehicleId", "car-1").put("deletedAt", JSONObject.NULL))
        val original = value.toString()
        assertEquals(2, previewPwaBackup(original).invalidDeletionStatuses)
        assertEquals(original, value.toString())
    }

    @Test fun multiItemMaintenanceIsDistinguishedFromMalformedOrDeletedRecords() {
        fun record(id: String, items: Any?, deleted: Boolean = false) = JSONObject().put("id", id).put("vehicleId", "car-1")
            .apply { if (items != null) put("items", items); if (deleted) put("deletedAt", "2026-01-01T00:00:00Z") }
        val data = backup().getJSONObject("data")
        data.put("maintenanceLogs", JSONArray()
            .put(record("multi", JSONArray().put(JSONObject()).put(JSONObject())))
            .put(record("single", JSONArray().put(JSONObject())))
            .put(record("empty", JSONArray()))
            .put(record("bad", JSONArray().put("not an object")))
            .put(record("missing", null))
            .put(record("deleted", JSONArray().put(JSONObject()).put(JSONObject()), true)))
        val value = JSONObject().put("app", "carlog").put("schemaVersion", 1).put("data", data)
        val preview = previewPwaBackup(value.toString())
        assertEquals(1, preview.multiItemMaintenance)
        assertEquals(3, preview.invalidMaintenanceItems)
    }

    private fun backup(): JSONObject {
        val vehicles = JSONArray().put(JSONObject().put("id", "car-1").put("nickname", "차량"))
        val fuels = JSONArray().put(JSONObject().put("id", "fuel-1").put("vehicleId", "car-1"))
            .put(JSONObject().put("id", "fuel-1").put("vehicleId", "car-1").put("deletedAt", "2026-01-01T00:00:00Z"))
        val expenses = JSONArray().put(JSONObject().put("id", "expense-1").put("vehicleId", "missing"))
        return JSONObject().put("app", "carlog").put("schemaVersion", 1).put("exportedAt", "2026-01-01T00:00:00Z")
            .put("data", JSONObject().put("vehicles", vehicles).put("fuelLogs", fuels).put("expenseLogs", expenses))
    }

    @Test fun previewCountsActiveRecordsAndReportsConflictsWithoutImporting() {
        val value = previewPwaBackup(backup().toString())
        assertEquals(1, value.vehicles.single().fuels)
        assertEquals(2, value.totals["fuelLogs"])
        assertEquals(1, value.deletedRecords)
        assertEquals(1, value.duplicateIds)
        assertEquals(1, value.unknownVehicleRefs)
    }

    @Test fun distinguishesMissingTablesFromPresentEmptyTables() {
        val value = backup()
        value.getJSONObject("data").put("maintenanceLogs", JSONArray())
        val preview = previewPwaBackup(value.toString())
        assertTrue("missing table must be visible", "odometerReadings" in preview.missingTables)
        assertFalse("empty but present table is not missing", "maintenanceLogs" in preview.missingTables)
        assertFalse("present table is not missing", "vehicles" in preview.missingTables)
        assertEquals(0, preview.totals["maintenanceLogs"])
    }

    @Test fun activeRecordsDistinguishDeletedAndAmbiguousVehicleReferences() {
        val value = backup()
        val data = value.getJSONObject("data")
        data.getJSONArray("vehicles")
            .put(JSONObject().put("id", "deleted-car").put("deletedAt", "2026-01-01T00:00:00Z"))
            .put(JSONObject().put("id", "duplicate-car"))
            .put(JSONObject().put("id", "duplicate-car").put("deletedAt", "2026-01-01T00:00:00Z"))
        data.getJSONArray("fuelLogs")
            .put(JSONObject().put("id", "deleted-ref").put("vehicleId", "deleted-car"))
            .put(JSONObject().put("id", "old-ref").put("vehicleId", "deleted-car").put("deletedAt", "2026-01-01T00:00:00Z"))
            .put(JSONObject().put("id", "ambiguous-ref").put("vehicleId", "duplicate-car"))
        val preview = previewPwaBackup(value.toString())
        assertEquals(1, preview.deletedVehicleRefs)
        assertEquals(1, preview.ambiguousVehicleRefs)
        assertEquals(1, preview.unknownVehicleRefs)
    }

    @Test fun receiptReferencesRequireAnUnambiguousActiveSourceForTheSameVehicle() {
        fun receipt(id: String, type: String, ref: String, car: String = "car-1") = JSONObject()
            .put("id", id).put("vehicleId", car).put("refType", type).put("refId", ref)
        val receipts = JSONArray().put(receipt("ok", "fuel", "fuel-1"))
            .put(receipt("wrong-car", "fuel", "fuel-1", "other"))
            .put(receipt("missing", "maintenance", "unknown"))
            .put(receipt("unknown-type", "new-type", "fuel-1"))
            .put(receipt("deleted", "fuel", "missing").put("deletedAt", "2026-01-01T00:00:00Z"))
        val value = backup().apply { getJSONObject("data").put("receiptImages", receipts) }
        assertEquals(3, previewPwaBackup(value.toString()).brokenReceiptReferences)
        value.getJSONObject("data").getJSONArray("fuelLogs").put(JSONObject().put("id", "fuel-1").put("vehicleId", "car-1"))
        assertEquals(4, previewPwaBackup(value.toString()).brokenReceiptReferences)
    }

    @Test fun recordReceiptReferencesRequireMatchingUniqueActiveReceipt() {
        fun fuel(id: String, receipt: String, deleted: Boolean = false) = JSONObject().put("id", id).put("vehicleId", "car-1")
            .put("receiptImageId", receipt).apply { if (deleted) put("deletedAt", "2026-01-01T00:00:00Z") }
        fun receipt(id: String, ref: String, car: String = "car-1", type: String = "fuel") = JSONObject()
            .put("id", id).put("vehicleId", car).put("refType", type).put("refId", ref)
        val data = backup().getJSONObject("data")
        data.put("fuelLogs", JSONArray().put(fuel("a", "ra")).put(fuel("b", "missing"))
            .put(fuel("c", "rc")).put(fuel("d", "rd")).put(fuel("e", "re"))
            .put(fuel("old", "missing", true)).put(JSONObject().put("id", "no-receipt").put("vehicleId", "car-1")))
        data.put("receiptImages", JSONArray().put(receipt("ra", "a")).put(receipt("rc", "c", car = "other"))
            .put(receipt("rd", "d", type = "expense")).put(receipt("re", "e"))
            .put(receipt("re", "e").put("deletedAt", "2026-01-01T00:00:00Z")))
        val root = JSONObject().put("app", "carlog").put("schemaVersion", 1).put("data", data)
        assertEquals(4, previewPwaBackup(root.toString()).brokenRecordReceiptReferences)
    }

    @Test fun rejectsOtherFormatsAndInvalidTableTypes() {
        val value = backup().put("schemaVersion", 2)
        try { previewPwaBackup(value.toString()); fail("future version should be rejected") }
        catch (_: IllegalArgumentException) { }
        value.put("schemaVersion", 1).getJSONObject("data").put("fuelLogs", "not an array")
        try { previewPwaBackup(value.toString()); fail("invalid table should be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun previewRejectsAmbiguousFieldsAndMalformedUnicode() {
        val valid = backup().toString()
        val cases = listOf(valid.replaceFirst("{", "{\"app\":\"another\","),
            valid.replaceFirst("{", "{\"\\u0061pp\":\"another\","),
            valid.replace("차량", "\\uD800"))
        cases.forEach { input ->
            try { previewPwaBackup(input); fail("ambiguous or malformed backup should fail") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unknownExpenseCategoriesAreReportedForActiveRecordsOnly() {
        val expenses = JSONArray()
            .put(JSONObject().put("id", "a").put("vehicleId", "car-1").put("category", "parking"))
            .put(JSONObject().put("id", "b").put("vehicleId", "car-1").put("category", "future"))
            .put(JSONObject().put("id", "c").put("vehicleId", "car-1"))
            .put(JSONObject().put("id", "d").put("vehicleId", "car-1").put("category", "future").put("deletedAt", "2026-01-01T00:00:00Z"))
        val value = backup().apply { getJSONObject("data").put("expenseLogs", expenses) }
        assertEquals(2, previewPwaBackup(value.toString()).unknownExpenseCategories)
    }

    @Test fun distinguishesManualAndDerivedOdometersAndChecksTheirVehicleAndSource() {
        fun reading(id: String, source: String, ref: String? = null, car: String = "car-1") =
            JSONObject().put("id", id).put("vehicleId", car).put("source", source).apply { ref?.let { put("refId", it) } }
        val readings = JSONArray().put(reading("a", "manual"))
            .put(reading("b", "fuel", "fuel-1"))
            .put(reading("c", "fuel", "fuel-1", "other-car"))
            .put(reading("d", "expense", "missing"))
            .put(reading("e", "maintenance"))
            .put(reading("f", "future"))
            .put(reading("g", "fuel", "fuel-1").put("deletedAt", "2026-01-01T00:00:00Z"))
        val value = backup().apply { getJSONObject("data").put("odometerReadings", readings) }
        val preview = previewPwaBackup(value.toString())
        assertEquals(1, preview.manualOdometerReadings)
        assertEquals(4, preview.derivedOdometerReadings)
        assertEquals(1, preview.unknownOdometerSources)
        assertEquals(3, preview.brokenOdometerReferences)
    }

    @Test fun duplicateDerivedOdometersCountOnlyAdditionalActiveUnambiguousLinks() {
        fun reading(id: String, ref: String) = JSONObject().put("id", id).put("vehicleId", "car-1")
            .put("source", "fuel").put("refId", ref)
        val value = backup()
        value.getJSONObject("data").put("odometerReadings", JSONArray()
            .put(reading("a", "fuel-1")).put(reading("b", "fuel-1")).put(reading("c", "fuel-1"))
            .put(reading("deleted", "fuel-1").put("deletedAt", "2026-01-01T00:00:00Z"))
            .put(reading("missing1", "missing")).put(reading("missing2", "missing")))
        val result = previewPwaBackup(value.toString())
        assertEquals(2, result.duplicateDerivedOdometerReadings)
        assertEquals(2, result.brokenOdometerReferences)
    }

    @Test fun duplicateActiveSourceRecordsAreNotConsideredAnUnambiguousLink() {
        val value = backup()
        value.getJSONObject("data").getJSONArray("fuelLogs")
            .put(JSONObject().put("id", "fuel-1").put("vehicleId", "car-1"))
        value.getJSONObject("data").put("odometerReadings", JSONArray().put(
            JSONObject().put("id", "distance").put("vehicleId", "car-1").put("source", "fuel").put("refId", "fuel-1")
        ))
        val preview = previewPwaBackup(value.toString())
        assertEquals(1, preview.brokenOdometerReferences)
        assertEquals(1, preview.derivedOdometerReadings)
    }

    @Test fun duplicateSourceIdAcrossVehiclesMakesBothDirectionsAndOdometerAmbiguous() {
        val value = backup()
        val data = value.getJSONObject("data")
        data.getJSONArray("fuelLogs").getJSONObject(0).put("receiptImageId", "receipt")
        data.getJSONArray("fuelLogs").put(JSONObject().put("id", "fuel-1").put("vehicleId", "other-car"))
        data.put("receiptImages", JSONArray().put(JSONObject().put("id", "receipt").put("vehicleId", "car-1").put("refType", "fuel").put("refId", "fuel-1")))
        data.put("odometerReadings", JSONArray().put(JSONObject().put("id", "distance").put("vehicleId", "car-1").put("source", "fuel").put("refId", "fuel-1")))
        val preview = previewPwaBackup(value.toString())
        assertEquals(1, preview.brokenOdometerReferences)
        assertEquals(1, preview.brokenReceiptReferences)
        assertEquals(1, preview.brokenRecordReceiptReferences)
    }
}
