package kr.carlog.pleos

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity data class VehicleEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String, val initialKm: Double = 0.0,
    val maker: String? = null, val model: String? = null, val year: Int? = null, val fuelType: String? = null)
/** A single active vehicle selection; existing vehicle rows remain untouched. */
@Entity data class ActiveVehicleEntity(@PrimaryKey val id: Int = 1, val vehicleId: String)
enum class VehicleSelectionResult { CREATED, SELECTED, CONFLICT, ACTIVE_CAPTURE }
@Entity(indices = [Index("vehicleId")]) data class RecordEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val vehicleId: String, val type: String, val timestamp: Long, val odometer: Double, val amount: Double, val cost: Long, val label: String, val full: Boolean = false, val category: String? = null, val engineHours: Double? = null, val engineHoursMode: String? = null, val updatedAt: Long? = null, val deletedAt: Long? = null, val relatedEventId: String? = null, val repairOutcome: String? = null, @ColumnInfo(defaultValue = "1") val odometerKnown: Boolean = true)
@Entity(indices = [Index("vehicleId")]) data class ScheduleEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val vehicleId: String, val label: String, val lastKm: Double, val intervalKm: Double, val intervalMonths: Long? = null, val lastDoneAt: Long? = null, val intervalEngineHours: Double? = null, val category: String? = null, val updatedAt: Long? = null, val deletedAt: Long? = null)
@Entity(indices = [Index("vehicleId")]) data class EventEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val vehicleId: String, val timestamp: Long, val category: String, val memo: String, val source: String, val status: String, val originalVehicleId: String? = null)
@Entity(primaryKeys = ["eventId", "timestamp", "name"], indices = [Index("eventId")], foreignKeys = [ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)])
data class SampleEntity(val eventId: String, val timestamp: Long, val name: String, val value: Double, val unit: String, val timestampKind: String? = null)
enum class EventImportResult { ADDED, DUPLICATE, CONFLICT, VEHICLE_NOT_FOUND }
data class EventExportData(val event: EventEntity, val samples: List<SampleEntity>)
data class MaintenanceReportData(val event: EventEntity, val samples: List<SampleEntity>, val repairs: List<RecordEntity>, val vehicle: VehicleEntity)
data class VehicleManagementExportData(val vehicle: VehicleEntity, val records: List<RecordEntity>, val schedules: List<ScheduleEntity>)
enum class VehicleManagementImportStatus { APPLIED, VEHICLE_MISMATCH, VEHICLE_CONFLICT, RECORD_CONFLICT, MISSING_EVENT }
data class VehicleManagementImportResult(val status: VehicleManagementImportStatus, val added: Int = 0, val duplicates: Int = 0, val missingEventIds: List<String> = emptyList())
@Dao interface CarlogDao {
    @Query("SELECT * FROM VehicleEntity LIMIT 1") suspend fun vehicle(): VehicleEntity?
    @Query("SELECT * FROM VehicleEntity WHERE id = :id") suspend fun vehicleById(id: String): VehicleEntity?
    @Query("SELECT * FROM VehicleEntity WHERE id = :id") fun observeVehicle(id: String): Flow<VehicleEntity?>
    @Query("SELECT * FROM VehicleEntity ORDER BY name, id") fun vehicles(): Flow<List<VehicleEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM EventEntity WHERE status = 'collecting')") suspend fun hasCollectingEvents(): Boolean
    @Query("SELECT v.* FROM VehicleEntity v INNER JOIN ActiveVehicleEntity a ON a.vehicleId = v.id WHERE a.id = 1") suspend fun selectedVehicle(): VehicleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setActiveVehicle(value: ActiveVehicleEntity)
    @Transaction suspend fun vehicleForStartup(): VehicleEntity? = selectedVehicle() ?: vehicle()
    /** Explicit selection only; an existing vehicle with the same UUID is never overwritten. */
    @Transaction suspend fun createOrSelectVehicle(value: VehicleEntity): VehicleSelectionResult {
        require(UUID.fromString(value.id).toString() == value.id && validVehicleInfo(value.name, value.initialKm, value.year, value.fuelType))
        if (hasCollectingEvents()) return VehicleSelectionResult.ACTIVE_CAPTURE
        val existing = vehicleById(value.id)
        if (existing != null && existing != value) return VehicleSelectionResult.CONFLICT
        if (existing == null) insertVehicle(value)
        setActiveVehicle(ActiveVehicleEntity(vehicleId = value.id))
        return if (existing == null) VehicleSelectionResult.CREATED else VehicleSelectionResult.SELECTED
    }
    @Insert suspend fun insertVehicle(value: VehicleEntity)
    @Query("UPDATE VehicleEntity SET name = :name, initialKm = :initialKm WHERE id = :id")
    suspend fun updateVehicle(id: String, name: String, initialKm: Double): Int
    @Query("UPDATE VehicleEntity SET name = :name, initialKm = :initialKm, maker = :maker, model = :model, year = :year, fuelType = :fuelType WHERE id = :id")
    suspend fun updateVehicleInfo(id: String, name: String, initialKm: Double, maker: String?, model: String?, year: Int?, fuelType: String?): Int
    @Transaction suspend fun updateVehicleInfoIfUnchanged(expected: VehicleEntity, value: VehicleEntity): Int {
        if (expected.id != value.id || vehicleById(expected.id) != expected) return 0
        return updateVehicleInfo(value.id, value.name, value.initialKm, value.maker, value.model, value.year, value.fuelType)
    }
    @Query("SELECT * FROM RecordEntity WHERE vehicleId = :id AND deletedAt IS NULL ORDER BY timestamp DESC, id") fun records(id: String): Flow<List<RecordEntity>>
    @Query("SELECT * FROM RecordEntity WHERE vehicleId = :id AND deletedAt IS NULL ORDER BY timestamp, id") suspend fun recordsForExport(id: String): List<RecordEntity>
    @Insert suspend fun insertRecord(value: RecordEntity)
    @Query("SELECT * FROM RecordEntity WHERE id = :id AND deletedAt IS NULL") suspend fun activeRecord(id: String): RecordEntity?
    @Update suspend fun updateRecord(value: RecordEntity)
    @Query("UPDATE RecordEntity SET deletedAt = :now, updatedAt = :now WHERE id = :id AND vehicleId = :vehicleId AND deletedAt IS NULL") suspend fun softDeleteRecord(id: String, vehicleId: String, now: Long): Int
    @Transaction suspend fun softDeleteRecordIfUnchanged(expected: RecordEntity, vehicleId: String, now: Long): Int {
        if (expected.vehicleId != vehicleId || expected.deletedAt != null || activeRecord(expected.id) != expected) return 0
        return softDeleteRecord(expected.id, vehicleId, now)
    }
    @Transaction suspend fun replaceActiveRecord(value: RecordEntity): Boolean {
        val existing = activeRecord(value.id) ?: return false
        if (existing.type != value.type || existing.vehicleId != value.vehicleId) return false
        updateRecord(value)
        return true
    }
    /** Reject stale forms without relying on device-clock revision timestamps. */
    @Transaction suspend fun replaceRecordIfUnchanged(expected: RecordEntity, value: RecordEntity): Boolean {
        if (expected.deletedAt != null || value.deletedAt != null || expected.id != value.id ||
            expected.type != value.type || expected.vehicleId != value.vehicleId) return false
        if (activeRecord(expected.id) != expected) return false
        updateRecord(value)
        return true
    }
    @Query("SELECT * FROM ScheduleEntity WHERE vehicleId = :id AND deletedAt IS NULL") fun schedules(id: String): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM ScheduleEntity WHERE vehicleId = :id AND deletedAt IS NULL ORDER BY id") suspend fun schedulesForExport(id: String): List<ScheduleEntity>
    @Transaction suspend fun vehicleManagementExport(id: String): VehicleManagementExportData? {
        val vehicle = vehicleById(id) ?: return null
        return VehicleManagementExportData(vehicle, recordsForExport(id), schedulesForExport(id))
    }
    @Query("SELECT * FROM RecordEntity WHERE id = :id") suspend fun recordById(id: String): RecordEntity?
    @Transaction suspend fun insertRecordOnce(value: RecordEntity): Boolean {
        val existing = recordById(value.id)
        if (existing != null) return existing == value
        insertRecord(value)
        return true
    }
    @Query("SELECT * FROM ScheduleEntity WHERE id = :id") suspend fun scheduleById(id: String): ScheduleEntity?
    @Query("SELECT * FROM RecordEntity WHERE id IN (:ids)") suspend fun recordsByIds(ids: List<String>): List<RecordEntity>
    @Query("SELECT * FROM ScheduleEntity WHERE id IN (:ids)") suspend fun schedulesByIds(ids: List<String>): List<ScheduleEntity>
    @Query("SELECT id FROM EventEntity WHERE vehicleId = :vehicleId AND id IN (:ids)")
    suspend fun eventIdsForVehicle(ids: List<String>, vehicleId: String): List<String>
    @Transaction suspend fun importVehicleManagement(data: VehicleManagementExportData, currentId: String): VehicleManagementImportResult {
        if (data.vehicle.id != currentId) return VehicleManagementImportResult(VehicleManagementImportStatus.VEHICLE_MISMATCH)
        if (vehicleById(currentId) != data.vehicle) return VehicleManagementImportResult(VehicleManagementImportStatus.VEHICLE_CONFLICT)
        require(data.records.all { it.vehicleId == currentId && it.deletedAt == null } && data.records.map { it.id }.distinct().size == data.records.size)
        require(data.schedules.all { it.vehicleId == currentId && it.deletedAt == null } && data.schedules.map { it.id }.distinct().size == data.schedules.size)
        // Stay below SQLite's parameter limit, and preserve file order in the error.
        val eventIds = data.records.mapNotNull { it.relatedEventId }.distinct()
        val foundEvents = eventIds.chunked(500).flatMap { eventIdsForVehicle(it, currentId) }.toSet()
        val missingEvents = eventIds.filterNot { it in foundEvents }
        if (missingEvents.isNotEmpty()) return VehicleManagementImportResult(VehicleManagementImportStatus.MISSING_EVENT, missingEventIds = missingEvents)
        val existingRecords = data.records.map { it.id }.chunked(500).flatMap { recordsByIds(it) }.associateBy { it.id }
        val existingSchedules = data.schedules.map { it.id }.chunked(500).flatMap { schedulesByIds(it) }.associateBy { it.id }
        val newRecords = mutableListOf<RecordEntity>()
        val newSchedules = mutableListOf<ScheduleEntity>()
        var duplicates = 0
        for (record in data.records) {
            val existing = existingRecords[record.id]
            if (existing == null) newRecords.add(record)
            else if (existing.deletedAt == null && (if (record.type == "주유") existing else existing.copy(full = false)) == record) duplicates++
            else return VehicleManagementImportResult(VehicleManagementImportStatus.RECORD_CONFLICT)
        }
        for (schedule in data.schedules) {
            val existing = existingSchedules[schedule.id]
            if (existing == null) newSchedules.add(schedule)
            else if (existing.deletedAt == null && existing == schedule) duplicates++
            else return VehicleManagementImportResult(VehicleManagementImportStatus.RECORD_CONFLICT)
        }
        newRecords.forEach { insertRecord(it) }
        newSchedules.forEach { insertSchedule(it) }
        return VehicleManagementImportResult(VehicleManagementImportStatus.APPLIED, newRecords.size + newSchedules.size, duplicates)
    }
    @Insert suspend fun insertSchedule(value: ScheduleEntity)
    @Query("UPDATE ScheduleEntity SET lastKm = :km, lastDoneAt = :doneAt, updatedAt = :now WHERE id = :id AND vehicleId = :vehicleId AND deletedAt IS NULL AND lastKm <= :km AND (lastDoneAt IS NULL OR lastDoneAt <= :doneAt)")
    suspend fun markScheduleDone(id: String, vehicleId: String, km: Double, doneAt: Long, now: Long): Int
    @Query("UPDATE ScheduleEntity SET label = :label, intervalKm = :km, intervalMonths = :months, intervalEngineHours = :hours, updatedAt = :now WHERE id = :id AND vehicleId = :vehicleId AND deletedAt IS NULL")
    suspend fun updateSchedule(id: String, vehicleId: String, label: String, km: Double, months: Long?, hours: Double?, now: Long): Int
    @Transaction suspend fun updateScheduleIfUnchanged(expected: ScheduleEntity, vehicleId: String, label: String, km: Double, months: Long?, hours: Double?, now: Long): Int {
        if (expected.vehicleId != vehicleId || expected.deletedAt != null || scheduleById(expected.id) != expected) return 0
        return updateSchedule(expected.id, vehicleId, label, km, months, hours, now)
    }
    @Query("UPDATE ScheduleEntity SET lastDoneAt = :doneAt, updatedAt = :now WHERE id = :id AND vehicleId = :vehicleId AND deletedAt IS NULL AND lastDoneAt IS NULL")
    suspend fun fillMissingScheduleDate(id: String, vehicleId: String, doneAt: Long, now: Long): Int
    @Transaction suspend fun fillMissingScheduleDateIfUnchanged(expected: ScheduleEntity, vehicleId: String, doneAt: Long, now: Long): Int {
        if (expected.vehicleId != vehicleId || expected.deletedAt != null || expected.lastDoneAt != null || scheduleById(expected.id) != expected) return 0
        return fillMissingScheduleDate(expected.id, vehicleId, doneAt, now)
    }
    @Transaction suspend fun markScheduleDoneIfUnchanged(expected: ScheduleEntity, vehicleId: String, km: Double, doneAt: Long, now: Long): Int {
        if (expected.vehicleId != vehicleId || expected.deletedAt != null || scheduleById(expected.id) != expected) return 0
        return markScheduleDone(expected.id, vehicleId, km, doneAt, now)
    }
    @Query("UPDATE ScheduleEntity SET deletedAt = :now, updatedAt = :now WHERE id = :id AND vehicleId = :vehicleId AND deletedAt IS NULL")
    suspend fun softDeleteSchedule(id: String, vehicleId: String, now: Long): Int
    @Transaction suspend fun softDeleteScheduleIfUnchanged(expected: ScheduleEntity, vehicleId: String, now: Long): Int {
        if (expected.vehicleId != vehicleId || expected.deletedAt != null || scheduleById(expected.id) != expected) return 0
        return softDeleteSchedule(expected.id, vehicleId, now)
    }
    @Query("SELECT * FROM EventEntity WHERE vehicleId = :id ORDER BY timestamp DESC, id") fun events(id: String): Flow<List<EventEntity>>
    @Query("SELECT * FROM EventEntity WHERE id = :id") suspend fun eventById(id: String): EventEntity?
    @Query("UPDATE EventEntity SET category = :category, memo = :memo WHERE id = :id AND vehicleId = :vehicleId")
    suspend fun updateEventDetails(id: String, vehicleId: String, category: String, memo: String): Int
    @Query("UPDATE EventEntity SET category = :category, memo = :memo WHERE id = :id AND vehicleId = :vehicleId AND category = :expectedCategory AND memo = :expectedMemo")
    suspend fun updateEventDetailsIfUnchanged(id: String, vehicleId: String, expectedCategory: String, expectedMemo: String, category: String, memo: String): Int
    @Query("SELECT EXISTS(SELECT 1 FROM VehicleEntity WHERE id = :id)") suspend fun hasVehicle(id: String): Boolean
    @Insert suspend fun insertEvent(value: EventEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSamplesIgnoringDuplicates(values: List<SampleEntity>): List<Long>
    @Query("SELECT * FROM SampleEntity WHERE eventId = :eventId AND timestamp = :timestamp AND name = :name")
    suspend fun sampleByKey(eventId: String, timestamp: Long, name: String): SampleEntity?
    @Transaction suspend fun insertSamples(values: List<SampleEntity>) {
        val inserted = insertSamplesIgnoringDuplicates(values)
        values.forEachIndexed { index, sample ->
            if (inserted[index] == -1L) {
                val existing = sampleByKey(sample.eventId, sample.timestamp, sample.name)
                check(existing != null && existing.value == sample.value && existing.unit == sample.unit && existing.timestampKind == sample.timestampKind) {
                    "Conflicting sample for the same timestamp and name"
                }
            }
        }
    }
    @Query("SELECT * FROM SampleEntity WHERE eventId = :id ORDER BY timestamp, name") fun samples(id: String): Flow<List<SampleEntity>>
    @Query("SELECT * FROM SampleEntity WHERE eventId = :id ORDER BY timestamp, name") suspend fun samplesOnce(id: String): List<SampleEntity>
    @Transaction suspend fun eventExportData(id: String): EventExportData? {
        val event = eventById(id)?.takeIf { it.status == "complete" || it.status == "interrupted" } ?: return null
        return EventExportData(event, samplesOnce(id))
    }
    @Query("SELECT * FROM RecordEntity WHERE vehicleId = :vehicleId AND relatedEventId = :eventId AND type = '정비' AND deletedAt IS NULL ORDER BY timestamp DESC, id")
    suspend fun linkedRepairs(vehicleId: String, eventId: String): List<RecordEntity>
    /** Read every report component from one Room snapshot, including collecting events. */
    @Transaction suspend fun maintenanceReportData(id: String, vehicleId: String): MaintenanceReportData? {
        val event = eventById(id)?.takeIf { it.vehicleId == vehicleId } ?: return null
        val vehicle = vehicleById(vehicleId) ?: return null
        return MaintenanceReportData(event, samplesOnce(id), linkedRepairs(vehicleId, id), vehicle)
    }
    @Query("UPDATE EventEntity SET status = :status WHERE id = :id AND status = 'collecting'")
    suspend fun setStatus(id: String, status: String): Int
    @Transaction suspend fun finishCollectingEvent(id: String, status: String) {
        require(status == "complete" || status == "interrupted")
        if (setStatus(id, status) == 0) check(eventById(id)?.status == status) { "Event is no longer collecting" }
    }
    @Transaction suspend fun recoverFailedEventStart(id: String) {
        val current = eventById(id) ?: return
        when (current.status) {
            "collecting" -> check(setStatus(id, "interrupted") == 1)
            "interrupted" -> Unit
            else -> error("Unexpected event status during start recovery")
        }
    }
    /** Cancellation may arrive after completion committed; never replace a completed event. */
    @Transaction suspend fun recoverCancelledEventCompletion(id: String) {
        val current = eventById(id) ?: error("Event no longer exists")
        when (current.status) {
            "collecting" -> check(setStatus(id, "interrupted") == 1)
            "complete", "interrupted" -> Unit
            else -> error("Unexpected event status")
        }
    }
    @Transaction suspend fun appendCollectingSamples(values: List<SampleEntity>) {
        values.map { it.eventId }.distinct().forEach { id ->
            check(eventById(id)?.status == "collecting") { "Event is no longer collecting" }
        }
        insertSamples(values)
    }
    @Query("UPDATE EventEntity SET status = 'interrupted' WHERE status = 'collecting'") suspend fun interruptPending()
    @Transaction suspend fun startEvent(event: EventEntity, samples: List<SampleEntity>) { insertEvent(event); insertSamples(samples) }
    @Transaction suspend fun importEvent(value: ImportedEvent, mappedVehicleId: String? = null): EventImportResult {
        val originalVehicleId = value.event.originalVehicleId ?: value.event.vehicleId
        val vehicleId = if (hasVehicle(originalVehicleId)) originalVehicleId else mappedVehicleId
        if (vehicleId == null || !hasVehicle(vehicleId)) return EventImportResult.VEHICLE_NOT_FOUND
        val event = value.event.copy(vehicleId = vehicleId, originalVehicleId = originalVehicleId.takeIf { it != vehicleId })
        val existing = eventById(event.id)
        if (existing != null) {
            val incoming = value.samples.sortedWith(compareBy<SampleEntity> { it.timestamp }.thenBy { it.name })
            return if (existing == event && samplesOnce(existing.id) == incoming) EventImportResult.DUPLICATE else EventImportResult.CONFLICT
        }
        insertEvent(event)
        insertSamples(value.samples)
        return EventImportResult.ADDED
    }
}
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE RecordEntity ADD COLUMN category TEXT")
    }
}
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE RecordEntity ADD COLUMN engineHours REAL")
        db.execSQL("ALTER TABLE RecordEntity ADD COLUMN engineHoursMode TEXT")
        db.execSQL("ALTER TABLE ScheduleEntity ADD COLUMN intervalMonths INTEGER")
        db.execSQL("ALTER TABLE ScheduleEntity ADD COLUMN lastDoneAt INTEGER")
        db.execSQL("ALTER TABLE ScheduleEntity ADD COLUMN intervalEngineHours REAL")
        db.execSQL("ALTER TABLE ScheduleEntity ADD COLUMN category TEXT")
    }
}
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE RecordEntity ADD COLUMN updatedAt INTEGER")
        db.execSQL("ALTER TABLE RecordEntity ADD COLUMN deletedAt INTEGER")
    }
}
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE RecordEntity ADD COLUMN relatedEventId TEXT")
        db.execSQL("ALTER TABLE RecordEntity ADD COLUMN repairOutcome TEXT")
    }
}
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE EventEntity ADD COLUMN originalVehicleId TEXT")
    }
}
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ScheduleEntity ADD COLUMN updatedAt INTEGER")
        db.execSQL("ALTER TABLE ScheduleEntity ADD COLUMN deletedAt INTEGER")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE RecordEntity ADD COLUMN odometerKnown INTEGER NOT NULL DEFAULT 1")
    }
}
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE SampleEntity ADD COLUMN timestampKind TEXT")
    }
}
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE VehicleEntity ADD COLUMN maker TEXT")
        db.execSQL("ALTER TABLE VehicleEntity ADD COLUMN model TEXT")
        db.execSQL("ALTER TABLE VehicleEntity ADD COLUMN year INTEGER")
        db.execSQL("ALTER TABLE VehicleEntity ADD COLUMN fuelType TEXT")
    }
}
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ActiveVehicleEntity (id INTEGER NOT NULL PRIMARY KEY, vehicleId TEXT NOT NULL)")
    }
}
@Database(entities = [VehicleEntity::class, ActiveVehicleEntity::class, RecordEntity::class, ScheduleEntity::class, EventEntity::class, SampleEntity::class], version = 11, exportSchema = false)
abstract class CarlogDatabase : RoomDatabase() { abstract fun dao(): CarlogDao }
