package com.example.domain.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.InkingiGuardApplication
import java.util.concurrent.TimeUnit

/**
 * WorkManager background worker that drains [SecurityEventQueue] safely when
 * network connectivity is available, respecting exponential backoff and queue concurrency locks.
 */
class SecurityEventSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SecurityEventSyncWorker"
        const val UNIQUE_WORK_NAME = "inkingi_security_event_queue_sync"

        fun enqueue(context: Context): Boolean {
            return try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = OneTimeWorkRequestBuilder<SecurityEventSyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enqueue SecurityEventSyncWorker: ${SecurityLogRedactor.redact(e.message)}")
                false
            }
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? InkingiGuardApplication ?: return Result.success()
        val queue = app.container.securityEventQueue
        queue.processPendingQueue(isNetworkAvailable = true)
        queue.pruneExpiredConfirmedItems()
        val summary = queue.queueSummary.value
        val hasRetryablePending = (summary.pendingCount + summary.localQueuedCount) > 0
        return if (hasRetryablePending && runAttemptCount < 5) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
