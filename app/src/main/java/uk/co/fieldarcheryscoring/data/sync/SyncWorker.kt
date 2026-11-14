package uk.co.fieldarcheryscoring.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import uk.co.fieldarcheryscoring.FieldArcheryApp
import uk.co.fieldarcheryscoring.data.AccountStore
import java.util.concurrent.TimeUnit

/** Background sync (upload then download). Runs only when there's a network connection; Android retries it with back-off. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val syncer = (applicationContext as FieldArcheryApp).container.roundSyncer
        return when (syncer.syncAll().outcome) {
            RoundSyncer.Outcome.RETRY_LATER -> Result.retry()
            else -> Result.success()
        }
    }
}

/** Asks WorkManager to run [SyncWorker] once there's signal. Cheap to call often. */
class SyncScheduler(context: Context, private val accounts: AccountStore) {
    private val appContext = context.applicationContext

    fun request() {
        if (accounts.account.value == null) return   // nothing to do until signed in
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Queue behind any upload already running, so a change made mid-upload is sent too.
        WorkManager.getInstance(appContext).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    private companion object {
        const val UNIQUE_NAME = "fieldarchery-round-sync"
    }
}
