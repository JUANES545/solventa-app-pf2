package co.solventa.mobile.feature.identity

import co.solventa.mobile.feature.evidence.EvidenceError
import co.solventa.mobile.feature.evidence.EvidenceHttpProblem
import co.solventa.mobile.feature.evidence.EvidenceProblem
import co.solventa.mobile.feature.auth.AuthError
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

internal class MemoryIdentityAttempts : IdentityAttempts {
    val values = mutableMapOf<String, IdentityAttempt>()
    var failWrite = false
    override fun read(actor: String) = values[actor]
    override fun save(actor: String, attempt: IdentityAttempt) {
        if (failWrite) throw IdentityStorageProblem()
        values[actor] = attempt
    }
    override fun clear(actor: String, attempt: IdentityAttempt) { values.remove(actor, attempt) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class Api : IdentityApi {
        var value = pendingIdentity
        val submissions = mutableListOf<Triple<String, String, String>>()
        var failure: Exception? = null
        var historical: IdentityVerification? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun start(actor: String) = value
        override suspend fun current(actor: String, id: String): IdentityVerification {
            gate?.let { withContext(NonCancellable) { it.await() } }
            failure?.let { throw it }
            return value
        }
        override suspend fun submit(actor: String, id: String, attemptId: String): IdentityVerification {
            submissions += Triple(actor, id, attemptId)
            failure?.let { throw it }
            return historical ?: value
        }
    }

    @Test fun pendingRejectionRetryApprovalAndExhaustionOnlyFollowServerState() = runTest(dispatcher) {
        val api = Api()
        val model = IdentityViewModel(api, MemoryIdentityAttempts(), dispatcher)
        model.show("actor-a"); advanceUntilIdle()
        assertEquals(pendingIdentity, model.state.value.verification)
        api.value = rejectedIdentity
        model.submit(); model.submit(); advanceUntilIdle()
        assertEquals(rejectedIdentity, model.state.value.verification)
        api.value = approvedIdentity
        model.submit(); advanceUntilIdle()
        assertEquals(approvedIdentity, model.state.value.verification)
        assertEquals(2, api.submissions.size)
        assertNotEquals(api.submissions[0].third, api.submissions[1].third)
        model.submit(); advanceUntilIdle()
        assertEquals(2, api.submissions.size)
        api.value = rejectedIdentity.copy(attempts = 3, retryAllowed = false)
        model.refresh(); advanceUntilIdle()
        model.submit(); advanceUntilIdle()
        assertEquals(2, api.submissions.size)
        model.close()
        model.show("actor-a"); advanceUntilIdle()
        assertEquals(3, model.state.value.verification?.attempts)
    }

    @Test fun ambiguousResultSurvivesRecreationAndReplaysSameKeyBeforeReadingCurrentState() = runTest(dispatcher) {
        val api = Api()
        val attempts = MemoryIdentityAttempts()
        val model = IdentityViewModel(api, attempts, dispatcher)
        model.show("actor-a"); advanceUntilIdle()
        api.failure = EvidenceProblem(EvidenceError.NETWORK)
        model.submit(); advanceUntilIdle()
        assertEquals(IdentityError.NETWORK, model.state.value.error)
        assertNull(model.state.value.verification)
        val saved = attempts.values.getValue("actor-a")
        val recreated = IdentityViewModel(api, attempts, dispatcher)
        api.failure = null
        api.historical = rejectedIdentity
        api.value = approvedIdentity
        recreated.show("actor-a"); advanceUntilIdle()
        assertEquals(listOf(saved.attemptId, saved.attemptId), api.submissions.map { it.third })
        assertEquals(approvedIdentity, recreated.state.value.verification)
        assertTrue(attempts.values.isEmpty())
    }

    @Test fun lostCanonicalReadKeepsKeyAndStorageFailurePreventsSending() = runTest(dispatcher) {
        val attempts = MemoryIdentityAttempts()
        val api = object : IdentityApi {
            var failGet = false
            val keys = mutableListOf<String>()
            override suspend fun start(actor: String) = pendingIdentity
            override suspend fun current(actor: String, id: String): IdentityVerification {
                if (failGet) throw EvidenceProblem(EvidenceError.NETWORK)
                return pendingIdentity
            }
            override suspend fun submit(actor: String, id: String, attemptId: String): IdentityVerification {
                keys += attemptId
                failGet = true
                return rejectedIdentity
            }
        }
        val model = IdentityViewModel(api, attempts, dispatcher)
        model.show("actor-a"); advanceUntilIdle()
        attempts.failWrite = true
        model.submit(); advanceUntilIdle()
        assertEquals(IdentityError.STORAGE, model.state.value.error)
        assertTrue(api.keys.isEmpty())
        attempts.failWrite = false
        model.refresh(); advanceUntilIdle()
        model.submit(); advanceUntilIdle()
        assertEquals(IdentityError.NETWORK, model.state.value.error)
        assertEquals(api.keys.single(), attempts.values.getValue("actor-a").attemptId)
        model.refresh(); advanceUntilIdle()
        assertEquals(1, api.keys.distinct().size)
    }

    @Test fun logoutActorChangeAndPauseRejectLateResultsAndNeverReplayAnotherActorsAttempt() = runTest(dispatcher) {
        for (leave in listOf<(IdentityViewModel) -> Unit>(
            { it.bindActor(null) }, { it.bindActor("actor-b") }, { it.pause() }, { it.close() },
        )) {
            val api = Api().apply { value = approvedIdentity; gate = CompletableDeferred() }
            val attempts = MemoryIdentityAttempts()
            val model = IdentityViewModel(api, attempts, dispatcher)
            model.show("actor-a"); runCurrent()
            leave(model)
            assertNull(model.state.value.verification)
            api.gate!!.complete(Unit); advanceUntilIdle()
            assertNull(model.state.value.verification)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.error)
        }
        val api = Api()
        val attempts = MemoryIdentityAttempts()
        attempts.values["actor-a"] = IdentityAttempt(pendingIdentity.id, "be794f6e-e01d-4d2f-ad25-c9bf118c117a")
        val model = IdentityViewModel(api, attempts, dispatcher)
        model.show("actor-b"); advanceUntilIdle()
        assertTrue(api.submissions.isEmpty())
        assertNotNull(attempts.values["actor-a"])
    }

