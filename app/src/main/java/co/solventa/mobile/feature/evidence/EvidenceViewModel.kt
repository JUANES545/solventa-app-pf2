package co.solventa.mobile.feature.evidence

import android.app.Application
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.AuthProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
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
    val items: List<EvidenceItem> = emptyList(),
    val location: EvidenceLocation? = null,
    val locationEnabled: Boolean = false,
    val busy: Boolean = false,
    val error: EvidenceError? = null,
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
        close()
        mutable.value = state.value.copy(actorId = actorId, open = true)
        operation {
            val configuration = api.configuration(actorId)
            val claims = api.claims(actorId)
            update { copy(configuration = configuration, claims = claims) }
        }
    }

    fun select(claim: EvidenceClaim) {
        val current = state.value
        val actor = current.actorId ?: return
        val config = current.configuration ?: return
        if (current.busy || claim !in current.claims) return
        operation {
            val scope = EvidenceScope(actor, claim.id)
            val ids = api.confirmedIds(scope)
            val items = store.list(scope)
            update { copy(scope = scope, policy = config.policy, items = items, confirmedRemoteIds = ids) }
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
    }

    fun camera(kind: EvidenceKind) {
        val current = state.value
        if (current.scope == null || current.busy) return
        if (kind !in checkNotNull(current.policy).maxBytes) {
            fail(EvidenceError.FORMAT); return
        }
        val count = current.items.count { it.status != EvidenceStatus.CONFIRMED } +
            (current.confirmedRemoteIds + current.items.mapNotNull { it.remoteId }).size
        if (count >= current.policy.maxCount) { fail(EvidenceError.COUNT); return }
        current.draft?.let(::discard)
        mutable.value = current.copy(step = EvidenceStep.CAMERA, kind = kind, draft = null, preview = null,
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
        if (draft != state.value.draft || draft.generation != state.value.generation) { discard(draft); return }
        val policy = checkNotNull(state.value.policy)
        operation {
            val preview = media.inspect(draft.file, draft.kind, policy)
            update { copy(step = EvidenceStep.REVIEW, preview = preview) }
        }
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
        mutable.value = current.copy(step = EvidenceStep.LIST, draft = null, preview = null,
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
        mutable.value = state.value.copy(step = EvidenceStep.LIST, draft = null, preview = null, error = error)
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

    private fun operation(block: suspend Operation.() -> Unit) {
        if (state.value.busy) return
        val generation = state.value.generation
        mutable.value = state.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Operation(generation).block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: EvidenceProblem) {
                Operation(generation).update { copy(error = error.reason) }
            } catch (_: AuthHttpError) {
                Operation(generation).update { copy(error = EvidenceError.SESSION) }
            } catch (_: AuthProblem) {
                Operation(generation).update { copy(error = EvidenceError.SESSION) }
            } catch (_: org.json.JSONException) {
                Operation(generation).update { copy(error = EvidenceError.PROTOCOL) }
            } catch (_: IllegalArgumentException) {
                Operation(generation).update { copy(error = EvidenceError.PROTOCOL) }
            } catch (_: SQLiteException) {
                Operation(generation).update { copy(error = EvidenceError.STORAGE) }
            } catch (_: IOException) {
                Operation(generation).update { copy(error = EvidenceError.STORAGE) }
            } catch (_: SecurityException) {
                Operation(generation).update { copy(error = EvidenceError.PERMISSION) }
            } finally {
                Operation(generation).update { copy(busy = false) }
            }
        }
    }

    private inner class Operation(private val generation: Long) {
        fun update(change: EvidenceState.() -> EvidenceState) {
            while (true) {
                val current = mutable.value
                if (current.generation != generation) return
                if (mutable.compareAndSet(current, current.change())) return
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
