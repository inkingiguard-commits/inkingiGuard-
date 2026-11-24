package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.AuditEventDao
import com.example.data.local.dao.EventFingerprintDao
import com.example.data.local.dao.IncidentDao
import com.example.data.local.dao.LocationDao
import com.example.data.local.dao.QueueItemDao
import com.example.data.local.dao.SecurityCommandDao
import com.example.data.local.dao.SecurityEventDao
import com.example.data.local.dao.TheftReportDao
import com.example.data.local.dao.TrustedZoneDao
import com.example.data.local.entity.AuditEventEntity
import com.example.data.local.entity.EventFingerprintEntity
import com.example.data.local.entity.IncidentEntity
import com.example.data.local.entity.LocationHistoryEntity
import com.example.data.local.entity.QueueItemEntity
import com.example.data.local.entity.SecurityCommandEntity
import com.example.data.local.entity.SecurityEventEntity
import com.example.data.local.entity.TheftReportEntity
import com.example.data.local.entity.TrustedZoneEntity

@Database(
    entities = [
        SecurityEventEntity::class,
        LocationHistoryEntity::class,
        TrustedZoneEntity::class,
        IncidentEntity::class,
        TheftReportEntity::class,
        SecurityCommandEntity::class,
        AuditEventEntity::class,
        QueueItemEntity::class,
        EventFingerprintEntity::class,
        com.example.data.local.entity.BehaviorEventEntity::class,
        com.example.data.local.entity.ArnaqueAppriseEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class InkingiDatabase : RoomDatabase() {

    abstract fun securityEventDao(): SecurityEventDao
    abstract fun locationDao(): LocationDao
    abstract fun trustedZoneDao(): TrustedZoneDao
    abstract fun incidentDao(): IncidentDao
    abstract fun theftReportDao(): TheftReportDao
    abstract fun securityCommandDao(): SecurityCommandDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun queueItemDao(): QueueItemDao
    abstract fun eventFingerprintDao(): EventFingerprintDao
    abstract fun behaviorEventDao(): com.example.data.local.dao.BehaviorEventDao
    abstract fun arnaqueAppriseDao(): com.example.data.local.dao.ArnaqueAppriseDao

    companion object {
        @Volatile
        private var INSTANCE: InkingiDatabase? = null

        fun getInstance(context: Context): InkingiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    InkingiDatabase::class.java,
                    "inkingi_guard.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