    @Test fun disabledServiceAndTerminalConflictAreExplicitAndRefreshable() = runTest(dispatcher) {
        val api = Api()
        val attempts = MemoryIdentityAttempts()
        val model = IdentityViewModel(api, attempts, dispatcher)
        api.failure = EvidenceHttpProblem(503, "servicio_no_disponible", false, EvidenceError.NETWORK)
        model.show("actor-a"); advanceUntilIdle()
        assertEquals(IdentityError.UNAVAILABLE, model.state.value.error)
        assertNull(model.state.value.verification)
        api.failure = null
        model.refresh(); advanceUntilIdle()
        val conflictApi = object : IdentityApi by api {
            override suspend fun submit(actor: String, id: String, attemptId: String): IdentityVerification {
                api.value = approvedIdentity
                throw EvidenceHttpProblem(409, "transicion_invalida", false, EvidenceError.PROTOCOL)
            }
        }
        val concurrent = IdentityViewModel(conflictApi, attempts, dispatcher)
        concurrent.show("actor-a"); advanceUntilIdle()
        concurrent.submit(); advanceUntilIdle()
        assertEquals(IdentityError.CONFLICT, concurrent.state.value.error)
        assertTrue(attempts.values.isEmpty())
        concurrent.refresh(); advanceUntilIdle()
        assertEquals(approvedIdentity, concurrent.state.value.verification)
    }

    @Test fun sessionValidationNetworkExpiryAndStorageFailuresAreExplicitWithoutApproval() = runTest(dispatcher) {
        for ((failure, expected) in listOf(
            IOException("Synthetic disconnected auth") to IdentityError.NETWORK,
            AuthProblem(AuthError.EXPIRED) to IdentityError.SESSION,
            AuthProblem(AuthError.STORAGE) to IdentityError.STORAGE,
            AuthHttpError(401) to IdentityError.SESSION,
            AuthHttpError(503) to IdentityError.UNAVAILABLE,
        )) {
            val api = Api().apply { this.failure = failure }
            val model = IdentityViewModel(api, MemoryIdentityAttempts(), dispatcher)
            model.show("actor-a"); advanceUntilIdle()
            assertEquals(expected, model.state.value.error)
            assertNull(model.state.value.verification)
            assertTrue(api.submissions.isEmpty())
        }
    }
}
