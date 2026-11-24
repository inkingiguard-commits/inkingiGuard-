package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ArnaqueAppriseEntity
import com.example.data.local.entity.BehaviorEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BehaviorEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: BehaviorEventEntity): Long

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<BehaviorEventEntity>>

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int): Flow<List<BehaviorEventEntity>>

    @Query("SELECT COUNT(DISTINCT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime')) FROM events WHERE action NOT IN ('GUEST_SESSION', 'TRUSTED_PERSON_SESSION')")
    fun getUniqueLearningDays(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime')) FROM events WHERE action NOT IN ('GUEST_SESSION', 'TRUSTED_PERSON_SESSION')")
    suspend fun getUniqueLearningDaysCount(): Int

    @Query("SELECT COUNT(*) FROM events WHERE action NOT IN ('GUEST_SESSION', 'TRUSTED_PERSON_SESSION')")
    suspend fun getTotalEventCount(): Int

    @Query("SELECT heure, COUNT(*) as cnt FROM events WHERE action NOT IN ('GUEST_SESSION', 'TRUSTED_PERSON_SESSION') GROUP BY heure ORDER BY cnt DESC")
    suspend fun getFrequentHours(): List<HourFrequency>

    @Query("SELECT zone_nom, COUNT(*) as cnt FROM events WHERE action NOT IN ('GUEST_SESSION', 'TRUSTED_PERSON_SESSION') GROUP BY zone_nom ORDER BY cnt DESC")
    suspend fun getFrequentZones(): List<ZoneFrequency>

    @Query(
        "SELECT * FROM events " +
            "WHERE (device_id = :deviceId OR device_id = '') " +
            "AND action NOT IN ('GUEST_SESSION', 'TRUSTED_PERSON_SESSION') " +
            "AND timestamp >= :sinceTimestamp " +
            "ORDER BY timestamp DESC"
    )
    suspend fun getOwnerEventsSince(deviceId: String, sinceTimestamp: Long): List<BehaviorEventEntity>

    @Query("DELETE FROM events WHERE device_id = :deviceId OR device_id = ''")
    suspend fun clearForDevice(deviceId: String)

    @Query("DELETE FROM events")
    suspend fun clearAll()
}

data class HourFrequency(
    val heure: Int,
    val cnt: Int
)

data class ZoneFrequency(
    val zone_nom: String,
    val cnt: Int
)

@Dao
interface ArnaqueAppriseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArnaque(arnaque: ArnaqueAppriseEntity)

    @Query("SELECT * FROM arnaques_apprises ORDER BY count DESC, lastDetectedTimestamp DESC")
    fun getAllLearnedArnaques(): Flow<List<ArnaqueAppriseEntity>>

    @Query("SELECT COUNT(*) FROM arnaques_apprises")
    fun getLearnedPatternsCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(count), 0) FROM arnaques_apprises")
    fun getTotalBlockedCount(): Flow<Int>

    @Query("SELECT * FROM arnaques_apprises WHERE pattern = :pattern LIMIT 1")
    suspend fun findByPattern(pattern: String): ArnaqueAppriseEntity?
}
