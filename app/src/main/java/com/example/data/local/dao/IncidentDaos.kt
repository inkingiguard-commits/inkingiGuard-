package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.AuditEventEntity
import com.example.data.local.entity.IncidentEntity
import com.example.data.local.entity.QueueItemEntity
import com.example.data.local.entity.SecurityCommandEntity
import com.example.data.local.entity.TheftReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IncidentDao {
    @Query("SELECT * FROM incidents ORDER BY createdTimestamp DESC")
    fun getAllIncidents(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents ORDER BY createdTimestamp DESC")
    suspend fun getIncidentsList(): List<IncidentEntity>

    @Query("SELECT * FROM incidents WHERE status IN ('ACTIVE', 'INVESTIGATING', 'CONFIRMED_THEFT', 'RECOVERING') ORDER BY createdTimestamp DESC LIMIT 1")
    fun getActiveIncident(): Flow<IncidentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(incident: IncidentEntity)

    @Update
    suspend fun updateIncident(incident: IncidentEntity)
}

@Dao
interface TheftReportDao {
    @Query("SELECT * FROM theft_reports ORDER BY timestamp DESC LIMIT 1")
    fun getLatestTheftReport(): Flow<TheftReportEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTheftReport(report: TheftReportEntity)
}

@Dao
interface SecurityCommandDao {
    @Query("SELECT * FROM security_commands ORDER BY createdTimestamp DESC")
    fun getAllCommands(): Flow<List<SecurityCommandEntity>>

    @Query("SELECT * FROM security_commands WHERE status = 'PENDING' ORDER BY createdTimestamp ASC")
    suspend fun getPendingCommands(): List<SecurityCommandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(command: SecurityCommandEntity)

    @Update
    suspend fun updateCommand(command: SecurityCommandEntity)
}

@Dao
interface AuditEventDao {
    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC")
    fun getAllAuditEvents(): Flow<List<AuditEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditEvent(audit: AuditEventEntity)
}

@Dao
interface QueueItemDao {
    @Query(
        "SELECT * FROM security_queue " +
            "WHERE syncStatus NOT IN ('SYNCED', 'SENT_TO_BACKEND', 'DISPATCH_ACCEPTED', 'DELIVERY_CONFIRMED') " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getPendingQueueItems(): List<QueueItemEntity>

    @Query(
        "SELECT COUNT(*) FROM security_queue " +
            "WHERE syncStatus NOT IN ('SYNCED', 'SENT_TO_BACKEND', 'DISPATCH_ACCEPTED', 'DELIVERY_CONFIRMED')"
    )
    fun getPendingCount(): Flow<Int>

    @Query("SELECT * FROM security_queue ORDER BY timestamp ASC")
    suspend fun getAllQueueItems(): List<QueueItemEntity>

    @Query("SELECT * FROM security_queue ORDER BY timestamp DESC")
    fun observeAllQueueItems(): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM security_queue WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): QueueItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueItem(item: QueueItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: QueueItemEntity): Long

    @Query(
        "UPDATE security_queue SET syncStatus = 'SYNCING' " +
            "WHERE id = :id AND syncStatus IN ('LOCAL_QUEUED', 'PENDING', 'FAILED', 'NOT_CONFIGURED')"
    )
    suspend fun claimForSync(id: String): Int

    @Query("UPDATE security_queue SET syncStatus = :status WHERE id = :id")
    suspend fun setStatusWithoutRetryIncrement(id: String, status: String)

    @Query("UPDATE security_queue SET syncStatus = :status, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE security_queue SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
    suspend fun recoverStaleSyncingItems(): Int

    @Query("UPDATE security_queue SET syncStatus = 'PENDING' WHERE syncStatus = 'LOCAL_QUEUED'")
    suspend fun markLocalQueuedAsPending(): Int

    @Query(
        "DELETE FROM security_queue " +
            "WHERE syncStatus IN ('SYNCED', 'SENT_TO_BACKEND', 'DISPATCH_ACCEPTED', 'DELIVERY_CONFIRMED') " +
            "AND timestamp < :cutoffTimestamp"
    )
    suspend fun pruneConfirmedOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM security_queue WHERE syncStatus = 'SYNCED'")
    suspend fun clearSynced()
}
