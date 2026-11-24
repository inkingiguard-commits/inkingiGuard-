package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.LocationHistoryEntity
import com.example.data.local.entity.SecurityEventEntity
import com.example.data.local.entity.TrustedZoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecurityEventDao {
    @Query("SELECT * FROM security_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<SecurityEventEntity>>

    @Query("SELECT * FROM security_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int): Flow<List<SecurityEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SecurityEventEntity)

    @Query("DELETE FROM security_events")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM security_events")
    suspend fun getCount(): Int
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM location_history ORDER BY timestamp DESC LIMIT 1")
    fun getLastLocation(): Flow<LocationHistoryEntity?>

    @Query("SELECT * FROM location_history ORDER BY timestamp DESC LIMIT :limit")
    fun getLocationHistory(limit: Int): Flow<List<LocationHistoryEntity>>

    @Query("SELECT * FROM location_history WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    fun getLocationsInRange(startTime: Long, endTime: Long): Flow<List<LocationHistoryEntity>>

    @Query("SELECT * FROM location_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBreadcrumb(): LocationHistoryEntity?

    @Query("SELECT COUNT(*) FROM location_history WHERE syncedWithBackend = 0")
    fun getUnsyncedCount(): Flow<Int>

    @Insert
    suspend fun insertLocation(location: LocationHistoryEntity)

    @Query("SELECT * FROM location_history WHERE syncedWithBackend = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedLocations(): List<LocationHistoryEntity>

    @Query("UPDATE location_history SET syncedWithBackend = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

@Dao
interface TrustedZoneDao {
    @Query("SELECT * FROM trusted_zones WHERE isEnabled = 1")
    fun getActiveZones(): Flow<List<TrustedZoneEntity>>

    @Query("SELECT * FROM trusted_zones ORDER BY createdAt DESC")
    fun getAllZones(): Flow<List<TrustedZoneEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertZone(zone: TrustedZoneEntity)

    @Query("DELETE FROM trusted_zones WHERE id = :id")
    suspend fun deleteZone(id: String)
}

@Dao
interface EventFingerprintDao {
    @Query("SELECT EXISTS(SELECT 1 FROM event_fingerprints WHERE fingerprint = :fingerprint)")
    suspend fun hasFingerprint(fingerprint: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFingerprint(entity: com.example.data.local.entity.EventFingerprintEntity)

    @Query("DELETE FROM event_fingerprints WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteExpiredFingerprints(cutoffTimestamp: Long)

    @Query("SELECT * FROM event_fingerprints WHERE timestamp >= :cutoffTimestamp")
    suspend fun getActiveFingerprints(cutoffTimestamp: Long): List<com.example.data.local.entity.EventFingerprintEntity>

    @Query("DELETE FROM event_fingerprints")
    suspend fun clearAll()
}

