package kr.carlog.pleos

import androidx.room.Room
import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VehicleManagementImportRoomTest {
    @Test fun uncertainResultAfterCommitCanBeRetriedWithoutDuplicates() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        val store = ViewModelStore()
        val dao = db.dao()
        var failAfterCommit = true
        val flaky = object : CarlogDao by dao {
            override suspend fun importVehicleManagement(data: VehicleManagementExportData, currentId: String): VehicleManagementImportResult {
                val result = dao.importVehicleManagement(data, currentId)
                if (failAfterCommit) { failAfterCommit = false; throw IllegalStateException("result lost after commit") }
                return result
            }
        }
        var json = ""
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, flaky, MockVehicleDataSource(),
                openInputStream = { json.byteInputStream() }) as T
        })[CarlogViewModel::class.java]
        val job = vm.viewModelScope.coroutineContext[Job]
        try {
            val car = withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            val record = RecordEntity(vehicleId = car.id, type = "지출", timestamp = 1700000000000, odometer = 100.0,
                amount = 0.0, cost = 1000, label = "주차", category = "주차")
            json = exportVehicleManagementJson(VehicleManagementExportData(car, listOf(record), emptyList()), 1700000000000)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.previewVehicleImport(Uri.parse("content://test/records")) }
            withTimeout(10_000) { vm.vehicleImportPreview.filterNotNull().first() }
            withTimeout(10_000) { vm.vehicleImportBusy.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.applyVehicleImport() }
            withTimeout(10_000) { vm.vehicleExportMessage.filterNotNull().first { it.contains("결과를 확인하지 못했습니다") } }
            withTimeout(10_000) { vm.vehicleImportBusy.first { !it } }
            assertNotNull(dao.recordById(record.id))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.applyVehicleImport() }
            withTimeout(10_000) { vm.vehicleExportMessage.filterNotNull().first { it.contains("동일 기록 1건") } }
            assertEquals(listOf(record.id), dao.recordsForExport(car.id).map { it.id })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            if (job != null) withTimeout(10_000) { job.join() }
            db.close()
        }
    }

    @Test fun checksLargeImportsInBatchesWithoutLosingConflictOrVehicleChecks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CarlogDatabase::class.java).build()
        try {
            val dao = db.dao()
            val car = VehicleEntity(name = "차량")
            val other = VehicleEntity(name = "다른 차량")
            dao.insertVehicle(car); dao.insertVehicle(other)
            val ids = List(501) { UUID.randomUUID().toString() }
            ids.forEachIndexed { index, id ->
                dao.insertEvent(EventEntity(id = id, vehicleId = if (index == 500) other.id else car.id,
                    timestamp = 0, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete"))
            }
            val records = ids.map { id -> RecordEntity(vehicleId = car.id, type = "정비", timestamp = 0,
                odometer = 1.0, amount = 0.0, cost = 0, label = "정비", relatedEventId = id) }
            val snapshot = VehicleManagementExportData(car, records, emptyList())
            val missing = dao.importVehicleManagement(snapshot, car.id)
            assertEquals(VehicleManagementImportStatus.MISSING_EVENT, missing.status)
            assertEquals(listOf(ids.last()), missing.missingEventIds)
            assertNull(dao.recordById(records.first().id))
            val valid = snapshot.copy(records = records.dropLast(1))
            dao.insertRecord(valid.records.last().copy(label = "충돌"))
            assertEquals(VehicleManagementImportStatus.RECORD_CONFLICT, dao.importVehicleManagement(valid, car.id).status)
            assertNull(dao.recordById(valid.records.first().id))
        } finally { db.close() }
    }

    @Test fun importsAtomicallyAndProtectsExistingAndDeletedRecords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CarlogDatabase::class.java).build()
        try {
            val dao = db.dao()
            val car = VehicleEntity(name = "차량")
            dao.insertVehicle(car)
            val fuel = RecordEntity(vehicleId = car.id, type = "주유", timestamp = 1700000000000, odometer = 100.0, amount = 10.0, cost = 10000, label = "주유", full = true)
            val schedule = ScheduleEntity(vehicleId = car.id, label = "오일", lastKm = 100.0, intervalKm = 10000.0)
            val parsed = parseVehicleManagementJson(exportVehicleManagementJson(VehicleManagementExportData(car, listOf(fuel), listOf(schedule)), 1700000000000))
            assertEquals(VehicleManagementImportResult(VehicleManagementImportStatus.APPLIED, 2, 0), dao.importVehicleManagement(parsed, car.id))
            assertEquals(VehicleManagementImportResult(VehicleManagementImportStatus.APPLIED, 0, 2), dao.importVehicleManagement(parsed, car.id))
            val additional = fuel.copy(id = UUID.randomUUID().toString())
            val conflict = parsed.copy(records = listOf(additional, fuel.copy(cost = 20000)))
            assertEquals(VehicleManagementImportStatus.RECORD_CONFLICT, dao.importVehicleManagement(conflict, car.id).status)
            assertNull(dao.recordById(additional.id))
            dao.softDeleteRecord(fuel.id, car.id, 1700000010000)
            assertEquals(VehicleManagementImportStatus.RECORD_CONFLICT, dao.importVehicleManagement(parsed, car.id).status)
            assertNotNull(dao.recordById(fuel.id)?.deletedAt)
            assertEquals(VehicleManagementImportStatus.VEHICLE_MISMATCH, dao.importVehicleManagement(parsed, UUID.randomUUID().toString()).status)
            assertEquals(VehicleManagementImportStatus.VEHICLE_CONFLICT, dao.importVehicleManagement(parsed.copy(vehicle = car.copy(name = "다른 이름")), car.id).status)
            val linked = additional.copy(type = "정비", amount = 0.0, full = false, relatedEventId = UUID.randomUUID().toString())
            val linked2 = linked.copy(id = UUID.randomUUID().toString(), relatedEventId = UUID.randomUUID().toString())
            val result = dao.importVehicleManagement(parsed.copy(records = listOf(linked, linked2)), car.id)
            assertEquals(VehicleManagementImportStatus.MISSING_EVENT, result.status)
            assertEquals(listOf(linked.relatedEventId, linked2.relatedEventId), result.missingEventIds)
            assertNull(dao.recordById(linked.id))
            assertNull(dao.recordById(linked2.id))
        } finally { db.close() }
    }
}
