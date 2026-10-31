package uk.co.fnurble.nfas

import android.app.Application
import uk.co.fnurble.nfas.data.AccountRepository
import uk.co.fnurble.nfas.data.AccountStore
import uk.co.fnurble.nfas.data.ProfileRepository
import uk.co.fnurble.nfas.data.RoundRepository
import uk.co.fnurble.nfas.data.local.NfasDatabase
import uk.co.fnurble.nfas.data.remote.NfasApi

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    private val db = NfasDatabase.create(app)
    val profileRepository = ProfileRepository(db.profileDao())
    val roundRepository = RoundRepository(db.roundDao())
    val accountRepository = AccountRepository(NfasApi(), AccountStore(app), profileRepository)
}

class NfasApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
