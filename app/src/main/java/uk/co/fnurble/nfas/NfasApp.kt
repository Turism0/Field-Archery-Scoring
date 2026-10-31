package uk.co.fnurble.nfas

import android.app.Application
import uk.co.fnurble.nfas.data.AccountRepository
import uk.co.fnurble.nfas.data.AccountStore
import uk.co.fnurble.nfas.data.ProfileRepository
import uk.co.fnurble.nfas.data.RoundRepository
import uk.co.fnurble.nfas.data.local.NfasDatabase
import uk.co.fnurble.nfas.data.remote.NfasApi
import uk.co.fnurble.nfas.data.sync.RoundSyncer
import uk.co.fnurble.nfas.data.sync.SyncScheduler

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    private val db = NfasDatabase.create(app)
    private val api = NfasApi()
    private val accountStore = AccountStore(app)

    val syncScheduler = SyncScheduler(app, accountStore)
    val roundSyncer = RoundSyncer(db.roundDao(), api, accountStore)

    val profileRepository = ProfileRepository(db.profileDao())
    val roundRepository = RoundRepository(db.roundDao(), onChanged = { syncScheduler.request() })
    val accountRepository = AccountRepository(api, accountStore, profileRepository, onSignedIn = { syncScheduler.request() })
}

class NfasApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Send anything left waiting from last time (e.g. rounds finished with no signal).
        container.syncScheduler.request()
    }
}
