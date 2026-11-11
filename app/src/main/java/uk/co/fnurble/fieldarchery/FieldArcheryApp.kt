package uk.co.fnurble.fieldarchery

import android.app.Application
import uk.co.fnurble.fieldarchery.data.AccountRepository
import uk.co.fnurble.fieldarchery.data.AccountStore
import uk.co.fnurble.fieldarchery.data.AdminRepository
import uk.co.fnurble.fieldarchery.data.FriendsRepository
import uk.co.fnurble.fieldarchery.data.HandicapRepository
import uk.co.fnurble.fieldarchery.data.LiveGroupRepository
import uk.co.fnurble.fieldarchery.data.LocationPageRepository
import uk.co.fnurble.fieldarchery.data.LocationRepository
import uk.co.fnurble.fieldarchery.data.NetworkStatus
import uk.co.fnurble.fieldarchery.data.ProfileRepository
import uk.co.fnurble.fieldarchery.data.RoundRepository
import uk.co.fnurble.fieldarchery.data.local.FieldArcheryDatabase
import uk.co.fnurble.fieldarchery.data.remote.FieldArcheryApi
import uk.co.fnurble.fieldarchery.data.sync.RoundSyncer
import uk.co.fnurble.fieldarchery.data.sync.SyncScheduler

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    private val db = FieldArcheryDatabase.create(app)
    /** Device online / server available, for the home screen notices. */
    val networkStatus = NetworkStatus(app)
    private val api = FieldArcheryApi(network = networkStatus)
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
    val homeLayoutStore = uk.co.fnurble.fieldarchery.data.HomeLayoutStore(app)
    val statsFilterStore = uk.co.fnurble.fieldarchery.data.StatsFilterStore(app)
    /** Asks the server if it's up (the answer, or failure, updates [networkStatus]). */
    suspend fun checkServer() {
        runCatching { api.health() }
    }

    /** Whose rounds are on this phone (3.2.02). */
    private val deviceData = uk.co.fnurble.fieldarchery.data.DeviceData(app, db.roundDao(), db.profileDao(), statsFilterStore, liveGroupRepository)
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
