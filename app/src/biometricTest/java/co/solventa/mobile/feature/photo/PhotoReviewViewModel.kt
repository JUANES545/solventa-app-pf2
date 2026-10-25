package co.solventa.mobile.feature.photo

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.mobile.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PhotoStep { INTRO, PERMISSION, CAMERA, CAPTURING, REVIEW, PROCESSING, COMPLETE }
internal enum class PhotoKind { DOCUMENT, SELFIE }

internal data class PhotoState(
    val actorId: String? = null,
    val open: Boolean = false,
    val generation: Long = 0,
    val step: PhotoStep = PhotoStep.INTRO,
    val preview: Bitmap? = null,
    val error: Int? = null,
    val kind: PhotoKind = PhotoKind.DOCUMENT,
    val documentReceived: Boolean = false,
    val selfieReceived: Boolean = false,
)

internal data class PhotoCapture(val generation: Long, val file: File)

// This controller only reviews a local file. It has no authentication or network dependency.
internal class PhotoReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val files = PhotoFiles(application.cacheDir)
    private val mutableState = MutableStateFlow(PhotoState())
    val state = mutableState.asStateFlow()
    private var candidate: PhotoCapture? = null
    private var job: Job? = null

    init { reset(false) }

    fun bindActor(actorId: String?) {
        if (state.value.actorId != actorId) reset(false, actorId = actorId)
    }

    fun open() = reset(true)
    fun close() = reset(false)

    fun pause() {
        if (state.value.open) reset(true, R.string.photo_interrupted)
    }

    fun startCamera() {
        if (!state.value.open || state.value.step !in listOf(PhotoStep.INTRO, PhotoStep.REVIEW)) return
        reset(true, kind = state.value.kind, documentReceived = state.value.documentReceived)
        if (state.value.error == null) mutableState.value = state.value.copy(step = PhotoStep.PERMISSION)
    }

    fun permissionResult(generation: Long, granted: Boolean) {
        if (!current(generation) || state.value.step != PhotoStep.PERMISSION) return
        mutableState.value = state.value.copy(
            step = if (granted) PhotoStep.CAMERA else PhotoStep.INTRO,
            error = if (granted) null else R.string.photo_denied)
    }

    fun beginCapture(generation: Long = state.value.generation): PhotoCapture? {
        if (!current(generation) || state.value.step != PhotoStep.CAMERA) return null
        return try {
            PhotoCapture(state.value.generation, files.create()).also {
                candidate = it
                mutableState.value = state.value.copy(step = PhotoStep.CAPTURING)
            }
        } catch (_: IOException) {
            reset(true, R.string.photo_storage)
            null
        } catch (_: SecurityException) {
            reset(true, R.string.photo_storage)
            null
        }
    }

    fun captured(request: PhotoCapture) {
        if (!current(request.generation) || candidate != request) {
            discardLate(request)
            return
        }
        if (state.value.step != PhotoStep.CAPTURING) return
        job = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { files.preview(request.file) }
                if (current(request.generation)) {
                    mutableState.value = state.value.copy(step = PhotoStep.REVIEW, preview = bitmap)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: PhotoFileProblem) {
                if (current(request.generation)) reset(true, problem.messageId)
            } catch (_: IOException) {
                if (current(request.generation)) reset(true, R.string.photo_invalid)
            } catch (_: SecurityException) {
                if (current(request.generation)) reset(true, R.string.photo_storage)
            } finally {
                if (!current(request.generation)) discardLate(request)
            }
        }
    }

    fun cameraFailed(generation: Long, message: Int = R.string.photo_camera_error, request: PhotoCapture? = null) {
        if (current(generation)) reset(true, message) else request?.let(::discardLate)
    }

    fun accept() {
        val request = candidate ?: return
        if (!current(request.generation) || state.value.step != PhotoStep.REVIEW || state.value.preview == null) return
        mutableState.value = state.value.copy(step = PhotoStep.PROCESSING)
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { files.preview(request.file) }
                // A fixed presentation step, not a liveness or identity decision.
                delay(600)
                if (current(request.generation)) {
                    files.remove(request.file)
                    candidate = null
                    if (state.value.kind == PhotoKind.DOCUMENT) {
                        reset(true, kind = PhotoKind.SELFIE, documentReceived = true)
                    } else if (state.value.documentReceived) {
                        mutableState.value = state.value.copy(step = PhotoStep.COMPLETE, preview = null, selfieReceived = true)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: PhotoFileProblem) {
                if (current(request.generation)) reset(true, problem.messageId)
            } catch (_: IOException) {
                if (current(request.generation)) reset(true, R.string.photo_storage)
            } catch (_: SecurityException) {
                if (current(request.generation)) reset(true, R.string.photo_storage)
            } finally {
                if (!current(request.generation)) discardLate(request)
            }
        }
    }

    private fun current(generation: Long) = state.value.open && state.value.generation == generation

    private fun reset(
        open: Boolean,
        error: Int? = null,
        actorId: String? = state.value.actorId,
        kind: PhotoKind = PhotoKind.DOCUMENT,
        documentReceived: Boolean = false,
    ) {
        val generation = state.value.generation + 1
        job?.cancel()
        candidate = null
        var message = error
        try {
            files.clear()
        } catch (_: IOException) {
            message = R.string.photo_storage
            Log.e("PhotoReview", "Temporary photo cleanup failed")
        } catch (_: SecurityException) {
            message = R.string.photo_storage
            Log.e("PhotoReview", "Temporary photo cleanup failed")
        }
        mutableState.value = PhotoState(actorId, open, generation, error = message,
            kind = if (message == null) kind else PhotoKind.DOCUMENT,
            documentReceived = message == null && documentReceived)
    }

    private fun discardLate(request: PhotoCapture) {
        try {
            files.remove(request.file)
        } catch (_: IOException) {
            Log.e("PhotoReview", "Could not remove a late temporary capture")
        } catch (_: SecurityException) {
            Log.e("PhotoReview", "Could not remove a late temporary capture")
        }
    }

    override fun onCleared() {
        reset(false)
        if (state.value.error == R.string.photo_storage) Log.e("PhotoReview", "Temporary photo cleanup failed")
        super.onCleared()
    }
}
