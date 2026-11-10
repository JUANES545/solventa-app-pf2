package co.solventa.mobile.mock

import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.database.sqlite.transaction
import android.database.sqlite.SQLiteDatabase
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.espresso.localBackendUrl
import co.solventa.mobile.feature.auth.*
import co.solventa.mobile.feature.claim.ClaimStore
import co.solventa.mobile.feature.evidence.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TestReportCleanupDeviceTest {
    @Test fun removeOnlyEmptyRecognizedTestReports(): Unit = runBlocking {
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
        val reports = ClaimStore(context.noBackupFilesDir).use { it.list(MockBackend.ACTOR) }
        val corrections = reports.mapNotNull { it.supersededBy }.toSet()
        val protectedReports = reports.filter {
            it.receipt == null || it.error != null || it.supersededBy != null || it.operation in corrections
        }
        val protected = protectedReports.mapNotNull { it.receipt?.id }.toSet()
        EvidenceStore(context.noBackupFilesDir).use { store ->
            val scopes = claims.associate { it.id to EvidenceScope(MockBackend.ACTOR, it.id) }
            val attachments = scopes.values.associateWith(store::list)
            val candidates = scopes.filter { (id, scope) -> id !in protected && attachments.getValue(scope).isEmpty() }.keys
            val removed = MockInitializer.backend.removeEmptyTestClaims(candidates, protectedReports.map { it.operation }.toSet())
            val confirmedTests = reports.filter { it.receipt?.id in removed }
            SQLiteDatabase.openDatabase(java.io.File(context.noBackupFilesDir, "claim-reports.db").path,
                null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.transaction {
                    for (report in confirmedTests) check(database.delete("reports",
                        "actor=? AND operation=? AND remote_id=? AND error IS NULL AND superseded_by IS NULL",
                        arrayOf(MockBackend.ACTOR, report.operation, checkNotNull(report.receipt).id)) == 1)
                }
            }
            check(api.claims(MockBackend.ACTOR) == claims.filterNot { it.id in removed })
            check(scopes.values.associateWith(store::list) == attachments)
            for (items in attachments.values) for (item in items) store.file(item)
            check(ClaimStore(context.noBackupFilesDir).use { it.list(MockBackend.ACTOR) } == reports - confirmedTests.toSet())
            android.util.Log.i("TestReportCleanup", "Removed ${removed.size} empty recognized test reports; retained all attachments and pending reports")
        }
    }
}
