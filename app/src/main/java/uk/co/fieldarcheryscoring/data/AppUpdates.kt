package uk.co.fieldarcheryscoring.data

import android.content.Context
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Update available" (3.2.15, Pete): asks Google Play whether a newer version of the app is there for this person
 * (closed testers included), so Home can say so. Only works for an app installed from Play; a build run from Android
 * Studio just never shows it. Checked at most once an hour.
 */
class AppUpdates(context: Context) {
    private val manager = AppUpdateManagerFactory.create(context.applicationContext)

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var checkedAt = 0L

    suspend fun check() {
        val now = System.currentTimeMillis()
        if (now - checkedAt < HOUR_MS) return
        checkedAt = now
        try {
            val info = manager.requestAppUpdateInfo()
            _available.value = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i("AppUpdates", "Couldn't ask Google Play about updates: ${e.message}")
        }
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
