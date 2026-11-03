package kr.carlog.pleos

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity data class VehicleEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String, val initialKm: Double = 0.0)
@Entity(indices = [Index("vehicleId")]) data class RecordEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val vehicleId: String, val type: String, val timestamp: Long, val odometer: Double, val amount: Double, val cost: Long, val label: String, val full: Boolean = false, val category: String? = null)
@Entity(indices = [Index("vehicleId")]) data class ScheduleEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val vehicleId: String, val label: String, val lastKm: Double, val intervalKm: Double)
@Entity(indices = [Index("vehicleId")]) data class EventEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val vehicleId: String, val timestamp: Long, val category: String, val memo: String, val source: String, val status: String)
@Entity(primaryKeys = ["eventId", "timestamp", "name"], indices = [Index("eventId")], foreignKeys = [ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)])
data class SampleEntity(val eventId: String, val timestamp: Long, val name: String, val value: Double, val unit: String)
@Dao interface CarlogDao {
    @Query("SELECT * FROM VehicleEntity LIMIT 1") suspend fun vehicle(): VehicleEntity?
    @Insert suspend fun insertVehicle(value: VehicleEntity)
    @Query("SELECT * FROM RecordEntity WHERE vehicleId = :id ORDER BY timestamp DESC") fun records(id: String): Flow<List<RecordEntity>>
    @Insert suspend fun insertRecord(value: RecordEntity)
    @Query("SELECT * FROM ScheduleEntity WHERE vehicleId = :id") fun schedules(id: String): Flow<List<ScheduleEntity>>
    @Insert suspend fun insertSchedule(value: ScheduleEntity)
    @Query("SELECT * FROM EventEntity WHERE vehicleId = :id ORDER BY timestamp DESC") fun events(id: String): Flow<List<EventEntity>>
    @Insert suspend fun insertEvent(value: EventEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSamples(values: List<SampleEntity>)
    @Query("SELECT * FROM SampleEntity WHERE eventId = :id ORDER BY timestamp") fun samples(id: String): Flow<List<SampleEntity>>
    @Query("UPDATE EventEntity SET status = :status WHERE id = :id") suspend fun setStatus(id: String, status: String)
    @Query("UPDATE EventEntity SET status = 'interrupted' WHERE status = 'collecting'") suspend fun interruptPending()
    @Transaction suspend fun startEvent(event: EventEntity, samples: List<SampleEntity>) { insertEvent(event); insertSamples(samples) }
}
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE RecordEntity ADD COLUMN category TEXT")
    }
}

@Database(entities = [VehicleEntity::class, RecordEntity::class, ScheduleEntity::class, EventEntity::class, SampleEntity::class], version = 2, exportSchema = false)
abstract class CarlogDatabase : RoomDatabase() { abstract fun dao(): CarlogDao }
