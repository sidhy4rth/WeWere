package com.rollapp.shared.data.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single queue-draining work chain.
 *
 * `KEEP` rather than `REPLACE`: enqueuing ten photos in a row should join the run
 * already in progress, not restart it ten times and lose the partial upload each time.
 */
@Singleton
class UploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val workManager get() = WorkManager.getInstance(context)

    fun ensureRunning() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(UploadWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Used by an explicit "Retry now" tap, which should not wait out the backoff. */
    fun restartNow() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(UploadWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
