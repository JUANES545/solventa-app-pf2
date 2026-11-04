package co.solventa.mobile.feature.evidence

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class EvidenceUploader(private val store: EvidenceStore, private val transport: EvidenceTransport) {
    private val mutex = Mutex()

    suspend fun retry(scope: EvidenceScope, isCurrentAccount: () -> Boolean, progress: (String, Long, Long) -> Unit) {
        mutex.withLock {
            for (item in store.list(scope)) {
                currentCoroutineContext().ensureActive()
                if (!isCurrentAccount()) throw EvidenceProblem(EvidenceError.SESSION)
                if (item.status != EvidenceStatus.PENDING) continue
                // Reconcile first: the previous process may have died after the server committed.
                try {
                    val receipt = transport.confirmed(scope, item.id) ?: run {
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentAccount()) throw EvidenceProblem(EvidenceError.SESSION)
                        transport.upload(item, store.file(item)) { bytes ->
                            if (bytes !in 0..item.bytes) throw EvidenceProblem(EvidenceError.PROTOCOL)
                            progress(item.id, bytes, item.bytes)
                        }
                    }
                    // Persist an already confirmed result even if the actor changed during the request.
                    store.confirm(item, receipt)
                } catch (error: EvidenceHttpProblem) {
                    if (!error.permanent) throw error
                    store.reject(item, error.reason)
                }
            }
        }
    }
}
