package co.solventa.mobile.mock

import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.espresso.localBackendUrl
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.evidence.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LegacyBlueEvidenceCleanupDeviceTest {
    @Test fun removeOnlyRecognizedLegacyBlueEvidence(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "co.solventa.mobile.pf2.mock")
        check(localBackendUrl() == BuildConfig.AUTH_BASE_URL)
        MockInitializer.backend.choose(MockScenario.DATA)
        val login = MobileAuthApi(BuildConfig.AUTH_BASE_URL)
        var stored: AuthGrant? = login.login(MockBackend.EMAIL, MockBackend.PASSWORD)
        val auth = AuthRepository(login, object : AuthStore {
            override fun read() = stored
            override fun write(grant: AuthGrant) { stored = grant }
            override fun clear() { stored = null }
        })
        val api = MobileEvidenceApi(auth, BuildConfig.AUTH_BASE_URL)
        val claims = api.claims(MockBackend.ACTOR)
        val scopes = (claims.map { it.id } + MockBackend.CLAIM).distinct()
            .map { EvidenceScope(MockBackend.ACTOR, it) }
        EvidenceStore(context.noBackupFilesDir).use { store ->
            val before = scopes.flatMap(store::list)
            val removed = LegacyBlueEvidenceCleanup.remove(store, claims, api::removeDemoConfirmed)
            check(scopes.flatMap(store::list) == before.filterNot { it.id in removed })
            for (scope in scopes) {
                val remote = api.confirmedIds(scope)
                check(before.filter { it.scope == scope && it.id in removed }.none { it.remoteId in remote })
            }
            android.util.Log.i("LegacyBlueCleanup", "Removed ${removed.size} exact legacy fixtures; kept ${scopes.flatMap(store::list).size} attachments")
            Unit
        }
    }
}
