package co.solventa.mobile.feature.evidence

import android.app.Application
import android.util.Log
import android.net.Uri
import co.solventa.mobile.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class EvidenceStep { LIST, CAMERA, CAPTURING, REVIEW }
internal data class EvidenceDraft(val file: File, val kind: EvidenceKind, val capturedAt: Instant, val generation: Long)
internal data class EvidenceState(
    val actorId: String? = null,
    val open: Boolean = false,
    val claims: List<EvidenceClaim> = emptyList(),
    val configuration: EvidenceConfiguration? = null,
    val confirmedRemoteIds: Set<String> = emptySet(),
    val scope: EvidenceScope? = null,
    val policy: EvidencePolicy? = null,
    val generation: Long = 0,
    val step: EvidenceStep = EvidenceStep.LIST,
    val kind: EvidenceKind = EvidenceKind.PHOTO,
    val draft: EvidenceDraft? = null,
    val preview: EvidencePreview? = null,
    val retainedDraft: EvidenceDraft? = null,
    val retainedPreview: EvidencePreview? = null,
    val items: List<EvidenceItem> = emptyList(),
    val location: EvidenceLocation? = null,
    val locationEnabled: Boolean = false,
    val busy: Boolean = false,
    val loadingClaims: Boolean = false,
    val error: EvidenceError? = null,
    val errorHttpStatus: Int? = null,
    val progress: Pair<Long, Long>? = null,
)

