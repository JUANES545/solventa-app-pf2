package co.solventa.mobile.feature.photo

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import co.solventa.mobile.R
import co.solventa.mobile.feature.auth.AccessScreen
import co.solventa.mobile.feature.auth.AuthUiState
import co.solventa.mobile.feature.auth.AuthViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PhotoCompletionBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun staleCompletionCannotConfirmClosedIncompleteOrInvalidPhotos() {
        val complete = PhotoState(open = true, step = PhotoStep.COMPLETE, documentReceived = true, selfieReceived = true)
        val state = MutableStateFlow(complete)
        val model = mock(PhotoReviewViewModel::class.java)
        `when`(model.state).thenReturn(state.asStateFlow())
        doAnswer { state.value = state.value.copy(open = false); Unit }.`when`(model).close()
        val auth = mock(AuthViewModel::class.java)
        `when`(auth.state).thenReturn(MutableStateFlow(AuthUiState(screen = AccessScreen.REGISTER_PHOTOS)).asStateFlow())
        @Suppress("UNCHECKED_CAST")
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == PhotoReviewViewModel::class.java)
                return model as T
            }
        }
        ViewModelProvider(compose.activity, factory)[PhotoReviewViewModel::class.java]
        compose.setContent { PhotoReviewHost(compose.activity, auth, camera = { _, _ -> }) { _, _ -> } }
        val preview = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        for (invalid in listOf(
            complete.copy(open = false),
            complete.copy(step = PhotoStep.REVIEW),
            complete.copy(documentReceived = false),
            complete.copy(selfieReceived = false),
            complete.copy(preview = preview),
            complete.copy(error = R.string.photo_invalid),
        )) {
            compose.runOnIdle { state.value = complete }
            val click = requireNotNull(compose.onNodeWithText("Continuar").performScrollTo()
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            clearInvocations(auth)
            // A queued click must recheck the current state, not the state last drawn.
            compose.runOnIdle { state.value = invalid; click() }
            verify(auth).cancelRegistrationPhotos()
            verify(auth, never()).completeRegistrationPhotos()
        }
        preview.recycle()
    }
}
