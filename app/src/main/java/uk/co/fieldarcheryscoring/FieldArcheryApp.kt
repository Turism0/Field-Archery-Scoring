package uk.co.fieldarcheryscoring

import android.app.Application
import uk.co.fieldarcheryscoring.data.AccountRepository
import uk.co.fieldarcheryscoring.data.AccountStore
import uk.co.fieldarcheryscoring.data.AdminRepository
import uk.co.fieldarcheryscoring.data.FriendsRepository
import uk.co.fieldarcheryscoring.data.HandicapRepository
import uk.co.fieldarcheryscoring.data.LiveGroupRepository
import uk.co.fieldarcheryscoring.data.LocationPageRepository
import uk.co.fieldarcheryscoring.data.LocationRepository
import uk.co.fieldarcheryscoring.data.NetworkStatus
import uk.co.fieldarcheryscoring.data.ProfileRepository
import uk.co.fieldarcheryscoring.data.RoundRepository
import uk.co.fieldarcheryscoring.data.local.FieldArcheryDatabase
import uk.co.fieldarcheryscoring.data.remote.FieldArcheryApi
import uk.co.fieldarcheryscoring.data.sync.RoundSyncer
import uk.co.fieldarcheryscoring.data.sync.SyncScheduler

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    private val db = FieldArcheryDatabase.create(app)
    /** Device online / server available, for the home screen notices. */
    val networkStatus = NetworkStatus(app)
    /** 3.2.05: this app's versionCode, sent with every call ("android/66") so the server can ask for an update. */
    private val versionCode: Long = runCatching {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
    }.getOrDefault(0L)
    private val api = FieldArcheryApi(network = networkStatus, appVersion = "android/$versionCode")
    val accountStore = AccountStore(app)

    val syncScheduler = SyncScheduler(app, accountStore)
    val locationRepository = LocationRepository(app, db.roundDao(), api, accountStore, onChanged = { syncScheduler.request() })
    val roundSyncer = RoundSyncer(db.roundDao(), api, accountStore, locationRepository)

    val profileRepository = ProfileRepository(db.profileDao())
    val roundRepository = RoundRepository(db.roundDao(), onChanged = { syncScheduler.request() })
    val friendsRepository = FriendsRepository(api, accountStore, onAccepted = { syncScheduler.request() })
    val adminRepository = AdminRepository(api, accountStore)
    val locationPageRepository = LocationPageRepository(api, accountStore)
    /** Live groups: one round scored on several phones through the server (2.1). */
    val liveGroupRepository = LiveGroupRepository(app, api, accountStore, roundRepository)
    /** Handicap model from the server, kept on the phone (2.2.05). */
    val handicapRepository = HandicapRepository(app, api, accountStore)
    /** How Home's tiles are arranged (2.4.01). */
    val homeLayoutStore = uk.co.fieldarcheryscoring.data.HomeLayoutStore(app)
    val statsFilterStore = uk.co.fieldarcheryscoring.data.StatsFilterStore(app)
    /** Asks the server if it's up (the answer, or failure, updates [networkStatus]). */
    suspend fun checkServer() {
        runCatching { api.health() }
    }

    /** Whose rounds are on this phone (3.2.02). */
    private val deviceData = uk.co.fieldarcheryscoring.data.DeviceData(app, db.roundDao(), db.profileDao(), statsFilterStore, liveGroupRepository)
    val accountRepository = AccountRepository(api, accountStore, profileRepository, deviceData, onSignedIn = { syncScheduler.request() })
}

class FieldArcheryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // OpenStreetMap tiles: identify the app (OSM's tile policy) and cache tiles in the app's own storage.
        org.osmdroid.config.Configuration.getInstance().apply {
            load(this@FieldArcheryApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        // Send anything left waiting from last time (e.g. rounds finished with no signal).
        container.syncScheduler.request()
    }
}