@HiltViewModel
internal class EvidenceViewModel @Inject constructor(
    application: Application,
    private val api: MobileEvidenceApi,
) : AndroidViewModel(application) {
    private val media = EvidenceMedia(application.cacheDir)
    private val storage = lazy { EvidenceStore(application.noBackupFilesDir) }
    private val store by storage
    private val mutable = MutableStateFlow(EvidenceState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun bindActor(actorId: String?) {
        if (state.value.actorId != actorId) close()
    }

    fun showClaims(actorId: String) {
        if (state.value.open && state.value.actorId == actorId && state.value.scope == null) {
            if (!state.value.busy) loadClaims(actorId)
            return
        }
        close()
        mutable.value = state.value.copy(actorId = actorId, open = true)
        loadClaims(actorId)
    }

    private fun loadClaims(actorId: String) {
        operation(loadingClaims = true) {
            val claims = api.claims(actorId)
            val configuration = if (claims.isEmpty()) null else api.configuration(actorId)
            update { copy(configuration = configuration, claims = claims) }
        }
    }

    fun select(claim: EvidenceClaim) {
        val current = state.value
        val actor = current.actorId ?: return
        val config = current.configuration ?: return
        if (current.busy || claim !in current.claims) return
        operation(loadingClaims = true) {
            val scope = EvidenceScope(actor, claim.id)
            val ids = api.confirmedIds(scope)
            val items = store.list(scope)
            update { copy(scope = scope, policy = config.policy, items = items, confirmedRemoteIds = ids) }
        }

    }

    fun showClaim(actorId: String, claim: EvidenceClaim) {
        close()
        mutable.value = state.value.copy(actorId = actorId, open = true, claims = listOf(claim))
        operation(loadingClaims = true) {
            val configuration = api.configuration(actorId)
            val scope = EvidenceScope(actorId, claim.id)
            val ids = api.confirmedIds(scope)
            update { copy(configuration = configuration, scope = scope, policy = configuration.policy,
                items = store.list(scope), confirmedRemoteIds = ids) }
        }
    }

    fun open(scope: EvidenceScope, policy: EvidencePolicy, confirmedRemoteIds: Set<String>) {
        close()
        mutable.value = state.value.copy(actorId = scope.actorId, open = true, scope = scope, policy = policy,
            confirmedRemoteIds = confirmedRemoteIds)
        operation { store.list(scope).let { items -> update { copy(items = items) } } }
    }

    fun close() {
        job?.cancel()
        val previous = state.value
        mutable.value = EvidenceState(generation = previous.generation + 1)
        previous.draft?.let { discard(it) }
        previous.retainedDraft?.let(::discard)
    }

    fun camera(kind: EvidenceKind) {
        val current = state.value
        if (current.scope == null || current.busy || current.step !in setOf(EvidenceStep.LIST, EvidenceStep.REVIEW)) return
        if (kind !in checkNotNull(current.policy).maxBytes) {
            fail(EvidenceError.FORMAT); return
        }
        val count = current.items.count { it.status != EvidenceStatus.CONFIRMED } +
            (current.confirmedRemoteIds + current.items.mapNotNull { it.remoteId }).size
        if (count >= current.policy.maxCount) { fail(EvidenceError.COUNT); return }
        mutable.value = current.copy(step = EvidenceStep.CAMERA, kind = kind, draft = null, preview = null,
            retainedDraft = current.draft, retainedPreview = current.preview,
            generation = current.generation + 1, error = null)
    }

    fun beginCapture(): EvidenceDraft? {
        val current = state.value
        if (current.step != EvidenceStep.CAMERA || current.busy || current.scope == null) return null
        return try {
            EvidenceDraft(media.create(current.kind), current.kind, Instant.now(), current.generation).also {
                mutable.value = current.copy(step = EvidenceStep.CAPTURING, draft = it, error = null)
            }
        } catch (_: IOException) {
            fail(EvidenceError.STORAGE); null
        } catch (_: SecurityException) {
            fail(EvidenceError.STORAGE); null
        }
    }

    fun captured(draft: EvidenceDraft) {
        val current = state.value
        if (draft != current.draft || draft.generation != current.generation) { discard(draft); return }
        val policy = checkNotNull(current.policy)
        operation {
            val preview = try {
                media.inspect(draft.file, draft.kind, policy)
            } catch (error: Exception) {
                val reason = evidenceOperationError(error)
                discard(draft)
                update { copy(step = if (current.retainedDraft == null) EvidenceStep.LIST else EvidenceStep.REVIEW,
                    kind = current.retainedDraft?.kind ?: kind,
                    draft = current.retainedDraft, preview = current.retainedPreview,
                    retainedDraft = null, retainedPreview = null, error = reason) }
                return@operation
            }
            if (update { copy(step = EvidenceStep.REVIEW, preview = preview, retainedDraft = null, retainedPreview = null) }) {
                current.retainedDraft?.let(::discard)
            } else {
                preview.bitmap?.recycle()
                discard(draft)
            }
        }

    }

    fun importPhoto(uri: Uri, scope: EvidenceScope, generation: Long) =
        importFile(uri, EvidenceKind.PHOTO, scope, generation)

    fun importFile(uri: Uri, kind: EvidenceKind, scope: EvidenceScope, generation: Long) {
        val current = state.value
        if (current.scope != scope || current.generation != generation || current.busy ||
            current.step !in setOf(EvidenceStep.LIST, EvidenceStep.REVIEW)) return
        if (uri.scheme != "content") { fail(EvidenceError.FORMAT); return }
        val policy = current.policy ?: return
        val count = current.items.count { it.status != EvidenceStatus.CONFIRMED } +
            (current.confirmedRemoteIds + current.items.mapNotNull { it.remoteId }).size
        if (count >= policy.maxCount) { fail(EvidenceError.COUNT); return }
        operation {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw EvidenceProblem(EvidenceError.STORAGE)
            val file = input.use { media.importFile(it, kind, policy) }
            try {
                val preview = media.inspect(file, kind, policy)
                val draft = EvidenceDraft(file, kind, Instant.now(), generation)
                if (!update { copy(step = EvidenceStep.REVIEW, kind = kind, draft = draft, preview = preview) }) {
                    preview.bitmap?.recycle()
                    media.delete(file)
                } else current.draft?.let(::discard)
            } catch (error: Exception) {
                if (file.exists()) media.delete(file)
                throw error
            }
        }
    }

    fun dismissLocationError() {
        if (state.value.error == EvidenceError.LOCATION) mutable.value = state.value.copy(error = null)
    }

    fun crop(crop: EvidenceCrop) {
        val current = state.value
        val draft = current.draft ?: return
        if (current.step != EvidenceStep.REVIEW || current.busy || draft.kind == EvidenceKind.VIDEO) return
        val policy = checkNotNull(current.policy)
        operation {
            media.crop(draft.file, crop, policy, draft.kind)
            val preview = media.inspect(draft.file, draft.kind, policy)
            update { copy(preview = preview) }
        }
    }

    fun accept(locationPermission: Boolean) {
        val current = state.value
        val draft = current.draft ?: return
        val scope = current.scope ?: return
        val policy = current.policy ?: return
        if (current.step != EvidenceStep.REVIEW || current.preview == null || current.busy) return
        val ids = current.confirmedRemoteIds
        operation {
            // Re-encode photos even without a crop so EXIF cannot leak location without consent.
            if (draft.kind != EvidenceKind.VIDEO) media.crop(draft.file, EvidenceCrop(), policy, draft.kind)
            else media.stripVideoMetadata(draft.file, policy)
            val inspected = media.inspect(draft.file, draft.kind, policy)
            store.enqueue(scope, draft.kind, draft.file, draft.capturedAt,
                current.location.takeIf { locationPermission && current.locationEnabled }, inspected.durationMillis, policy, ids)
            val items = store.list(scope)
            update { copy(step = EvidenceStep.LIST, draft = null, preview = null, items = items) }
            media.delete(draft.file)
        }
    }

    fun back() {
        val current = state.value
        if (current.busy) return
        current.draft?.let(::discard)
        val restored = current.retainedDraft
        mutable.value = current.copy(step = if (restored == null) EvidenceStep.LIST else EvidenceStep.REVIEW,
            kind = restored?.kind ?: current.kind, draft = restored, preview = current.retainedPreview,
            retainedDraft = null, retainedPreview = null,
            generation = current.generation + 1, error = null)
    }

    fun location(location: EvidenceLocation?) {
        mutable.value = state.value.copy(location = location.takeIf { state.value.locationEnabled })
    }

    fun enableLocation(enabled: Boolean) {
        mutable.value = state.value.copy(locationEnabled = enabled, location = state.value.location.takeIf { enabled })
    }

    fun fail(error: EvidenceError) {
        mutable.value = state.value.copy(error = error)
    }

    fun cameraFailed(draft: EvidenceDraft?, generation: Long, error: EvidenceError = EvidenceError.CAMERA) {
        if (generation != state.value.generation) { draft?.let(::discard); return }
        draft?.let(::discard)
        val restored = state.value.retainedDraft
        mutable.value = state.value.copy(step = if (restored == null) EvidenceStep.LIST else EvidenceStep.REVIEW,
            kind = restored?.kind ?: state.value.kind, draft = restored, preview = state.value.retainedPreview, error = error,
            retainedDraft = null, retainedPreview = null,
            generation = state.value.generation + 1)
    }

    fun retry(transport: EvidenceTransport, isCurrentAccount: () -> Boolean) {
        val current = state.value
        val scope = current.scope ?: return
        if (current.busy) return
        operation {
            try {
                EvidenceUploader(store, transport).retry(scope, isCurrentAccount) { _, bytes, total ->
                    update { copy(progress = bytes to total) }
                }

            } finally {
                val items = store.list(scope)
                update { copy(items = items, progress = null) }
            }
        }
    }

    fun retry() {
        val current = state.value
        val configuration = current.configuration ?: return
        if (current.scope == null || current.busy || current.step != EvidenceStep.LIST ||
            current.items.none { it.status == EvidenceStatus.PENDING }) return
        retry(api.transport(store, configuration)) { state.value.actorId == current.actorId && state.value.open }
    }

    fun removeRejected(item: EvidenceItem) {
        val current = state.value
        val configuration = current.configuration ?: return
        if (current.busy || item.scope != current.scope || item.status != EvidenceStatus.REJECTED) return
        operation {
            val receipt = api.transport(store, configuration).cancel(item)
            if (receipt != null) store.confirm(item, receipt)
            else store.removeRejected(item)
            val items = store.list(item.scope)
            update { copy(items = items) }
        }

    }

    fun remove(item: EvidenceItem) {
        val current = state.value
        if (current.busy || item.scope != current.scope || item !in current.items) return
        if (item.status == EvidenceStatus.REJECTED) {
            removeRejected(item)
            return
        }
        if (item.status == EvidenceStatus.CONFIRMED) {
            removeDemoConfirmed(checkNotNull(item.remoteId))
            return
        }
        if (item.status != EvidenceStatus.PENDING) return
        operation {
            val configuration = current.configuration
            val receipt = if (store.uploadId(item) == null) null else {
                if (configuration == null) throw EvidenceProblem(EvidenceError.PROTOCOL)
                api.transport(store, configuration).cancel(item)
            }
            if (receipt == null) store.removePending(item) else store.confirm(item, receipt)
            update { copy(items = store.list(item.scope)) }
        }
    }

    fun removeDemoConfirmed(remoteId: String) {
        val current = state.value
        val scope = current.scope ?: return
        if (BuildConfig.BUILD_TYPE != "mock" || current.busy ||
            remoteId !in current.confirmedRemoteIds && current.items.none { it.remoteId == remoteId }) return
        operation {
            api.removeDemoConfirmed(scope, remoteId)
            store.list(scope).filter { it.remoteId == remoteId }.forEach(store::removeDemoConfirmed)
            update { copy(items = store.list(scope), confirmedRemoteIds = confirmedRemoteIds - remoteId) }
        }
    }

    fun refresh() {
        val current = state.value
        if (current.busy || current.step != EvidenceStep.LIST) return
        val scope = current.scope
        if (scope == null) current.actorId?.let(::loadClaims)
        else operation(loadingClaims = true) {
            val ids = api.confirmedIds(scope)
            update { copy(items = store.list(scope), confirmedRemoteIds = ids) }
        }
    }

    private fun operation(loadingClaims: Boolean = false, block: suspend Operation.() -> Unit) {
        if (state.value.busy) return
        val generation = state.value.generation
        mutable.value = state.value.copy(busy = true, loadingClaims = loadingClaims, error = null, errorHttpStatus = null)
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Operation(generation).block() }
            } catch (error: Exception) {
                val reason = evidenceOperationError(error)
                Operation(generation).update { copy(error = reason, errorHttpStatus = (error as? EvidenceHttpProblem)?.status) }
            } finally {
                Operation(generation).update { copy(busy = false, loadingClaims = false) }
            }
        }
    }

    private inner class Operation(private val generation: Long) {
        fun update(change: EvidenceState.() -> EvidenceState): Boolean {
            while (true) {
                val current = mutable.value
                if (current.generation != generation) return false
                if (mutable.compareAndSet(current, current.change())) return true
            }
        }
    }

    private fun discard(draft: EvidenceDraft) {
        try {
            media.delete(draft.file)
        } catch (_: IOException) {
            Log.e("Evidence", "Draft cleanup failed")
            fail(EvidenceError.STORAGE)
        } catch (_: SecurityException) {
            Log.e("Evidence", "Draft cleanup denied")
            fail(EvidenceError.STORAGE)
        }
    }

    override fun onCleared() {
        close()
        val active = viewModelScope.coroutineContext[Job]
        if (active == null) {
            if (storage.isInitialized()) store.close()
        } else {
            active.invokeOnCompletion { if (storage.isInitialized()) store.close() }
        }
        super.onCleared()
    }
}
