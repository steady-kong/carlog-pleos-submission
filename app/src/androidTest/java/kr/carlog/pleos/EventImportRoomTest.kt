package kr.carlog.pleos

import androidx.room.Room
import android.content.Context
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventImportRoomTest {
    @Test fun eventImportResultLostAfterCommitCanBeRetried() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        var failAfterCommit = true
        val flaky = object : CarlogDao by dao {
            override suspend fun importEvent(value: ImportedEvent, mappedVehicleId: String?): EventImportResult {
                val result = dao.importEvent(value, mappedVehicleId)
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
            val event = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
            json = exportEventJson(event, listOf(SampleEntity(event.id, 100_000, "fuel", 50.0, "%")))
            val uri = Uri.parse("content://test/event")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.importEventFile(uri) }
            withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it.contains("결과를 확인하지 못했습니다") } }
            withTimeout(10_000) { vm.importing.first { !it } }
            assertNotNull(dao.eventById(event.id))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.importEventFile(uri) }
            withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it == "이미 같은 이벤트가 있습니다." } }
            assertEquals(1, dao.samplesOnce(event.id).size)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            if (job != null) withTimeout(10_000) { job.join() }
        }
    }

    @Test fun preciseSchemaVersionsAreNotRoundedOnAndroid() {
        fun changed(text: String, value: String) = text.replaceFirst(Regex("\\\"schemaVersion\\\"\\s*:\\s*[12]"), "\"schemaVersion\": $value")
        val car = VehicleEntity(name = "차량")
        val event = EventEntity(vehicleId = car.id, timestamp = 60_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
        val eventJson = exportEventJson(event, listOf(SampleEntity(event.id, event.timestamp, "fuel", 10.0, "%")))
        val vehicleJson = exportVehicleManagementJson(VehicleManagementExportData(car, emptyList(), emptyList()), 60_000)
        val backupJson = "{\"app\":\"carlog\",\"schemaVersion\":1,\"data\":{}}"
        parseEventJson(changed(eventJson, "1.0"))
        parseVehicleManagementJson(changed(vehicleJson, "1e0"))
        previewPwaBackup(changed(backupJson, "1.000"))
        val underflow = eventJson.replaceFirst(Regex("\\\"value\\\"\\s*:\\s*10(?:\\.0)?"), "\"value\": 1e-999")
        try { parseEventJson(underflow); fail("nonzero value must not become zero") } catch (_: IllegalArgumentException) { }
        for (action in listOf<() -> Unit>(
            { parseEventJson(changed(eventJson, "1.0000000000000000001")); Unit },
            { parseVehicleManagementJson(changed(vehicleJson, "1.0000000000000000001")); Unit },
            { previewPwaBackup(changed(backupJson, "1.0000000000000000001")); Unit },
        )) {
            try { action(); fail("distinct version must be rejected") } catch (_: IllegalArgumentException) { }
        }
    }

    private lateinit var database: CarlogDatabase
    private lateinit var dao: CarlogDao

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CarlogDatabase::class.java).build()
        dao = database.dao()
    }
    @After fun cleanup() = database.close()

    @Test fun repairEntryRejectsOtherVehicleEventAndAcceptsMatchingEvent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val car = VehicleEntity(name = "현재 차량")
        val other = VehicleEntity(name = "다른 차량")
        val ownEvent = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "차량 이상", memo = "현재", source = "MOCK", status = "complete")
        val otherEvent = ownEvent.copy(id = java.util.UUID.randomUUID().toString(), vehicleId = other.id, memo = "다른 차량")
        dao.insertVehicle(car); dao.insertVehicle(other); dao.setActiveVehicle(ActiveVehicleEntity(vehicleId = car.id))
        dao.insertEvent(ownEvent); dao.insertEvent(otherEvent)
        var cleanupJob: Job? = null
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            cleanupJob = vm.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first { it.id == car.id } }
            val wrongSaved = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.saveRecord("정비", "정비", 1000.0, 0.0, 10000, false, null, null, null, otherEvent.id, "확인", 101_000, null, null) { wrongSaved.complete(Unit) }
            }
            withTimeout(10_000) { vm.recordError.filterNotNull().first() }
            assertFalse(wrongSaved.isCompleted)
            assertTrue(dao.records(car.id).first().isEmpty())
            withTimeout(10_000) { vm.recordSaving.first { !it } }
            val saved = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.saveRecord("정비", "정비", 1000.0, 0.0, 10000, false, null, null, null, ownEvent.id, "확인", 101_000, null, null) { saved.complete(Unit) }
            }
            withTimeout(10_000) { saved.await() }
            val repair = dao.records(car.id).first().single()
            assertEquals(ownEvent.id, repair.relatedEventId)
            assertEquals("확인", repair.repairOutcome)
            assertEquals(listOf(repair), dao.linkedRepairs(car.id, ownEvent.id))
            assertTrue(dao.linkedRepairs(other.id, otherEvent.id).isEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            cleanupJob?.let { withTimeout(10_000) { it.join() } }
        }
        Unit
    }

    @Test fun equalTimestampRecordsEventsAndSamplesHaveStableTieBreakers() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val event = EventEntity(id = "b", vehicleId = car.id, timestamp = 100, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
        dao.insertEvent(event); dao.insertEvent(event.copy(id = "a")); dao.insertEvent(event.copy(id = "new", timestamp = 101))
        val record = RecordEntity(id = "b", vehicleId = car.id, type = "지출", timestamp = 100, odometer = 0.0, amount = 0.0, cost = 100, label = "기록")
        dao.insertRecord(record); dao.insertRecord(record.copy(id = "a")); dao.insertRecord(record.copy(id = "new", timestamp = 101))
        dao.insertSamples(listOf(SampleEntity(event.id, 100, "z", 1.0, ""), SampleEntity(event.id, 100, "a", 2.0, "")))
        assertEquals(listOf("new", "a", "b"), dao.events(car.id).first().map { it.id })
        assertEquals(listOf("new", "a", "b"), dao.records(car.id).first().map { it.id })
        assertEquals(listOf("a", "z"), dao.samples(event.id).first().map { it.name })
    }

    @Test fun invalidExchangeTimesNeverPartiallyImportEventsOrVehicleRecords() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val car = VehicleEntity(name = "차량")
        val original = RecordEntity(vehicleId = car.id, type = "지출", timestamp = 100, odometer = 0.0, odometerKnown = false, amount = 0.0, cost = 100, label = "기존")
        val candidate = original.copy(id = java.util.UUID.randomUUID().toString(), label = "새 기록")
        val event = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "도로 상태", memo = "test", source = "MOCK", status = "complete")
        dao.insertVehicle(car); dao.insertRecord(original)
        val eventFile = java.io.File.createTempFile("invalid-event-time-", ".json", app.cacheDir)
        val vehicleFile = java.io.File.createTempFile("invalid-vehicle-time-", ".json", app.cacheDir)
        var cleanupJob: Job? = null
        try {
            val eventJson = org.json.JSONObject(exportEventJson(event, emptyList())).put("occurredAt", "2016-12-31T23:59:60Z")
            eventFile.writeText(eventJson.toString())
            val vehicleJson = org.json.JSONObject(exportVehicleManagementJson(VehicleManagementExportData(car, listOf(candidate), emptyList()), 100))
            vehicleJson.getJSONArray("records").getJSONObject(0).put("occurredAt", "2020-01-01T24:00:00Z")
            vehicleFile.writeText(vehicleJson.toString())
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            cleanupJob = vm.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.importEventFile(Uri.fromFile(eventFile)) }
            val eventError = withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it.contains("가져오지 못했습니다") } }
            assertTrue(eventError.contains("이벤트 발생 시각"))
            assertNull(dao.eventById(event.id))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.previewVehicleImport(Uri.fromFile(vehicleFile)) }
            val vehicleError = withTimeout(10_000) { vm.vehicleExportMessage.filterNotNull().first { it.contains("읽을 수 없습니다") } }
            assertTrue(vehicleError.contains("차량 기록 1번째 시각"))
            assertNull(vm.vehicleImportPreview.value)
            assertNull(dao.recordById(candidate.id))
            assertEquals(original, dao.recordById(original.id))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            cleanupJob?.let { withTimeout(10_000) { it.join() } }
            eventFile.delete(); vehicleFile.delete()
        }
        Unit
    }

    @Test fun cancelledImportPickersExplainOutcomeWithoutChangingStoredRecords() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val car = VehicleEntity(name = "차량")
        val record = RecordEntity(vehicleId = car.id, type = "지출", timestamp = 1, odometer = 0.0, odometerKnown = false, amount = 0.0, cost = 100, label = "기존")
        dao.insertVehicle(car); dao.insertRecord(record)
        var cleanupJob: Job? = null
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            cleanupJob = vm.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.importEventFile(null); vm.previewVehicleImport(null); vm.previewPwaFile(null)
            }
            assertTrue(vm.exchangeMessage.value.orEmpty().contains("취소되었거나"))
            assertTrue(vm.vehicleExportMessage.value.orEmpty().contains("취소되었거나"))
            assertTrue(vm.backupMessage.value.orEmpty().contains("취소되었거나"))
            assertEquals(record, dao.recordById(record.id))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.eventPickerFailed(); vm.vehiclePickerFailed(); vm.backupPickerFailed()
            }
            assertTrue(vm.exchangeMessage.value.orEmpty().contains("열지 못했습니다"))
            assertTrue(vm.vehicleExportMessage.value.orEmpty().contains("열지 못했습니다"))
            assertTrue(vm.backupMessage.value.orEmpty().contains("열지 못했습니다"))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            cleanupJob?.let { withTimeout(10_000) { it.join() } }
        }
        Unit
    }

    @Test fun missingScheduleDateRejectsStaleOrCrossVehicleUpdates() = runBlocking {
        val car = VehicleEntity(name = "차량")
        val other = VehicleEntity(name = "다른 차량")
        dao.insertVehicle(car); dao.insertVehicle(other)
        val original = ScheduleEntity(vehicleId = car.id, label = "오일", lastKm = 1000.0, intervalKm = 5000.0, intervalMonths = 12)
        dao.insertSchedule(original)
        assertEquals(0, dao.fillMissingScheduleDateIfUnchanged(original, other.id, 100, 200))
        assertEquals(1, dao.updateSchedule(original.id, car.id, "변경", 6000.0, 12, null, 150))
        assertEquals(0, dao.fillMissingScheduleDateIfUnchanged(original, car.id, 100, 200))
        val latest = requireNotNull(dao.scheduleById(original.id))
        assertEquals(1, dao.fillMissingScheduleDateIfUnchanged(latest, car.id, 100, 200))
        assertEquals(0, dao.fillMissingScheduleDateIfUnchanged(latest, car.id, 300, 400))
        val saved = requireNotNull(dao.scheduleById(original.id))
        assertEquals(100L, saved.lastDoneAt!!)
        assertEquals(1000.0, saved.lastKm, 0.0)
    }

    @Test fun eventExportLimitShowsReasonAndPreservesStoredSamples() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val event = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "일반 이벤트", memo = "원본", source = "MOCK", status = "complete")
        val samples = (0..EVENT_EXCHANGE_MAX_SAMPLES).map { SampleEntity(event.id, 40_000L + it, "fuel", 50.0, "%") }
        dao.startEvent(event, samples)
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            val unexpected = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.eventJsonForExport(event.id) { unexpected.complete(Unit) } }
            val message = withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first() }
            assertTrue(message.contains("1만 개"))
            assertTrue(message.contains("원본 기록은 유지"))
            assertFalse(unexpected.isCompleted)
            assertEquals(event, dao.eventById(event.id))
            assertEquals(samples.size, dao.samplesOnce(event.id).size)
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() } }
        Unit
    }

    @Test fun vehicleEditUsesOriginalValuesAsPrecondition() = runBlocking {
        val original = VehicleEntity(name = "처음")
        dao.insertVehicle(original)
        assertEquals(1, dao.updateVehicleInfoIfUnchanged(original, original.copy(name = "다른 화면")))
        assertEquals(0, dao.updateVehicleInfoIfUnchanged(original, original.copy(name = "오래된 입력")))
        assertEquals("다른 화면", dao.vehicleById(original.id)?.name)
        assertEquals(0, dao.updateVehicleInfoIfUnchanged(original, original.copy(id = java.util.UUID.randomUUID().toString())))
    }

    @Test fun staleRecordEditCannotOverwriteChangedOrDeletedRecord() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val original = RecordEntity(vehicleId = car.id, type = "지출", timestamp = 100, odometer = 100.0, amount = 0.0, cost = 2000, label = "원본", updatedAt = 10)
        dao.insertRecord(original)
        val changed = original.copy(label = "다른 화면 변경", cost = 3000) // same revision timestamp
        dao.updateRecord(changed)
        assertFalse(dao.replaceRecordIfUnchanged(original, original.copy(cost = 4000)))
        assertEquals(changed, dao.activeRecord(original.id))
        assertTrue(dao.replaceRecordIfUnchanged(changed, changed.copy(cost = 5000)))
        dao.softDeleteRecord(original.id, car.id, 20)
        assertFalse(dao.replaceRecordIfUnchanged(changed.copy(cost = 5000), changed.copy(cost = 6000)))
        assertNull(dao.activeRecord(original.id))
    }

    @Test fun eventEditDetectsMetadataConflictsButAllowsCaptureStatusChange() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val event = EventEntity(vehicleId = car.id, timestamp = 100, category = "차량 이상", memo = "원본", source = "MOCK", status = "collecting")
        dao.insertEvent(event)
        dao.finishCollectingEvent(event.id, "complete")
        assertEquals(1, dao.updateEventDetailsIfUnchanged(event.id, car.id, event.category, event.memo, "도로 상태", "다른 수정"))
        assertEquals(0, dao.updateEventDetailsIfUnchanged(event.id, car.id, event.category, event.memo, "일반 이벤트", "오래된 입력"))
        assertEquals("다른 수정", dao.eventById(event.id)?.memo)
        assertEquals("complete", dao.eventById(event.id)?.status)
    }

    @Test fun scheduleUpdatesRejectAStaleBaselineAndKeepTheNewerValues() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val schedule = ScheduleEntity(vehicleId = car.id, label = "오일", lastKm = 1000.0, intervalKm = 5000.0, lastDoneAt = 100, updatedAt = 10)
        dao.insertSchedule(schedule)
        assertEquals(1, dao.updateSchedule(schedule.id, car.id, "다른 화면", 6000.0, null, null, 10))
        assertEquals(0, dao.updateScheduleIfUnchanged(schedule, car.id, "이전 화면", 7000.0, null, null, 20))
        assertEquals(0, dao.markScheduleDoneIfUnchanged(schedule, car.id, 2000.0, 200, 20))
        val latest = requireNotNull(dao.scheduleById(schedule.id))
        assertEquals("다른 화면", latest.label)
        assertEquals(1, dao.markScheduleDoneIfUnchanged(latest, car.id, 2000.0, 200, 20))
        assertEquals(2000.0, dao.scheduleById(schedule.id)!!.lastKm, 0.0)
    }

    @Test fun deletionRejectsStaleRecordAndScheduleConfirmations() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val record = RecordEntity(vehicleId = car.id, type = "지출", timestamp = 100, odometer = 100.0, amount = 0.0, cost = 10, label = "원본")
        val schedule = ScheduleEntity(vehicleId = car.id, label = "오일", lastKm = 1000.0, intervalKm = 5000.0)
        dao.insertRecord(record); dao.insertSchedule(schedule)
        dao.updateRecord(record.copy(cost = 20))
        dao.updateSchedule(schedule.id, car.id, "변경됨", 5000.0, null, null, 1)
        assertEquals(0, dao.softDeleteRecordIfUnchanged(record, car.id, 2))
        assertEquals(0, dao.softDeleteScheduleIfUnchanged(schedule, car.id, 2))
        assertEquals(20L, dao.activeRecord(record.id)?.cost)
        assertEquals("변경됨", dao.scheduleById(schedule.id)?.label)
        assertEquals(1, dao.softDeleteRecordIfUnchanged(requireNotNull(dao.activeRecord(record.id)), car.id, 3))
        assertEquals(1, dao.softDeleteScheduleIfUnchanged(requireNotNull(dao.scheduleById(schedule.id)), car.id, 3))
    }

    private fun SQLiteDatabase.removeV10VehicleColumns() {
        execSQL("DROP TABLE ActiveVehicleEntity")
        listOf("maker", "model", "year", "fuelType").forEach { execSQL("ALTER TABLE VehicleEntity DROP COLUMN $it") }
    }

    @Test fun vehicleManagementExportUsesOnlySelectedActiveRecords() = runBlocking {
        val car = VehicleEntity(name = "첫 차량")
        val other = VehicleEntity(name = "다른 차량")
        dao.insertVehicle(car); dao.insertVehicle(other)
        val fuel = RecordEntity(vehicleId = car.id, type = "주유", timestamp = 100, odometer = 1200.0, amount = 30.0, cost = 45000, label = "주유")
        val deleted = fuel.copy(id = java.util.UUID.randomUUID().toString(), deletedAt = 200)
        val otherFuel = fuel.copy(id = java.util.UUID.randomUUID().toString(), vehicleId = other.id)
        dao.insertRecord(fuel); dao.insertRecord(deleted); dao.insertRecord(otherFuel)
        val schedule = ScheduleEntity(vehicleId = car.id, label = "오일", lastKm = 1000.0, intervalKm = 5000.0)
        dao.insertSchedule(schedule)
        dao.insertSchedule(schedule.copy(id = java.util.UUID.randomUUID().toString(), deletedAt = 200))
        val snapshot = requireNotNull(dao.vehicleManagementExport(car.id))
        assertEquals(listOf(fuel), snapshot.records)
        assertEquals(listOf(schedule), snapshot.schedules)
        val json = org.json.JSONObject(exportVehicleManagementJson(snapshot, 100))
        assertEquals(car.id, json.getJSONObject("vehicle").getString("id"))
        assertEquals(1, json.getJSONArray("records").length())
        assertEquals(1, json.getJSONArray("schedules").length())
        assertNull(dao.vehicleManagementExport(java.util.UUID.randomUUID().toString()))
    }

    @Test fun pwaBackupFilePreviewReportsDerivedDistanceWithoutChangingRoom() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val file = java.io.File.createTempFile("pwa-preview-", ".json", app.cacheDir)
        val car = VehicleEntity(name = "기존 차량")
        dao.insertVehicle(car)
        file.writeText("""{"app":"carlog","schemaVersion":1,"data":{"vehicles":[{"id":"web-car","nickname":"웹 차량"}],"fuelLogs":[{"id":"fuel","vehicleId":"web-car"}],"odometerReadings":[{"id":"a","vehicleId":"web-car","source":"manual"},{"id":"b","vehicleId":"web-car","source":"fuel","refId":"fuel"},{"id":"c","vehicleId":"web-car","source":"expense","refId":"missing"}]}}""", Charsets.UTF_8)
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.previewPwaFile(Uri.fromFile(file)) }
            val preview = withTimeout(10_000) { vm.backupPreview.filterNotNull().first() }
            assertEquals(1, preview.manualOdometerReadings)
            assertEquals(2, preview.derivedOdometerReadings)
            assertEquals(1, preview.brokenOdometerReferences)
            assertEquals(car, dao.vehicleById(car.id))
            assertNull(dao.vehicleById("web-car"))
            assertTrue(dao.records(car.id).first().isEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            file.delete()
        }
        Unit
    }

    @Test fun pwaPreviewKeepsLatestSelectionAndCancellationWhenOldReadFinishesLate() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val firstStarted = CompletableDeferred<Unit>()
        val cancelledStarted = CompletableDeferred<Unit>()
        val firstClosed = CompletableDeferred<Int>()
        val cancelledClosed = CompletableDeferred<Int>()
        val firstRelease = java.util.concurrent.CountDownLatch(1)
        val cancelledRelease = java.util.concurrent.CountDownLatch(1)
        fun backup(name: String) = """{"app":"carlog","schemaVersion":1,"data":{"vehicles":[{"id":"web","nickname":"$name"}]}}""".toByteArray()
        fun slow(name: String, started: CompletableDeferred<Unit>, release: java.util.concurrent.CountDownLatch, closed: CompletableDeferred<Int>) =
            object : java.io.ByteArrayInputStream(backup(name)) {
                private var waited = false
                private var reads = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    if (!waited) { waited = true; started.complete(Unit); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                    return super.read(bytes, offset, length)
                }
                override fun close() { closed.complete(reads); super.close() }
            }
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource(), openInputStream = { uri ->
                    when (uri.lastPathSegment) {
                        "first" -> slow("이전", firstStarted, firstRelease, firstClosed)
                        "cancelled" -> slow("취소", cancelledStarted, cancelledRelease, cancelledClosed)
                        else -> java.io.ByteArrayInputStream(backup("최신"))
                    }
                }) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.previewPwaFile(Uri.parse("content://test/first")) }
            withTimeout(10_000) { firstStarted.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.previewPwaFile(Uri.parse("content://test/latest")) }
            withTimeout(10_000) { vm.backupPreview.filterNotNull().first { it.vehicles.single().name == "최신" } }
            firstRelease.countDown()
            assertEquals(1, withTimeout(10_000) { firstClosed.await() })
            assertEquals("최신", vm.backupPreview.value?.vehicles?.single()?.name)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.previewPwaFile(Uri.parse("content://test/cancelled")) }
            withTimeout(10_000) { cancelledStarted.await() }
            assertTrue(vm.backupLoading.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.closeBackupPreview() }
            cancelledRelease.countDown()
            assertEquals(1, withTimeout(10_000) { cancelledClosed.await() })
            assertNull(vm.backupPreview.value)
            assertNull(vm.backupMessage.value)
            assertFalse(vm.backupLoading.value)
        } finally {
            firstRelease.countDown(); cancelledRelease.countDown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        }
        Unit
    }

    @Test fun vehicleManagementExportCanBeSavedToSelectedFile() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val file = java.io.File.createTempFile("vehicle-records-", ".json", app.cacheDir)
        val car = VehicleEntity(name = "차량")
        val fuel = RecordEntity(vehicleId = car.id, type = "주유", timestamp = 100, odometer = 1000.0, amount = 10.0, cost = 15000, label = "주유")
        dao.insertVehicle(car); dao.insertRecord(fuel)
        val ready = CompletableDeferred<Unit>()
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.vehicleDetails.first { it != null } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.prepareVehicleExport { ready.complete(Unit) } }
            withTimeout(10_000) { ready.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.finishVehicleExport(Uri.fromFile(file)) }
            withTimeout(10_000) { vm.vehicleExportMessage.filterNotNull().first { it == "차량 관리 기록 JSON을 저장했습니다." } }
            val json = org.json.JSONObject(file.readText(Charsets.UTF_8))
            assertEquals(car.id, json.getJSONObject("vehicle").getString("id"))
            assertEquals(fuel.id, json.getJSONArray("records").getJSONObject(0).getString("id"))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            file.delete()
        }
        Unit
    }

    @Test fun vehicleManagementFilePreviewApplyAndConflictPreserveData() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val file = java.io.File.createTempFile("vehicle-import-", ".json", app.cacheDir)
        val car = VehicleEntity(name = "차량")
        val fuel = RecordEntity(vehicleId = car.id, type = "주유", timestamp = 100, odometer = 1000.0, amount = 10.0, cost = 15000, label = "주유")
        dao.insertVehicle(car)
        file.writeText(exportVehicleManagementJson(VehicleManagementExportData(car, listOf(fuel), emptyList()), 100), Charsets.UTF_8)
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.previewVehicleImport(Uri.fromFile(file)) }
            withTimeout(10_000) { vm.vehicleImportPreview.filterNotNull().first() }
            withTimeout(10_000) { vm.vehicleImportBusy.first { !it } }
            assertNull(dao.recordById(fuel.id))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.applyVehicleImport() }
            withTimeout(10_000) { vm.vehicleExportMessage.filterNotNull().first { it.contains("추가 1건") } }
            withTimeout(10_000) { vm.vehicleImportBusy.first { !it } }
            assertEquals(fuel, dao.recordById(fuel.id))
            assertNull(vm.vehicleImportPreview.value)
            file.writeText(exportVehicleManagementJson(VehicleManagementExportData(car, listOf(fuel.copy(cost = 20000)), emptyList()), 100), Charsets.UTF_8)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.previewVehicleImport(Uri.fromFile(file)) }
            withTimeout(10_000) { vm.vehicleImportPreview.filterNotNull().first() }
            withTimeout(10_000) { vm.vehicleImportBusy.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.applyVehicleImport() }
            withTimeout(10_000) { vm.vehicleExportMessage.filterNotNull().first { it.contains("충돌") } }
            assertEquals(fuel, dao.recordById(fuel.id))
            assertNotNull(vm.vehicleImportPreview.value)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            file.delete()
        }
        Unit
    }

    @Test fun completionRecoveryPreservesCommittedAndInterruptsCollectingRoomEvents() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val first = EventEntity(vehicleId = car.id, timestamp = 100, category = "일반 이벤트", memo = "", source = "MOCK", status = "collecting")
        val second = first.copy(id = java.util.UUID.randomUUID().toString())
        dao.insertEvent(first); dao.insertEvent(second)
        dao.finishCollectingEvent(first.id, "complete")
        dao.recoverCancelledEventCompletion(first.id)
        dao.recoverCancelledEventCompletion(second.id)
        assertEquals("complete", dao.eventById(first.id)?.status)
        assertEquals("interrupted", dao.eventById(second.id)?.status)
        dao.recoverCancelledEventCompletion(second.id)
        assertEquals("interrupted", dao.eventById(second.id)?.status)
    }

    @Test fun reportSnapshotReadsOnlyMatchingVehicleEventAndActiveRepairs() = runBlocking {
        val car = VehicleEntity(name = "차량")
        val other = VehicleEntity(name = "다른 차량")
        dao.insertVehicle(car); dao.insertVehicle(other)
        val event = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "일반 이벤트", memo = "이전", source = "MOCK", status = "collecting")
        val sample = SampleEntity(event.id, 99_000, "fuel", 50.0, "%")
        val repair = RecordEntity(vehicleId = car.id, type = "정비", timestamp = 101_000, odometer = 100.0, amount = 0.0, cost = 0, label = "점검", relatedEventId = event.id)
        dao.startEvent(event, listOf(sample)); dao.insertRecord(repair)
        dao.insertRecord(repair.copy(id = java.util.UUID.randomUUID().toString(), deletedAt = 1))
        dao.insertRecord(repair.copy(id = java.util.UUID.randomUUID().toString(), vehicleId = other.id))
        assertNull(dao.maintenanceReportData(event.id, other.id))
        val snapshot = requireNotNull(dao.maintenanceReportData(event.id, car.id))
        dao.updateEventDetails(event.id, car.id, event.category, "이후")
        dao.finishCollectingEvent(event.id, "complete")
        assertEquals(event, snapshot.event)
        assertEquals(listOf(sample), snapshot.samples)
        assertEquals(listOf(repair), snapshot.repairs)
        assertEquals(car, snapshot.vehicle)
        assertEquals("complete", dao.maintenanceReportData(event.id, car.id)?.event?.status)
    }

    @Test fun reportCanBeSavedAsExactPreviewAndBlocksVehicleSwitchUntilFinished() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val file = java.io.File.createTempFile("carlog-report-save-", ".txt", app.cacheDir)
        val car = VehicleEntity(name = "차량")
        val other = VehicleEntity(name = "다른 차량")
        dao.insertVehicle(car); dao.insertVehicle(other)
        val event = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "차량 이상", memo = "시연 메모", source = "MOCK", status = "complete")
        dao.insertEvent(event)
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setMockCollection(false) }
            withTimeout(10_000) { vm.captureTransitioning.first { !it } }
            val report = vm.reportFromStorage(event.id)
            val ready = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.prepareReportExport(report) { ready.complete(Unit) } }
            withTimeout(10_000) { ready.await() }
            assertTrue(vm.reportExportBusy.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectVehicle(other) }
            assertEquals(car.id, vm.vehicleDetails.value?.id)
            assertTrue(vm.vehicleExportMessage.value.orEmpty().contains("마친 뒤"))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.finishReportExport(Uri.fromFile(file)) }
            withTimeout(10_000) { vm.reportExportMessage.filterNotNull().first { it == "정비 참고 리포트를 저장했습니다." } }
            assertEquals(report, file.readText(Charsets.UTF_8))
            assertTrue(report.contains("시연 메모"))
            withTimeout(10_000) { vm.reportExportBusy.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectVehicle(other) }
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first { it.id == other.id } }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            file.delete()
        }
        Unit
    }

    @Test fun reportFilePickerCancellationAndLaunchFailureReleaseBusyState() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.prepareReportExport("보고서") { }
                assertTrue(vm.reportExportBusy.value)
                vm.finishReportExport(null)
                assertFalse(vm.reportExportBusy.value)
                assertTrue(vm.reportExportMessage.value.orEmpty().contains("취소되었거나"))
                assertTrue(vm.reportExportMessage.value.orEmpty().contains("공유"))
                vm.prepareReportExport("보고서") { throw IllegalStateException("no picker") }
                assertFalse(vm.reportExportBusy.value)
            }
            assertTrue(vm.reportExportMessage.value.orEmpty().contains("선택기를 열지 못했습니다"))
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() } }
        Unit
    }

    @Test fun reportFileWriteKeepsVehicleSwitchBlockedUntilWriteCompletes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val car = VehicleEntity(name = "차량")
        val other = VehicleEntity(name = "다른 차량")
        dao.insertVehicle(car); dao.insertVehicle(other)
        val started = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val sink = object : java.io.ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                started.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                super.write(bytes, offset, length)
            }
        }
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource(), openExportStream = { sink }) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setMockCollection(false) }
            withTimeout(10_000) { vm.captureTransitioning.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.prepareReportExport("저장할 리포트") { }
                vm.finishReportExport(Uri.parse("content://test/report"))
            }
            withTimeout(10_000) { started.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.finishReportExport(null)
                vm.selectVehicle(other)
            }
            assertTrue(vm.reportExportBusy.value)
            assertEquals(car.id, vm.vehicleDetails.value?.id)
            release.countDown()
            withTimeout(10_000) { vm.reportExportBusy.first { !it } }
            assertEquals("저장할 리포트", sink.toString("UTF-8"))
            assertEquals("정비 참고 리포트를 저장했습니다.", vm.reportExportMessage.value)
        } finally {
            release.countDown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        }
        Unit
    }

    @Test fun uncommittedStartFailureCanRetryWithRoom() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var failStart = true
        val store = object : EventCaptureStore by RoomEventCaptureStore(dao) {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) {
                if (failStart) throw IllegalStateException("failure before commit")
                dao.startEvent(event, samples)
            }
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L
            override fun elapsedRealtimeMillis() = 1_000L
        }
        try {
            val capture = EventCapture(scope, store, clock, { fail(it) })
            try { capture.start(car.id, "일반 이벤트", "retry", "MOCK"); fail("expected failure") }
            catch (error: IllegalStateException) { assertFalse(error is CaptureStoppedException) }
            assertTrue(dao.events(car.id).first().isEmpty())
            failStart = false
            val id = capture.start(car.id, "일반 이벤트", "retry", "MOCK")
            assertEquals("collecting", dao.eventById(id)?.status)
            capture.interruptAll()
            assertEquals("interrupted", dao.eventById(id)?.status)
        } finally { scope.cancel() }
    }

    @Test fun committedStartFailureIsInterruptedInRoom() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var eventId = ""
        val store = object : EventCaptureStore by RoomEventCaptureStore(dao) {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) {
                eventId = event.id
                dao.startEvent(event, samples)
                throw IllegalStateException("failure after commit")
            }
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L
            override fun elapsedRealtimeMillis() = 1_000L
        }
        try {
            val capture = EventCapture(scope, store, clock, { fail(it) })
            try { capture.start(car.id, "일반 이벤트", "", "MOCK"); fail("expected failure") }
            catch (_: IllegalStateException) { }
            assertEquals("interrupted", dao.eventById(eventId)?.status)
        } finally { scope.cancel() }
    }

    @Test fun cancellationDuringAppendFailureRecoveryStillInterruptsRoomEvents() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cancelOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = object : EventCaptureStore {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) = dao.startEvent(event, samples)
            override suspend fun append(samples: List<SampleEntity>) { throw IllegalStateException("write failed") }
            override suspend fun finish(id: String, status: String) {
                if (status == "interrupted" && cancelOnce.compareAndSet(true, false)) throw kotlinx.coroutines.CancellationException("recovery cancelled")
                dao.finishCollectingEvent(id, status)
            }
            override suspend fun recoverCancelledCompletion(id: String) = dao.recoverCancelledEventCompletion(id)
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L
            override fun elapsedRealtimeMillis() = 1_000L
        }
        val errors = mutableListOf<String>()
        val capture = EventCapture(scope, store, clock, { fail(it) }, onTerminalFailure = errors::add)
        try {
            val first = capture.start(car.id, "일반 이벤트", "first", "MOCK")
            val second = capture.start(car.id, "차량 이상", "second", "MOCK")
            try { capture.accept(Signal(100_000, "fuel", 50.0, "%")); fail("cancellation expected") }
            catch (e: kotlinx.coroutines.CancellationException) { assertTrue(e.suppressed.any { it is IllegalStateException }) }
            assertEquals("interrupted", dao.eventById(first)?.status)
            assertEquals("interrupted", dao.eventById(second)?.status)
            assertEquals(1, errors.size)
        } finally { scope.cancel() }
        Unit
    }

    @Test fun cancelledExplicitInterruptionRecoversRemainingRoomEvents() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val committed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstWrite = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = object : EventCaptureStore {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) = dao.startEvent(event, samples)
            override suspend fun append(samples: List<SampleEntity>) = dao.appendCollectingSamples(samples)
            override suspend fun finish(id: String, status: String) {
                dao.finishCollectingEvent(id, status)
                if (status == "interrupted" && firstWrite.compareAndSet(true, false)) { committed.complete(Unit); awaitCancellation() }
            }
            override suspend fun recoverCancelledCompletion(id: String) = dao.recoverCancelledEventCompletion(id)
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L
            override fun elapsedRealtimeMillis() = 1_000L
        }
        val errors = mutableListOf<String>()
        val capture = EventCapture(scope, store, clock, { fail(it) }, onTerminalFailure = errors::add)
        try {
            val first = capture.start(car.id, "일반 이벤트", "first", "MOCK")
            val second = capture.start(car.id, "차량 이상", "second", "MOCK")
            val pending = scope.launch { capture.interruptAll() }
            withTimeout(10_000) { committed.await() }
            pending.cancel()
            withTimeout(10_000) { pending.join() }
            assertEquals("interrupted", dao.eventById(first)?.status)
            assertEquals("interrupted", dao.eventById(second)?.status)
            assertEquals(1, errors.size)
        } finally { scope.cancel() }
        Unit
    }

    @Test fun cancelledClockResetAfterCommittedInterruptionRecoversAllRoomEvents() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val committed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wall = java.util.concurrent.atomic.AtomicLong(100_000L)
        val blockOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = object : EventCaptureStore {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) = dao.startEvent(event, samples)
            override suspend fun append(samples: List<SampleEntity>) = dao.appendCollectingSamples(samples)
            override suspend fun finish(id: String, status: String) {
                dao.finishCollectingEvent(id, status)
                if (status == "interrupted" && blockOnce.compareAndSet(true, false)) {
                    committed.complete(Unit)
                    awaitCancellation()
                }
            }
            override suspend fun recoverCancelledCompletion(id: String) = dao.recoverCancelledEventCompletion(id)
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = wall.get()
            override fun elapsedRealtimeMillis() = 1_000L
        }
        val errors = mutableListOf<String>()
        val capture = EventCapture(scope, store, clock, { fail(it) }, onTerminalFailure = errors::add)
        try {
            val first = capture.start(car.id, "일반 이벤트", "first", "MOCK")
            val second = capture.start(car.id, "차량 이상", "second", "MOCK")
            capture.accept(Signal(100_000, "fuel", 50.0, "%"))
            wall.addAndGet(10_000)
            val job = scope.launch { capture.accept(Signal(wall.get(), "fuel", 51.0, "%")) }
            withTimeout(10_000) { committed.await() }
            job.cancel()
            withTimeout(10_000) { job.join() }
            assertEquals("interrupted", dao.eventById(first)?.status)
            assertEquals("interrupted", dao.eventById(second)?.status)
            assertEquals(1, dao.samplesOnce(first).size)
            assertEquals(1, dao.samplesOnce(second).size)
            assertEquals(1, errors.size)
            try { capture.start(car.id, "일반 이벤트", "later", "MOCK"); fail("capture must remain stopped") }
            catch (_: CaptureStoppedException) { }
        } finally { scope.cancel() }
        Unit
    }

    @Test fun cancelledCommittedAppendInterruptsRoomEvents() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val committed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = object : EventCaptureStore {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) = dao.startEvent(event, samples)
            override suspend fun append(samples: List<SampleEntity>) {
                dao.appendCollectingSamples(samples)
                committed.complete(Unit)
                awaitCancellation()
            }
            override suspend fun finish(id: String, status: String) = dao.finishCollectingEvent(id, status)
            override suspend fun recoverCancelledCompletion(id: String) = dao.recoverCancelledEventCompletion(id)
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L
            override fun elapsedRealtimeMillis() = 1_000L
        }
        val errors = mutableListOf<String>()
        val capture = EventCapture(scope, store, clock, { fail(it) }, onTerminalFailure = errors::add)
        try {
            val first = capture.start(car.id, "일반 이벤트", "first", "MOCK")
            val second = capture.start(car.id, "차량 이상", "second", "MOCK")
            val job = scope.launch { capture.accept(Signal(100_000, "fuel", 50.0, "%")) }
            withTimeout(10_000) { committed.await() }
            job.cancel()
            withTimeout(10_000) { job.join() }
            assertEquals("interrupted", dao.eventById(first)?.status)
            assertEquals("interrupted", dao.eventById(second)?.status)
            assertEquals(1, dao.samplesOnce(first).size)
            assertEquals(1, dao.samplesOnce(second).size)
            assertEquals(1, errors.size)
        } finally { scope.cancel() }
        Unit
    }

    @Test fun cancellingCaptureScopeDuringWindowPersistsInterruptedEvents() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L
            override fun elapsedRealtimeMillis() = 1_000L
        }
        val capture = EventCapture(scope, RoomEventCaptureStore(dao), clock, { fail(it) }, onTerminalFailure = {})
        try {
            val first = capture.start(car.id, "일반 이벤트", "first", "MOCK")
            val second = capture.start(car.id, "차량 이상", "second", "MOCK")
            capture.accept(Signal(100_000, "fuel", 50.0, "%"))
            job.cancel()
            withTimeout(10_000) { job.join() }
            assertEquals("interrupted", dao.eventById(first)?.status)
            assertEquals("interrupted", dao.eventById(second)?.status)
            assertEquals(1, dao.samplesOnce(first).size)
            assertEquals(1, dao.samplesOnce(second).size)
        } finally {
            job.cancel()
            withTimeout(10_000) { job.join() }
        }
        Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun fullEventWindowPersistsBoundariesAndRoundTripsThroughJson() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val captureScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 39_999L + scheduler.currentTime
            override fun elapsedRealtimeMillis() = scheduler.currentTime
        }
        val capture = EventCapture(captureScope, RoomEventCaptureStore(dao), clock, { fail(it) })
        try {
        capture.accept(Signal(39_999, "fuel", 60.0, "%"))
        scheduler.advanceTimeBy(1)
        capture.accept(Signal(40_000, "fuel", 59.0, "%"))
        scheduler.advanceTimeBy(60_000)
        val id = capture.start(car.id, "일반 이벤트", "window", "MOCK")
        scheduler.runCurrent()
        capture.accept(Signal(100_000, "fuel", 50.0, "%"))
        scheduler.advanceTimeBy(59_999)
        capture.accept(Signal(159_999, "fuel", 40.0, "%"))
        scheduler.advanceTimeBy(1)
        // This sample is accepted before the scheduled completion acquires the capture lock.
        capture.accept(Signal(160_000, "fuel", 39.0, "%"))
        scheduler.runCurrent()
        withTimeout(10_000) { while (dao.eventById(id)?.status == "collecting") { scheduler.runCurrent(); kotlinx.coroutines.delay(10) } }
        val event = requireNotNull(dao.eventById(id))
        assertEquals("complete", event.status)
        val samples = dao.samplesOnce(id)
        assertEquals(listOf(40_000L, 100_000L, 159_999L, 160_000L), samples.map { it.timestamp })
        assertEquals(50.0, eventSnapshot(samples, event.timestamp).getValue("fuel").value, 0.0)
        assertEquals(ImportedEvent(event, samples), parseEventJson(exportEventJson(event, samples)))
        } finally { captureScope.cancel() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun completedCaptureAndWindowSurviveFileDatabaseReopen() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val name = "capture-reopen-${java.util.UUID.randomUUID()}.db"
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 40_000L + scheduler.currentTime
            override fun elapsedRealtimeMillis() = scheduler.currentTime
        }
        var db: CarlogDatabase? = null
        try {
            val original = Room.databaseBuilder(app, CarlogDatabase::class.java, name).build()
            db = original
            val car = VehicleEntity(name = "reopen test")
            original.dao().insertVehicle(car)
            val capture = EventCapture(scope, RoomEventCaptureStore(original.dao()), clock, { fail(it) })
            capture.accept(Signal(40_000, "fuel", 60.0, "%"))
            scheduler.advanceTimeBy(60_000)
            val id = capture.start(car.id, "일반 이벤트", "reopen", "MOCK")
            scheduler.runCurrent()
            capture.accept(Signal(100_000, "fuel", 50.0, "%"))
            scheduler.advanceTimeBy(59_999)
            capture.accept(Signal(159_999, "fuel", 40.0, "%"))
            scheduler.advanceTimeBy(1)
            scheduler.runCurrent()
            withTimeout(10_000) { while (original.dao().eventById(id)?.status == "collecting") { scheduler.runCurrent(); kotlinx.coroutines.delay(10) } }
            val event = requireNotNull(original.dao().eventById(id))
            assertEquals("complete", event.status)
            val expected = original.dao().samplesOnce(id)
            assertEquals(listOf(40_000L, 100_000L, 159_999L), expected.map { it.timestamp })
            scope.cancel()
            original.close()
            db = null
            val reopened = Room.databaseBuilder(app, CarlogDatabase::class.java, name).build()
            db = reopened
            assertEquals(event, reopened.dao().eventById(id))
            assertEquals(expected, reopened.dao().samplesOnce(id))
        } finally {
            scope.cancel()
            db?.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun v2ImportPreservesProvenanceAndDetectsConflicts() = runBlocking {
        val car = VehicleEntity(name = "차량")
        dao.insertVehicle(car)
        val event = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "VEHICLE", status = "complete")
        val received = SampleEntity(event.id, 100_000, "odometer", 123.0, "km", "RECEIVED")
        val imported = parseEventJson(exportEventJson(event, listOf(received)))
        assertEquals(EventImportResult.ADDED, dao.importEvent(imported))
        assertEquals(listOf(received), dao.samplesOnce(event.id))
        assertEquals(EventImportResult.DUPLICATE, dao.importEvent(imported))
        val changed = imported.copy(samples = listOf(received.copy(timestampKind = "MEASURED")))
        assertEquals(EventImportResult.CONFLICT, dao.importEvent(changed))
        assertEquals(listOf(received), dao.samplesOnce(event.id))
    }

    @Test fun failedVehicleMappingRetainsImportForRetry() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val file = java.io.File.createTempFile("carlog-map-retry-", ".json", app.cacheDir)
        val sourceCar = VehicleEntity(name = "source")
        val event = EventEntity(vehicleId = sourceCar.id, timestamp = 100_000, category = "일반 이벤트", memo = "memo", source = "MOCK", status = "complete")
        val samples = listOf(SampleEntity(event.id, 100_000, "fuel", 50.0, "%"))
        file.writeText(exportEventJson(event, samples), Charsets.UTF_8)
        val flakyDao = object : CarlogDao by dao {
            var mappedAttempts = 0
            override suspend fun importEvent(value: ImportedEvent, mappedVehicleId: String?): EventImportResult {
                if (mappedVehicleId != null && mappedAttempts++ == 0) throw IllegalStateException("temporary write failure")
                return dao.importEvent(value, mappedVehicleId)
            }
        }
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, flakyDao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.importEventFile(Uri.fromFile(file)) }
            withTimeout(10_000) { vm.pendingImport.filterNotNull().first() }
            withTimeout(10_000) { vm.importing.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.importToCurrentVehicle() }
            withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it.contains("다시 시도") } }
            withTimeout(10_000) { vm.importing.first { !it } }
            assertNotNull(vm.pendingImport.value)
            assertNull(dao.eventById(event.id))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.importToCurrentVehicle() }
            withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it == "이벤트를 가져왔습니다." } }
            assertNull(vm.pendingImport.value)
            assertEquals(sourceCar.id, dao.eventById(event.id)?.originalVehicleId)
            assertEquals(samples, dao.samplesOnce(event.id))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            file.delete()
        }
        Unit
    }

    @Test fun pendingEventExportCannotBeReplacedByAnotherExport() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val car = VehicleEntity(name = "차량")
        val first = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "일반 이벤트", memo = "first", source = "MOCK", status = "complete")
        val second = first.copy(id = java.util.UUID.randomUUID().toString(), memo = "second")
        dao.insertVehicle(car); dao.insertEvent(first); dao.insertEvent(second)
        val output = java.io.ByteArrayOutputStream()
        var cleanupJob: Job? = null
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) { output } as T
            })[CarlogViewModel::class.java]
            cleanupJob = vm.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            val ready = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.prepareEventExport(first.id) { ready.complete(Unit) } }
            withTimeout(10_000) { ready.await() }
            var secondLaunched = false
            var vehicleLaunched = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.prepareEventExport(second.id) { secondLaunched = true }
                vm.prepareVehicleExport { vehicleLaunched = true }
                assertFalse(vm.beginShare())
            }
            assertFalse(secondLaunched)
            assertFalse(vehicleLaunched)
            assertTrue(vm.exchangeMessage.value.orEmpty().contains("다른 저장"))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.finishEventExport(Uri.parse("content://test/event")) }
            withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it == "이벤트 JSON 파일을 저장했습니다." } }
            assertEquals(first.id, parseEventJson(output.toString("UTF-8")).event.id)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            cleanupJob?.let { withTimeout(10_000) { it.join() } }
        }
        Unit
    }

    @Test fun jsonExportPickerFailureAndCancellationDoNotBlockVehicleSwitch() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val current = VehicleEntity(name = "현재")
        val target = VehicleEntity(name = "다음")
        val event = EventEntity(vehicleId = current.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
        dao.insertVehicle(current); dao.insertEvent(event)
        var cleanupJob: Job? = null
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            cleanupJob = vm.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { vm.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setMockCollection(false) }
            withTimeout(10_000) { vm.captureTransitioning.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.prepareEventExport(event.id) { throw IllegalStateException("no picker") } }
            withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it.contains("선택기를 열지 못했습니다") } }
            val eventReady = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.prepareEventExport(event.id) { eventReady.complete(Unit) } }
            withTimeout(10_000) { eventReady.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.finishEventExport(null) }
            assertTrue(vm.exchangeMessage.value.orEmpty().contains("취소되었거나"))
            val vehicleReady = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.prepareVehicleExport { vehicleReady.complete(Unit) } }
            withTimeout(10_000) { vehicleReady.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.finishVehicleExport(null) }
            assertTrue(vm.vehicleExportMessage.value.orEmpty().contains("취소되었거나"))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectVehicle(target) }
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first { it.id == target.id } }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            cleanupJob?.let { withTimeout(10_000) { it.join() } }
        }
        Unit
    }

    @Test fun vehicleSwitchWaitsForBothVehicleAndEventFileWrites() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val current = VehicleEntity(name = "현재")
        val target = VehicleEntity(name = "다음")
        val event = EventEntity(vehicleId = current.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
        dao.insertVehicle(current); dao.insertEvent(event)
        val vehicleStarted = CompletableDeferred<Unit>(); val eventStarted = CompletableDeferred<Unit>()
        val vehicleRelease = java.util.concurrent.CountDownLatch(1); val eventRelease = java.util.concurrent.CountDownLatch(1)
        fun sink(started: CompletableDeferred<Unit>, release: java.util.concurrent.CountDownLatch) = object : java.io.ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                started.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                super.write(bytes, offset, length)
            }
        }
        val vehicleSink = sink(vehicleStarted, vehicleRelease); val eventSink = sink(eventStarted, eventRelease)
        var cleanupJob: Job? = null
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) { uri ->
                    if (uri.lastPathSegment == "vehicle") vehicleSink else eventSink
                } as T
            })[CarlogViewModel::class.java]
            cleanupJob = vm.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { vm.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setMockCollection(false) }
            withTimeout(10_000) { vm.captureTransitioning.first { !it } }
            val vehicleReady = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.prepareVehicleExport { vehicleReady.complete(Unit) } }
            withTimeout(10_000) { vehicleReady.await() }
            withTimeout(10_000) { vm.vehicleExportPreparing.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.finishVehicleExport(Uri.parse("content://test/vehicle")) }
            withTimeout(10_000) { vehicleStarted.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectVehicle(target) }
            assertEquals(current.id, vm.vehicleDetails.value?.id)
            assertTrue(vm.vehicleExportMessage.value.orEmpty().contains("저장·공유"))
            vehicleRelease.countDown()
            withTimeout(10_000) { vm.vehicleExportMessage.filterNotNull().first { it == "차량 관리 기록 JSON을 저장했습니다." } }
            val eventReady = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.prepareEventExport(event.id) { eventReady.complete(Unit) } }
            withTimeout(10_000) { eventReady.await() }
            withTimeout(10_000) { vm.exportPreparing.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.finishEventExport(Uri.parse("content://test/event")) }
            withTimeout(10_000) { eventStarted.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectVehicle(target) }
            assertEquals(current.id, vm.vehicleDetails.value?.id)
            assertTrue(vm.vehicleExportMessage.value.orEmpty().contains("저장·공유"))
            eventRelease.countDown()
            withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it == "이벤트 JSON 파일을 저장했습니다." } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectVehicle(target) }
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first { it.id == target.id } }
            assertEquals(current.id, parseVehicleManagementJson(vehicleSink.toString("UTF-8")).vehicle.id)
            assertEquals(current.id, parseEventJson(eventSink.toString("UTF-8")).event.vehicleId)
        } finally {
            vehicleRelease.countDown(); eventRelease.countDown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            cleanupJob?.let { job -> withTimeout(10_000) { job.join() } }
        }
        Unit
    }

    @Test fun eventImportedForAnotherKnownVehicleNamesItsDestination() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val file = java.io.File.createTempFile("carlog-other-vehicle-", ".json", app.cacheDir)
        val current = VehicleEntity(name = "현재 차량")
        val destination = VehicleEntity(name = "기록 차량")
        val event = EventEntity(vehicleId = destination.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
        dao.insertVehicle(current); dao.insertVehicle(destination)
        dao.setActiveVehicle(ActiveVehicleEntity(vehicleId = current.id))
        file.writeText(exportEventJson(event, emptyList()), Charsets.UTF_8)
        var cleanupJob: Job? = null
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            cleanupJob = vm.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first { it.id == current.id } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.importEventFile(Uri.fromFile(file)) }
            val message = withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first() }
            assertTrue(message.contains(destination.name))
            assertTrue(message.contains(destination.id))
            assertEquals(destination.id, dao.eventById(event.id)?.vehicleId)
            assertEquals(current.id, vm.vehicleDetails.value?.id)
            withTimeout(10_000) { vm.importing.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.importEventFile(Uri.fromFile(file)) }
            val duplicate = withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it.contains("이미 같은") } }
            assertTrue(duplicate.contains(destination.id))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            cleanupJob?.let { job -> withTimeout(10_000) { job.join() } }
            file.delete()
        }
        Unit
    }

    @Test fun restoreVehicleEventAndLinkedRepairRoundTripsWithoutChangingExistingCar() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val vehicleFile = java.io.File.createTempFile("carlog-restore-vehicle-", ".json", app.cacheDir)
        val eventFile = java.io.File.createTempFile("carlog-restore-event-", ".json", app.cacheDir)
        val original = VehicleEntity(name = "기존")
        val restored = VehicleEntity(name = "원본 차량")
        val event = EventEntity(vehicleId = restored.id, timestamp = 100_000, category = "차량 이상", memo = "소음", source = "MOCK", status = "complete")
        val sample = SampleEntity(event.id, 100_000, "fuel", 50.0, "%", "RECEIVED")
        val repair = RecordEntity(vehicleId = restored.id, type = "정비", timestamp = 101_000, odometer = 1234.0,
            amount = 0.0, cost = 30000, label = "점검", relatedEventId = event.id, repairOutcome = "확인")
        dao.insertVehicle(original)
        vehicleFile.writeText(exportVehicleManagementJson(VehicleManagementExportData(restored, listOf(repair), emptyList()), 200_000), Charsets.UTF_8)
        eventFile.writeText(exportEventJson(event, listOf(sample)), Charsets.UTF_8)
        lateinit var vm: CarlogViewModel
        var cleanupJob: Job? = null
        try {
            vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            cleanupJob = vm.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { vm.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setMockCollection(false) }
            withTimeout(10_000) { vm.captureTransitioning.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.previewVehicleImport(Uri.fromFile(vehicleFile)) }
            withTimeout(10_000) { vm.vehicleImportPreview.filterNotNull().first() }
            withTimeout(10_000) { vm.vehicleImportBusy.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectVehicle(restored) }
            withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first { it.id == restored.id } }
            withTimeout(10_000) { vm.vehicleSwitching.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.applyVehicleImport() }
            withTimeout(10_000) { vm.vehicleExportMessage.filterNotNull().first { it.contains(event.id) } }
            withTimeout(10_000) { vm.vehicleImportBusy.first { !it } }
            assertNull(dao.recordById(repair.id))
            assertNotNull(vm.vehicleImportPreview.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.importEventFile(Uri.fromFile(eventFile)) }
            withTimeout(10_000) { vm.exchangeMessage.filterNotNull().first { it == "이벤트를 가져왔습니다." } }
            withTimeout(10_000) { vm.importing.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.applyVehicleImport() }
            withTimeout(10_000) { vm.vehicleExportMessage.filterNotNull().first { it.contains("추가 1건") } }
            assertEquals(repair, dao.recordById(repair.id))
            assertEquals(event, dao.eventById(event.id))
            assertEquals(listOf(sample), dao.samplesOnce(event.id))
            val exported = parseVehicleManagementJson(exportVehicleManagementJson(requireNotNull(dao.vehicleManagementExport(restored.id)), 200_000))
            assertEquals(restored.id, exported.vehicle.id)
            assertEquals(event.id, exported.records.single().relatedEventId)
            assertEquals(original, dao.vehicleById(original.id))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            cleanupJob?.let { job -> withTimeout(10_000) { job.join() } }
            vehicleFile.delete(); eventFile.delete()
        }
        Unit
    }

    @Test fun screenOpenedDuringVehicleSwitchWaitsForCommittedSelection() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val firstStore = ViewModelStore(); val secondStore = ViewModelStore()
        val started = CompletableDeferred<Unit>(); val allowCommit = CompletableDeferred<Unit>()
        val slowDao = object : CarlogDao by dao {
            override suspend fun createOrSelectVehicle(value: VehicleEntity): VehicleSelectionResult {
                started.complete(Unit)
                allowCommit.await()
                return dao.createOrSelectVehicle(value)
            }
        }
        fun factory(selectedDao: CarlogDao) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, selectedDao, MockVehicleDataSource()) as T
        }
        var firstJob: Job? = null; var secondJob: Job? = null
        try {
            val first = ViewModelProvider(firstStore, factory(slowDao))[CarlogViewModel::class.java]
            firstJob = first.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { first.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.setMockCollection(false) }
            withTimeout(10_000) { first.captureTransitioning.first { !it } }
            val target = VehicleEntity(name = "전환 차량")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.selectVehicle(target) }
            withTimeout(10_000) { started.await() }
            val second = ViewModelProvider(secondStore, factory(dao))[CarlogViewModel::class.java]
            secondJob = second.viewModelScope.coroutineContext[Job]
            assertTrue(second.captureTransitioning.value)
            assertNull(second.vehicleDetails.value)
            allowCommit.complete(Unit)
            withTimeout(10_000) { first.vehicleDetails.filterNotNull().first { it.id == target.id } }
            withTimeout(10_000) { second.captureTransitioning.first { !it } }
            assertEquals(target.id, second.vehicleDetails.value?.id)
            assertEquals(target.id, dao.vehicleForStartup()?.id)
            assertFalse(second.captureReady.value)
        } finally {
            allowCommit.complete(Unit)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear(); secondStore.clear() }
            firstJob?.let { withTimeout(10_000) { it.join() } }
            secondJob?.let { withTimeout(10_000) { it.join() } }
        }
        Unit
    }

    @Test fun cancelledVehicleSwitchUnblocksAnotherScreenWithoutChangingSelection() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val firstStore = ViewModelStore(); val secondStore = ViewModelStore()
        val started = CompletableDeferred<Unit>()
        val slowDao = object : CarlogDao by dao {
            override suspend fun createOrSelectVehicle(value: VehicleEntity): VehicleSelectionResult {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        fun factory(selectedDao: CarlogDao) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, selectedDao, MockVehicleDataSource()) as T
        }
        var firstJob: Job? = null; var secondJob: Job? = null
        try {
            val first = ViewModelProvider(firstStore, factory(slowDao))[CarlogViewModel::class.java]
            firstJob = first.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { first.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.setMockCollection(false) }
            withTimeout(10_000) { first.captureTransitioning.first { !it } }
            val original = requireNotNull(first.vehicleDetails.value)
            val target = VehicleEntity(name = "취소 차량")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.selectVehicle(target) }
            withTimeout(10_000) { started.await() }
            val second = ViewModelProvider(secondStore, factory(dao))[CarlogViewModel::class.java]
            secondJob = second.viewModelScope.coroutineContext[Job]
            assertTrue(second.captureTransitioning.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear() }
            firstJob?.let { withTimeout(10_000) { it.join() } }
            withTimeout(10_000) { second.captureTransitioning.first { !it } }
            assertEquals(original.id, second.vehicleDetails.value?.id)
            assertNull(dao.vehicleById(target.id))
            assertEquals(original.id, dao.vehicleForStartup()?.id)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear(); secondStore.clear() }
            firstJob?.let { withTimeout(10_000) { it.join() } }
            secondJob?.let { withTimeout(10_000) { it.join() } }
        }
        Unit
    }

    @Test fun vehicleSwitchRequiresExclusiveScreenAndNoCollectingEvent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val firstStore = ViewModelStore(); val secondStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
        }
        try {
            val first = ViewModelProvider(firstStore, factory)[CarlogViewModel::class.java]
            withTimeout(10_000) { first.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.setMockCollection(false) }
            withTimeout(10_000) { first.captureTransitioning.first { !it } }
            val old = requireNotNull(first.vehicleDetails.value)
            val target = VehicleEntity(name = "대상")
            val second = ViewModelProvider(secondStore, factory)[CarlogViewModel::class.java]
            withTimeout(10_000) { second.captureTransitioning.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.selectVehicle(target) }
            assertEquals(old, first.vehicleDetails.value)
            assertTrue(first.vehicleExportMessage.value.orEmpty().contains("다른 차량 화면"))
            assertNull(dao.vehicleById(target.id))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { secondStore.clear() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(first.beginShare())
                assertFalse(first.beginShare())
                first.selectVehicle(target)
            }
            assertEquals(old, first.vehicleDetails.value)
            assertTrue(first.vehicleExportMessage.value.orEmpty().contains("저장·공유"))
            assertNull(dao.vehicleById(target.id))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.endShare() }
            val event = EventEntity(vehicleId = old.id, timestamp = 100, category = "일반 이벤트", memo = "", source = "MOCK", status = "collecting")
            dao.insertEvent(event)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.selectVehicle(target) }
            withTimeout(10_000) { first.vehicleExportMessage.filterNotNull().first { it.contains("완료되지 않은 이벤트") } }
            assertNull(dao.vehicleById(target.id))
            dao.finishCollectingEvent(event.id, "interrupted")
            withTimeout(10_000) { first.vehicleSwitching.first { !it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.selectVehicle(target) }
            withTimeout(10_000) { first.vehicleDetails.filterNotNull().first { it.id == target.id } }
            assertEquals(target, dao.vehicleForStartup())
            assertEquals(old, dao.vehicleById(old.id))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear(); secondStore.clear() }
        }
        Unit
    }

    @Test fun closingScreenDuringDelayedStartDoesNotInterruptNewScreenEvent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val firstStore = ViewModelStore(); val secondStore = ViewModelStore()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val oldId = CompletableDeferred<String>()
        val delayedDao = object : CarlogDao by dao {
            override suspend fun startEvent(event: EventEntity, samples: List<SampleEntity>) {
                if (event.memo == "old-delayed") {
                    oldId.complete(event.id)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                        dao.startEvent(event, samples)
                    }
                } else dao.startEvent(event, samples)
            }
        }
        fun factory(target: CarlogDao) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, target, MockVehicleDataSource()) as T
        }
        var firstJob: Job? = null; var secondJob: Job? = null
        try {
            val first = ViewModelProvider(firstStore, factory(delayedDao))[CarlogViewModel::class.java]
            firstJob = first.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { first.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.mark("일반 이벤트", "old-delayed", {}) }
            withTimeout(10_000) { entered.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear() }
            val second = ViewModelProvider(secondStore, factory(dao))[CarlogViewModel::class.java]
            secondJob = second.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { second.captureReady.first { it } }
            val saved = CompletableDeferred<Unit>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { second.mark("차량 이상", "new-active") { saved.complete(Unit) } }
            withTimeout(10_000) { saved.await() }
            val current = withTimeout(10_000) { second.events.first { list -> list.any { it.memo == "new-active" } }.first { it.memo == "new-active" } }
            release.complete(Unit)
            firstJob?.let { withTimeout(10_000) { it.join() } }
            assertEquals("interrupted", dao.eventById(oldId.await())?.status)
            assertEquals("collecting", dao.eventById(current.id)?.status)
        } finally {
            release.complete(Unit)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear(); secondStore.clear() }
            firstJob?.let { withTimeout(10_000) { it.join() } }
            secondJob?.let { withTimeout(10_000) { it.join() } }
        }
        Unit
    }

    @Test fun closingScreenInterruptsEventBeforeAnotherScreenStarts() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val saved = CompletableDeferred<Unit>()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
        }
        try {
            val vm = ViewModelProvider(store, factory)[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.mark("일반 이벤트", "close-screen", { saved.complete(Unit) }) }
            withTimeout(10_000) { saved.await() }
            val event = withTimeout(10_000) { vm.events.first { it.isNotEmpty() }.single() }
            assertEquals("collecting", dao.eventById(event.id)?.status)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            withTimeout(10_000) {
                while (dao.eventById(event.id)?.status == "collecting") kotlinx.coroutines.delay(10)
            }
            assertEquals("interrupted", dao.eventById(event.id)?.status)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        }
        Unit
    }

    @Test fun secondScreenDoesNotInterruptAnActiveCapture() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val firstStore = ViewModelStore()
        val secondStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
        }
        try {
            val first = ViewModelProvider(firstStore, factory)[CarlogViewModel::class.java]
            withTimeout(10_000) { first.captureReady.first { it } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { first.mark("일반 이벤트", "active", {}) }
            val event = withTimeout(10_000) { first.events.first { it.isNotEmpty() }.single() }
            val second = ViewModelProvider(secondStore, factory)[CarlogViewModel::class.java]
            withTimeout(10_000) { second.captureTransitioning.first { !it } }
            assertFalse(second.captureReady.value)
            assertTrue(second.captureError.value.orEmpty().contains("다른 화면"))
            assertEquals(first.vehicleDetails.value?.id, second.vehicleDetails.value?.id)
            withTimeout(10_000) { second.events.first { events -> events.any { it.id == event.id } } }
            assertEquals("collecting", dao.eventById(event.id)?.status)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { second.setMockCollection(true) }
            withTimeout(10_000) { second.captureReady.first { it } }
            assertEquals("interrupted", dao.eventById(event.id)?.status)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear(); secondStore.clear() }
        }
        Unit
    }

    @Test fun recoveredEventRejectsLateSamplesAndCannotBecomeComplete() = runBlocking {
        val car = VehicleEntity(name = "test")
        dao.insertVehicle(car)
        val event = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "일반 이벤트", memo = "",
            source = "MOCK", status = "collecting")
        val before = SampleEntity(event.id, 99_000, "fuel", 50.0, "%")
        val after = SampleEntity(event.id, 101_000, "fuel", 49.0, "%")
        val store = RoomEventCaptureStore(dao)
        store.start(event, listOf(before))
        dao.interruptPending()
        try { store.append(listOf(after)); fail("interrupted event must reject late samples") }
        catch (_: IllegalStateException) { }
        try { store.finish(event.id, "complete"); fail("interrupted event must not become complete") }
        catch (_: IllegalStateException) { }
        store.finish(event.id, "interrupted") // Same terminal status is idempotent.
        assertEquals("interrupted", dao.eventById(event.id)?.status)
        assertEquals(listOf(before), dao.samplesOnce(event.id))
    }

    @Test fun startupDatabaseFailureCanBeRetriedWithoutRestartingApp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val vehicle = VehicleEntity(name = "existing")
        dao.insertVehicle(vehicle)
        val interrupted = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "일반 이벤트", memo = "pending", source = "MOCK", status = "collecting")
        dao.insertEvent(interrupted)
        val flakyDao = object : CarlogDao by dao {
            var attempts = 0
            override suspend fun vehicleForStartup(): VehicleEntity? {
                if (attempts++ == 0) throw IllegalStateException("temporary read failure")
                return dao.vehicleForStartup()
            }
        }
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, flakyDao, MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.captureTransitioning.first { !it } }
            assertNull(vm.vehicleDetails.value)
            assertFalse(vm.captureReady.value)
            assertNotNull(vm.captureError.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setMockCollection(true) }
            withTimeout(10_000) { vm.captureReady.first { it } }
            assertEquals(vehicle.id, vm.vehicleDetails.value?.id)
            assertNull(vm.captureError.value)
            assertEquals("interrupted", dao.eventById(interrupted.id)?.status)
            withTimeout(10_000) { vm.latest.first { it.isNotEmpty() } }
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() } }
        Unit
    }

    @Test fun stoppingAndResumingInterruptsEventAndUsesFreshBuffer() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val signals = MutableSharedFlow<Signal>()
        val source = object : VehicleDataSource {
            override val source = "MOCK"
            override fun signals() = signals
        }
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, source) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { signals.subscriptionCount.first { it == 1 } }
            signals.emit(Signal(System.currentTimeMillis(), "old_fuel", 50.0, "%"))
            withTimeout(10_000) { vm.latest.first { it.containsKey("old_fuel") } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.mark("일반 이벤트", "before", {}) }
            val first = withTimeout(10_000) { vm.events.first { it.any { event -> event.memo == "before" } }.first { it.memo == "before" } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setMockCollection(false) }
            withTimeout(10_000) { vm.captureTransitioning.first { !it } }
            assertFalse(vm.captureReady.value)
            assertEquals(0, signals.subscriptionCount.value)
            assertTrue(vm.latest.value.isEmpty())
            assertEquals("interrupted", dao.eventById(first.id)?.status)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setMockCollection(true) }
            withTimeout(10_000) { signals.subscriptionCount.first { it == 1 } }
            signals.emit(Signal(System.currentTimeMillis(), "new_fuel", 40.0, "%"))
            withTimeout(10_000) { vm.latest.first { it.containsKey("new_fuel") } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.mark("일반 이벤트", "after", {}) }
            val second = withTimeout(10_000) { vm.events.first { it.any { event -> event.memo == "after" } }.first { it.memo == "after" } }
            assertEquals(listOf("new_fuel"), dao.samplesOnce(second.id).map { it.name })
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setMockCollection(false) }
            withTimeout(10_000) { vm.captureTransitioning.first { !it } }
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() } }
        Unit
    }

    @Test fun preEventAgeContinuesAdvancingWhenSourceStopsEmitting() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val source = object : VehicleDataSource {
            override val source = "MOCK"
            override fun signals() = flow {
                emit(Signal(System.currentTimeMillis(), "fuel", 50.0, "%"))
                awaitCancellation()
            }
        }
        try {
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CarlogViewModel(app, dao, source) as T
            })[CarlogViewModel::class.java]
            val age = withTimeout(6_000) { vm.oldestPreEventSampleAge.filterNotNull().first { it >= 1_000 } }
            assertTrue(age in 1_000..60_000)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        }
    }

    @Test fun startupRecoversPersistedCollectingEventWithoutLosingSamples() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val name = "recovery-${java.util.UUID.randomUUID()}.db"
        val vehicle = VehicleEntity(name = "recovery test")
        val collecting = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "차량 이상", memo = "진행 중", source = "MOCK", status = "collecting")
        val complete = EventEntity(vehicleId = vehicle.id, timestamp = 200_000, category = "일반 이벤트", memo = "완료", source = "MOCK", status = "complete")
        val samples = listOf(SampleEntity(collecting.id, 99_000, "fuel", 50.0, "%"), SampleEntity(collecting.id, 101_000, "odometer", 123.0, "km"))
        val store = ViewModelStore()
        var reopened: CarlogDatabase? = null
        try {
            val original = Room.databaseBuilder(app, CarlogDatabase::class.java, name).build()
            try {
                original.dao().insertVehicle(vehicle)
                original.dao().startEvent(collecting, samples)
                original.dao().insertEvent(complete)
            } finally { original.close() }
            val restored = Room.databaseBuilder(app, CarlogDatabase::class.java, name).build()
            reopened = restored
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CarlogViewModel(app, restored.dao(), MockVehicleDataSource()) as T
            })[CarlogViewModel::class.java]
            withTimeout(10_000) { vm.captureReady.first { it } }
            assertEquals("interrupted", restored.dao().eventById(collecting.id)?.status)
            assertEquals(samples, restored.dao().samplesOnce(collecting.id))
            assertEquals(complete, restored.dao().eventById(complete.id))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            reopened?.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun standaloneMileageCanBeStoredEditedAndSoftDeleted() = runBlocking {
        val vehicle = VehicleEntity(name = "test")
        dao.insertVehicle(vehicle)
        val reading = RecordEntity(vehicleId = vehicle.id, type = "주행거리", timestamp = 100_000, odometer = 10_000.0, amount = 0.0, cost = 0, label = "주행거리")
        dao.insertRecord(reading)
        assertFalse(dao.replaceActiveRecord(reading.copy(type = "지출", cost = 100)))
        assertEquals(reading, dao.activeRecord(reading.id))
        assertTrue(dao.replaceActiveRecord(reading.copy(odometer = 10_500.0)))
        val records = dao.records(vehicle.id).first()
        assertEquals(10_500.0, maintenanceOdometer(0.0, records, null, false), 0.0)
        assertEquals(java.math.BigInteger.ZERO, spendingStats(records).total)
        assertEquals(0, dao.softDeleteRecord(reading.id, "other-vehicle", 190_000))
        assertNotNull(dao.activeRecord(reading.id))
        assertEquals(1, dao.softDeleteRecord(reading.id, vehicle.id, 200_000))
        assertTrue(dao.records(vehicle.id).first().isEmpty())
        assertFalse(dao.replaceActiveRecord(reading))
    }

    @Test fun importIsAtomicAndDoesNotOverwriteConflicts() = runBlocking {
        val vehicle = VehicleEntity(name = "test")
        dao.insertVehicle(vehicle)
        val event = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "일반 이벤트", memo = "원본", source = "MOCK", status = "complete")
        val samples = listOf(SampleEntity(event.id, 100_000, "fuel", 50.0, "%"))
        val value = ImportedEvent(event, samples)
        assertEquals(EventImportResult.ADDED, dao.importEvent(value))
        assertEquals(EventImportResult.DUPLICATE, dao.importEvent(value))
        assertEquals(EventImportResult.CONFLICT, dao.importEvent(value.copy(event = event.copy(memo = "다른 내용"))))
        assertEquals(EventImportResult.VEHICLE_NOT_FOUND, dao.importEvent(value.copy(event = event.copy(vehicleId = "missing"))))
        assertEquals(event, dao.eventById(event.id))
        assertEquals(samples, dao.samplesOnce(event.id))
    }

    @Test fun eventFileTransfersBetweenDatabasesWithoutOverwritingDuplicatesOrConflicts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destination = Room.inMemoryDatabaseBuilder(context, CarlogDatabase::class.java).build()
        val file = java.io.File.createTempFile("carlog-event-test-", ".json", context.cacheDir)
        try {
            val originCar = VehicleEntity(name = "origin")
            val destinationCar = VehicleEntity(name = "destination")
            dao.insertVehicle(originCar)
            destination.dao().insertVehicle(destinationCar)
            val event = EventEntity(vehicleId = originCar.id, timestamp = 100_000, category = "차량 이상", memo = "시험", source = "MOCK", status = "complete")
            val signals = listOf(SampleEntity(event.id, 99_000, "fuel", 45.0, "%"), SampleEntity(event.id, 101_000, "odometer", 10_000.0, "km"))
            dao.startEvent(event, signals)
            val snapshot = requireNotNull(dao.eventExportData(event.id))
            file.writeText(exportEventJson(snapshot.event, snapshot.samples), Charsets.UTF_8)
            val imported = parseEventJson(file.readText(Charsets.UTF_8))
            assertEquals(EventImportResult.VEHICLE_NOT_FOUND, destination.dao().importEvent(imported))
            assertEquals(EventImportResult.ADDED, destination.dao().importEvent(imported, destinationCar.id))
            assertEquals(EventImportResult.DUPLICATE, destination.dao().importEvent(imported, destinationCar.id))
            assertEquals(EventImportResult.CONFLICT, destination.dao().importEvent(imported.copy(event = imported.event.copy(memo = "수정됨")), destinationCar.id))
            val saved = requireNotNull(destination.dao().eventExportData(event.id))
            assertEquals(destinationCar.id, saved.event.vehicleId)
            assertEquals(originCar.id, saved.event.originalVehicleId)
            assertEquals("시험", saved.event.memo)
            assertEquals(signals, saved.samples)
            assertEquals(imported, parseEventJson(exportEventJson(saved.event, saved.samples)))
        } finally {
            file.delete()
            destination.close()
        }
    }

    @Test fun androidExchangeRejectsUnpairedSurrogatesAndPreservesValidEmoji() {
        val event = EventEntity(vehicleId = java.util.UUID.randomUUID().toString(), timestamp = 100_000,
            category = "일반 이벤트", memo = "차량 🚗", source = "MOCK", status = "complete")
        assertEquals(event.memo, parseEventJson(exportEventJson(event, emptyList())).event.memo)
        try { exportEventJson(event.copy(memo = "broken \uD800"), emptyList()); fail("invalid Unicode") }
        catch (_: IllegalArgumentException) { }
        val malformed = exportEventJson(event, emptyList()).replace("차량 🚗", "\\uD800")
        try { parseEventJson(malformed); fail("invalid Unicode") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun androidImportRejectsDuplicateJsonFieldsBeforeWriting() = runBlocking {
        val vehicle = VehicleEntity(name = "test")
        dao.insertVehicle(vehicle)
        val event = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
        val json = exportEventJson(event, emptyList()).replaceFirst("{", "{\"id\":\"${event.id}\",")
        try { dao.importEvent(parseEventJson(json)); fail("duplicate field should be rejected") }
        catch (_: IllegalArgumentException) { }
        assertNull(dao.eventById(event.id))
    }

    @Test fun exportSnapshotUsesSavedStatusAndAllSamplesForTheRequestedEvent() = runBlocking {
        val vehicle = VehicleEntity(name = "test")
        dao.insertVehicle(vehicle)
        val event = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "collecting")
        val other = event.copy(id = java.util.UUID.randomUUID().toString(), status = "complete")
        dao.insertEvent(event); dao.insertEvent(other)
        val before = SampleEntity(event.id, 99_000, "fuel", 50.0, "%")
        val after = SampleEntity(event.id, 101_000, "fuel", 49.0, "%")
        dao.insertSamples(listOf(before, SampleEntity(other.id, 100_000, "fuel", 1.0, "%")))
        assertNull(dao.eventExportData(event.id))
        dao.insertSamples(listOf(after)); dao.setStatus(event.id, "complete")
        val snapshot = dao.eventExportData(event.id)!!
        assertEquals("complete", snapshot.event.status)
        assertEquals(listOf(before, after), snapshot.samples)
        assertEquals(snapshot, parseEventJson(exportEventJson(snapshot.event, snapshot.samples)).let { EventExportData(it.event, it.samples) })
        assertNull(dao.eventExportData("missing"))
    }

    @Test fun duplicateSamplesAreIdempotentButConflictingValuesRollBackTheBatch() = runBlocking {
        val car = VehicleEntity(name = "test"); dao.insertVehicle(car)
        val event = EventEntity(vehicleId = car.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "collecting")
        val existing = SampleEntity(event.id, 99_000, "fuel", 50.0, "%")
        dao.startEvent(event, listOf(existing))
        dao.insertSamples(listOf(existing, existing))
        assertEquals(listOf(existing), dao.samplesOnce(event.id))
        val next = SampleEntity(event.id, 101_000, "fuel", 49.0, "%")
        try { dao.insertSamples(listOf(next, existing.copy(value = 10.0))); fail("conflict should fail") }
        catch (_: IllegalStateException) { }
        assertEquals(listOf(existing), dao.samplesOnce(event.id))
        try { dao.insertSamples(listOf(existing.copy(unit = "L"))); fail("different units should fail") }
        catch (_: IllegalStateException) { }
        assertEquals(listOf(existing), dao.samplesOnce(event.id))
        dao.insertSamples(listOf(next))
        assertEquals(listOf(existing, next), dao.samplesOnce(event.id))
    }

    @Test fun conflictingLiveSignalInterruptsEventAndPreservesOriginalValue() = runBlocking {
        val car = VehicleEntity(name = "test"); dao.insertVehicle(car)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clock = object : CaptureClock {
                override fun wallTimeMillis() = 100_000L
                override fun elapsedRealtimeMillis() = 0L
            }
            val capture = EventCapture(scope, RoomEventCaptureStore(dao), clock, { fail(it) })
            val id = capture.start(car.id, "일반 이벤트", "", "MOCK")
            capture.accept(Signal(100_001, "fuel", 50.0, "%"))
            try { capture.accept(Signal(100_001, "fuel", 49.0, "%")); fail("conflicting signal should fail") }
            catch (_: ConflictingSignalException) { }
            assertEquals("interrupted", dao.eventById(id)?.status)
            assertEquals(50.0, dao.samplesOnce(id).single().value, 0.0)
        } finally { scope.cancel() }
    }

    @Test fun eventDetailsCanBeCorrectedWithoutChangingSamplesOrStatus() = runBlocking {
        val vehicle = VehicleEntity(name = "test"); val other = VehicleEntity(name = "other")
        dao.insertVehicle(vehicle); dao.insertVehicle(other)
        val event = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "일반 이벤트", memo = "전", source = "MOCK", status = "collecting")
        val sample = SampleEntity(event.id, 99_000, "fuel", 50.0, "%")
        dao.startEvent(event, listOf(sample))
        assertEquals(0, dao.updateEventDetails(event.id, other.id, "도로 상태", "다름"))
        assertEquals(1, dao.updateEventDetails(event.id, vehicle.id, "도로 상태", "후"))
        assertEquals(event.copy(category = "도로 상태", memo = "후"), dao.eventById(event.id))
        assertEquals(listOf(sample), dao.samplesOnce(event.id))
        dao.setStatus(event.id, "complete")
        val snapshot = dao.eventExportData(event.id)!!
        val imported = parseEventJson(exportEventJson(snapshot.event, snapshot.samples))
        assertEquals("도로 상태", imported.event.category)
        assertEquals("후", imported.event.memo)
        assertEquals("complete", imported.event.status)
        assertEquals(listOf(sample), imported.samples)
    }

    @Test fun vehicleDetailsUpdatePreservesIdAndLinkedRecords() = runBlocking {
        val vehicle = VehicleEntity(name = "기존", initialKm = 10.0)
        dao.insertVehicle(vehicle)
        val event = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
        val record = RecordEntity(vehicleId = vehicle.id, type = "주유", timestamp = 100_000, odometer = 100.0, amount = 10.0, cost = 0, label = "주유")
        dao.insertEvent(event); dao.insertRecord(record)
        assertEquals(0, dao.updateVehicle("missing", "차량", 500.0))
        assertEquals(1, dao.updateVehicle(vehicle.id, "새 이름", 500.0))
        assertEquals(vehicle.copy(name = "새 이름", initialKm = 500.0), dao.vehicle())
        assertEquals(event, dao.eventById(event.id))
        assertEquals(listOf(record), dao.records(vehicle.id).first())
    }

    @Test fun maintenanceLinkAndSoftDeleteArePersisted() = runBlocking {
        val vehicle = VehicleEntity(name = "test")
        dao.insertVehicle(vehicle)
        val event = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
        dao.insertEvent(event)
        val record = RecordEntity(vehicleId = vehicle.id, type = "정비", timestamp = 101_000, odometer = 100.0, amount = 0.0, cost = 10000, label = "점검")
        dao.insertRecord(record)
        assertTrue(dao.replaceActiveRecord(record.copy(relatedEventId = event.id, repairOutcome = "조치 완료", updatedAt = 102_000)))
        assertEquals("조치 완료", dao.activeRecord(record.id)?.repairOutcome)
        assertEquals(event.id, dao.records(vehicle.id).first().single().relatedEventId)
        dao.softDeleteRecord(record.id, vehicle.id, 103_000)
        assertNull(dao.activeRecord(record.id))
        assertTrue(dao.records(vehicle.id).first().isEmpty())
        assertFalse(dao.replaceActiveRecord(record.copy(label = "복구 시도")))
    }

    @Test fun explicitlyMappedVehiclePreservesOriginalIdAndDeduplicates() = runBlocking {
        val car = VehicleEntity(name = "local")
        val original = VehicleEntity(name = "foreign")
        dao.insertVehicle(car)
        val event = EventEntity(vehicleId = original.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
        val value = ImportedEvent(event, listOf(SampleEntity(event.id, 100_000, "fuel", 42.0, "%")))
        assertEquals(EventImportResult.VEHICLE_NOT_FOUND, dao.importEvent(value))
        assertNull(dao.eventById(event.id))
        assertEquals(EventImportResult.ADDED, dao.importEvent(value, car.id))
        val stored = dao.eventById(event.id)!!
        assertEquals(car.id, stored.vehicleId)
        assertEquals(original.id, stored.originalVehicleId)
        assertEquals(original.id, parseEventJson(exportEventJson(stored, value.samples)).event.vehicleId)
        assertEquals(EventImportResult.DUPLICATE, dao.importEvent(value, car.id))
        assertEquals(EventImportResult.CONFLICT, dao.importEvent(value.copy(event = event.copy(memo = "변경")), car.id))
    }

    @Test fun replacementRequiresTheSameVehicleAndDoesNotMoveTheBaselineBackwards() = runBlocking {
        val car = VehicleEntity(name = "local")
        val other = VehicleEntity(name = "other")
        dao.insertVehicle(car); dao.insertVehicle(other)
        val schedule = ScheduleEntity(vehicleId = car.id, label = "오일", lastKm = 1000.0, intervalKm = 5000.0, lastDoneAt = 100L)
        dao.insertSchedule(schedule)
        assertEquals(0, dao.markScheduleDone(schedule.id, other.id, 2000.0, 200L, 300L))
        assertEquals(0, dao.markScheduleDone(schedule.id, car.id, 999.0, 200L, 300L))
        assertEquals(0, dao.markScheduleDone(schedule.id, car.id, 2000.0, 99L, 300L))
        assertEquals(1, dao.markScheduleDone(schedule.id, car.id, 2000.0, 200L, 300L))
        val stored = dao.schedules(car.id).first().single()
        assertEquals(2000.0, stored.lastKm, 0.0)
        assertEquals(200L, stored.lastDoneAt)
    }

    @Test fun scheduleEditPreservesReplacementAndDeletedScheduleCannotBeResurrected() = runBlocking {
        val car = VehicleEntity(name = "local")
        val other = VehicleEntity(name = "other")
        dao.insertVehicle(car); dao.insertVehicle(other)
        val schedule = ScheduleEntity(vehicleId = car.id, label = "오일", lastKm = 1000.0, intervalKm = 5000.0, lastDoneAt = 100L)
        dao.insertSchedule(schedule)
        assertEquals(0, dao.updateSchedule(schedule.id, other.id, "타인", 6000.0, null, null, 150L))
        assertEquals(1, dao.markScheduleDone(schedule.id, car.id, 2000.0, 200L, 250L))
        assertEquals(1, dao.updateSchedule(schedule.id, car.id, "엔진오일", 6000.0, 12, null, 300L))
        val edited = dao.schedules(car.id).first().single()
        assertEquals(2000.0, edited.lastKm, 0.0)
        assertEquals(200L, edited.lastDoneAt)
        assertEquals(6000.0, edited.intervalKm, 0.0)
        assertEquals(1, dao.softDeleteSchedule(schedule.id, car.id, 400L))
        assertTrue(dao.schedules(car.id).first().isEmpty())
        assertEquals(0, dao.updateSchedule(schedule.id, car.id, "복구", 6000.0, null, null, 500L))
        assertEquals(0, dao.markScheduleDone(schedule.id, car.id, 3000.0, 300L, 500L))
        val cursor = database.openHelper.readableDatabase.query("SELECT deletedAt, updatedAt FROM ScheduleEntity WHERE id = ?", arrayOf(schedule.id))
        cursor.use { assertTrue(it.moveToFirst()); assertEquals(400L, it.getLong(0)); assertEquals(400L, it.getLong(1)) }
    }

    @Test fun migrationFromTenPreservesDataAndAllowsDurableSelection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v10-${java.util.UUID.randomUUID()}.db"
        try {
            val original = Room.databaseBuilder(context, CarlogDatabase::class.java, name).build()
            val existing = VehicleEntity(name = "기존 차량")
            val record = RecordEntity(vehicleId = existing.id, type = "주유", timestamp = 100, odometer = 1000.0, amount = 10.0, cost = 15000, label = "주유")
            original.dao().insertVehicle(existing); original.dao().insertRecord(record); original.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE ActiveVehicleEntity"); db.version = 10
            }
            val restored = VehicleEntity(name = "파일 차량")
            val migrated = Room.databaseBuilder(context, CarlogDatabase::class.java, name).addMigrations(MIGRATION_10_11).build()
            try {
                val local = migrated.dao()
                assertEquals(existing, local.vehicleForStartup())
                assertEquals(VehicleSelectionResult.CREATED, local.createOrSelectVehicle(restored))
                assertEquals(restored, local.vehicleForStartup())
                assertEquals(record, local.activeRecord(record.id))
                assertEquals(VehicleSelectionResult.CONFLICT, local.createOrSelectVehicle(restored.copy(name = "다른 이름")))
                assertEquals(restored, local.vehicleForStartup())
                assertEquals(VehicleSelectionResult.SELECTED, local.createOrSelectVehicle(restored))
            } finally { migrated.close() }
            val reopened = Room.databaseBuilder(context, CarlogDatabase::class.java, name).build()
            try {
                assertEquals(restored, reopened.dao().vehicleForStartup())
                assertEquals(existing, reopened.dao().vehicleById(existing.id))
                assertEquals(record, reopened.dao().activeRecord(record.id))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationFromNinePreservesVehicleAndLinkedRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v9-${java.util.UUID.randomUUID()}.db"
        try {
            val original = Room.databaseBuilder(context, CarlogDatabase::class.java, name).build()
            val vehicle = VehicleEntity(name = "차량", initialKm = 1200.0)
            val record = RecordEntity(vehicleId = vehicle.id, type = "주유", timestamp = 100, odometer = 1300.0, amount = 10.0, cost = 15000, label = "주유")
            original.dao().insertVehicle(vehicle); original.dao().insertRecord(record); original.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.removeV10VehicleColumns()
                db.version = 9
            }
            val migrated = Room.databaseBuilder(context, CarlogDatabase::class.java, name).addMigrations(MIGRATION_9_10, MIGRATION_10_11).build()
            try {
                val dao = migrated.dao()
                assertEquals(vehicle, dao.vehicle())
                assertEquals(record, dao.activeRecord(record.id))
                assertEquals(1, dao.updateVehicleInfo(vehicle.id, "새 차량", 1400.0, "현대", "차종", 2024, "ev"))
                assertEquals(vehicle.copy(name = "새 차량", initialKm = 1400.0, maker = "현대", model = "차종", year = 2024, fuelType = "ev"), dao.vehicle())
                assertEquals(record, dao.activeRecord(record.id))
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationFromEightRetainsUnknownTimestampKindAndStoresReceiptTime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v8-${java.util.UUID.randomUUID()}.db"
        try {
            val original = Room.databaseBuilder(context, CarlogDatabase::class.java, name).build()
            val vehicle = VehicleEntity(name = "차량")
            val event = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
            original.dao().insertVehicle(vehicle); original.dao().insertEvent(event)
            original.dao().insertSamples(listOf(SampleEntity(event.id, 99_000, "fuel", 50.0, "%")))
            original.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("ALTER TABLE SampleEntity DROP COLUMN timestampKind")
                db.removeV10VehicleColumns()
                db.version = 8
            }
            val migrated = Room.databaseBuilder(context, CarlogDatabase::class.java, name).addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build()
            try {
                val dao = migrated.dao()
                assertNull(dao.samplesOnce(event.id).single().timestampKind)
                val receipt = SampleEntity(event.id, 100_000, "odometer", 123.0, "km", "RECEIVED")
                dao.insertSamples(listOf(receipt))
                assertEquals("RECEIVED", dao.samplesOnce(event.id).last().timestampKind)
                val exported = exportEventJson(event, dao.samplesOnce(event.id))
                assertEquals(2, org.json.JSONObject(exported).getInt("schemaVersion"))
                assertEquals(dao.samplesOnce(event.id).toSet(), parseEventJson(exported).samples.toSet())
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationFromOriginalVersionOnePreservesVehicleRecordsEventsAndSamples() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v1-${java.util.UUID.randomUUID()}.db"
        try {
            val path = context.getDatabasePath(name)
            path.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE VehicleEntity (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, initialKm REAL NOT NULL)")
                db.execSQL("CREATE TABLE RecordEntity (id TEXT NOT NULL PRIMARY KEY, vehicleId TEXT NOT NULL, type TEXT NOT NULL, timestamp INTEGER NOT NULL, odometer REAL NOT NULL, amount REAL NOT NULL, cost INTEGER NOT NULL, label TEXT NOT NULL, full INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_RecordEntity_vehicleId ON RecordEntity(vehicleId)")
                db.execSQL("CREATE TABLE ScheduleEntity (id TEXT NOT NULL PRIMARY KEY, vehicleId TEXT NOT NULL, label TEXT NOT NULL, lastKm REAL NOT NULL, intervalKm REAL NOT NULL)")
                db.execSQL("CREATE INDEX index_ScheduleEntity_vehicleId ON ScheduleEntity(vehicleId)")
                db.execSQL("CREATE TABLE EventEntity (id TEXT NOT NULL PRIMARY KEY, vehicleId TEXT NOT NULL, timestamp INTEGER NOT NULL, category TEXT NOT NULL, memo TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL)")
                db.execSQL("CREATE INDEX index_EventEntity_vehicleId ON EventEntity(vehicleId)")
                db.execSQL("CREATE TABLE SampleEntity (eventId TEXT NOT NULL, timestamp INTEGER NOT NULL, name TEXT NOT NULL, value REAL NOT NULL, unit TEXT NOT NULL, PRIMARY KEY(eventId, timestamp, name), FOREIGN KEY(eventId) REFERENCES EventEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_SampleEntity_eventId ON SampleEntity(eventId)")
                db.execSQL("INSERT INTO VehicleEntity VALUES ('v1-car', '차량', 1000)")
                db.execSQL("INSERT INTO RecordEntity VALUES ('v1-record', 'v1-car', '주유', 10000, 1200, 20, 30000, '주유', 1)")
                db.execSQL("INSERT INTO ScheduleEntity VALUES ('v1-schedule', 'v1-car', '오일', 1000, 5000)")
                db.execSQL("INSERT INTO EventEntity VALUES ('v1-event', 'v1-car', 20000, '일반 이벤트', '메모', 'MOCK', 'complete')")
                db.execSQL("INSERT INTO SampleEntity VALUES ('v1-event', 20000, 'fuel', 50, '%')")
                db.version = 1
            }
            val migrated = Room.databaseBuilder(context, CarlogDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build()
            try {
                val dao = migrated.dao()
                assertEquals("v1-car", dao.vehicle()?.id)
                val record = requireNotNull(dao.activeRecord("v1-record"))
                assertEquals(1200.0, record.odometer, 0.0)
                assertTrue(record.odometerKnown)
                assertEquals(30_000L, record.cost)
                assertNull(record.category)
                assertEquals("v1-schedule", dao.schedules("v1-car").first().single().id)
                assertEquals("메모", dao.eventById("v1-event")?.memo)
                assertEquals(50.0, dao.samplesOnce("v1-event").single().value, 0.0)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationFromSixPreservesSchedulesAndExistingEvents() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        try {
            val original = Room.databaseBuilder(context, CarlogDatabase::class.java, name).build()
            val vehicle = VehicleEntity(name = "차량")
            val schedule = ScheduleEntity(vehicleId = vehicle.id, label = "오일", lastKm = 1000.0, intervalKm = 5000.0)
            val event = EventEntity(vehicleId = vehicle.id, timestamp = 100_000, category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
            original.dao().insertVehicle(vehicle); original.dao().insertSchedule(schedule); original.dao().insertEvent(event)
            original.dao().insertSamples(listOf(SampleEntity(event.id, 100_000, "fuel", 50.0, "%")))
            original.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TABLE ScheduleEntity_old (id TEXT NOT NULL PRIMARY KEY, vehicleId TEXT NOT NULL, label TEXT NOT NULL, lastKm REAL NOT NULL, intervalKm REAL NOT NULL, intervalMonths INTEGER, lastDoneAt INTEGER, intervalEngineHours REAL, category TEXT)")
                db.execSQL("INSERT INTO ScheduleEntity_old SELECT id, vehicleId, label, lastKm, intervalKm, intervalMonths, lastDoneAt, intervalEngineHours, category FROM ScheduleEntity")
                db.execSQL("DROP TABLE ScheduleEntity")
                db.execSQL("ALTER TABLE ScheduleEntity_old RENAME TO ScheduleEntity")
                db.execSQL("CREATE INDEX index_ScheduleEntity_vehicleId ON ScheduleEntity(vehicleId)")
                db.execSQL("ALTER TABLE SampleEntity DROP COLUMN timestampKind")
                db.execSQL("ALTER TABLE RecordEntity DROP COLUMN odometerKnown")
                db.removeV10VehicleColumns()
                db.version = 6
            }
            val migrated = Room.databaseBuilder(context, CarlogDatabase::class.java, name).addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build()
            try {
                assertEquals(schedule, migrated.dao().schedules(vehicle.id).first().single())
                assertEquals(event, migrated.dao().eventById(event.id))
                assertEquals(50.0, migrated.dao().samplesOnce(event.id).single().value, 0.0)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationFromSevenPreservesKnownOdometerAndAllowsUnknown() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        try {
            val original = Room.databaseBuilder(context, CarlogDatabase::class.java, name).build()
            val car = VehicleEntity(name = "차량")
            val record = RecordEntity(vehicleId = car.id, type = "지출", timestamp = 100, odometer = 1234.0, amount = 0.0, cost = 2000, label = "주차")
            original.dao().insertVehicle(car); original.dao().insertRecord(record); original.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("ALTER TABLE SampleEntity DROP COLUMN timestampKind")
                db.execSQL("ALTER TABLE RecordEntity DROP COLUMN odometerKnown")
                db.removeV10VehicleColumns()
                db.version = 7
            }
            val migrated = Room.databaseBuilder(context, CarlogDatabase::class.java, name).addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build()
            try {
                assertEquals(record, migrated.dao().activeRecord(record.id))
                val unknown = record.copy(id = java.util.UUID.randomUUID().toString(), odometer = 0.0, odometerKnown = false)
                migrated.dao().insertRecord(unknown)
                assertEquals(unknown, migrated.dao().activeRecord(unknown.id))
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
