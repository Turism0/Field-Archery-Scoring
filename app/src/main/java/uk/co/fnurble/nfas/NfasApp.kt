package uk.co.fnurble.nfas

import android.app.Application
import uk.co.fnurble.nfas.data.AccountRepository
import uk.co.fnurble.nfas.data.AccountStore
import uk.co.fnurble.nfas.data.AdminRepository
import uk.co.fnurble.nfas.data.FriendsRepository
import uk.co.fnurble.nfas.data.LocationPageRepository
import uk.co.fnurble.nfas.data.LocationRepository
import uk.co.fnurble.nfas.data.NetworkStatus
import uk.co.fnurble.nfas.data.ProfileRepository
import uk.co.fnurble.nfas.data.RoundRepository
import uk.co.fnurble.nfas.data.local.NfasDatabase
import uk.co.fnurble.nfas.data.remote.NfasApi
import uk.co.fnurble.nfas.data.sync.RoundSyncer
import uk.co.fnurble.nfas.data.sync.SyncScheduler

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    private val db = NfasDatabase.create(app)
    /** Device online / server available, for the home screen notices. */
    val networkStatus = NetworkStatus(app)
    private val api = NfasApi(network = networkStatus)
    val accountStore = AccountStore(app)

    val syncScheduler = SyncScheduler(app, accountStore)
    val locationRepository = LocationRepository(app, db.roundDao(), api, accountStore, onChanged = { syncScheduler.request() })
    val roundSyncer = RoundSyncer(db.roundDao(), api, accountStore, locationRepository)

    val profileRepository = ProfileRepository(db.profileDao())
    val roundRepository = RoundRepository(db.roundDao(), onChanged = { syncScheduler.request() })
    val friendsRepository = FriendsRepository(api, accountStore, onAccepted = { syncScheduler.request() })
    val adminRepository = AdminRepository(api, accountStore)
    val locationPageRepository = LocationPageRepository(api, accountStore)
    /** Asks the server if it's up (the answer, or failure, updates [networkStatus]). */
    suspend fun checkServer() {
        runCatching { api.health() }
    }

    val accountRepository = AccountRepository(api, accountStore, profileRepository, onSignedIn = { syncScheduler.request() })
}

class NfasApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // OpenStreetMap tiles: identify the app (OSM's tile policy) and cache tiles in the app's own storage.
        org.osmdroid.config.Configuration.getInstance().apply {
            load(this@NfasApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        // Send anything left waiting from last time (e.g. rounds finished with no signal).
        container.syncScheduler.request()
    }
}
