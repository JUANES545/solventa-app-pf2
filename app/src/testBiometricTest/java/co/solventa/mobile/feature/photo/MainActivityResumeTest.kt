package co.solventa.mobile.feature.photo

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import co.solventa.mobile.MainActivity
import co.solventa.mobile.SolventaApplication
import co.solventa.mobile.feature.auth.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = SolventaApplication::class, sdk = [35])
class MainActivityResumeTest {
    @get:Rule val compose = createComposeRule()
    private var grant: AuthGrant? = null
    private var expired = false
    private var calls = 0
    private var registerCalls = 0
    private val user = AuthUser("account-a", "Cliente", "synthetic@example.org")
    private val api = object : AuthApi {
        override fun register(name: String, email: String, password: String): AuthUser {
            calls++
            registerCalls++
            error("Unexpected registration")
        }
        override fun login(email: String, password: String): AuthGrant {
            calls++
            return AuthGrant(user, "access", "refresh", System.currentTimeMillis() + 3_600_000,
                System.currentTimeMillis() + 86_400_000)
        }
        override fun current(token: String): AuthUser {
            calls++
            if (expired) throw AuthHttpError(401)
            return user
        }
        override fun refresh(token: String): AuthGrant {
            calls++
            if (expired) throw AuthHttpError(401)
            return requireNotNull(grant)
        }
        override fun logout(accessToken: String, refreshToken: String) = error("Unexpected logout")
    }
    private val store = object : AuthStore {
        override fun read() = grant
        override fun write(grant: AuthGrant) { this@MainActivityResumeTest.grant = grant }
        override fun clear() { grant = null }
    }
    private val auth = AuthViewModel(AuthRepository(api, store))

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            condition()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun factory() = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return when (modelClass) {
                AuthViewModel::class.java -> auth
                PhotoReviewViewModel::class.java -> PhotoReviewViewModel(org.robolectric.RuntimeEnvironment.getApplication())
                else -> error("Unexpected ViewModel")
            } as T
        }
    }

    @Test fun authenticatedResumeRevalidatesAndClosesPhotoAfterSessionRevocation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        ViewModelProvider(activity, factory())[AuthViewModel::class.java]
        ViewModelProvider(activity, factory())[PhotoReviewViewModel::class.java]
        auth.login(user.email, "password")
        await { auth.state.value.user != null && !auth.state.value.loading }
        controller.create().start().resume().visible()
        await { !auth.state.value.loading }
        compose.waitForIdle()
        val photo = ViewModelProvider(activity)[PhotoReviewViewModel::class.java]
        assertSame("The Activity and photo controller must use the same Application",
            activity.application, photo.getApplication<android.app.Application>())
        photo.bindActor(user.id)
        photo.open()
        shadowOf(Looper.getMainLooper()).idle()
        val file = PhotoFiles(activity.cacheDir).create()
        assertTrue(photo.state.value.open)
        compose.onNodeWithText("Abrir cámara").assertIsDisplayed()
        controller.pause().stop()
        val previousCalls = calls
        expired = true
        assertFalse(ViewModelProvider(activity)[BiometricViewModel::class.java].state.value.busy)
        assertTrue(photo.state.value.open)
        controller.start().resume().visible()
        await { auth.state.value.user == null && !photo.state.value.open && photo.state.value.actorId == null }
        assertTrue(calls > previousCalls)
        assertNull(grant)
        assertFalse(file.exists())
        assertNull(photo.state.value.preview)
        assertEquals(AuthError.EXPIRED, auth.state.value.error)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun guestResumeWithOpenPhotoAndEmptyStoreDoesNotCallHttp() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        ViewModelProvider(activity, factory())[AuthViewModel::class.java]
        ViewModelProvider(activity, factory())[PhotoReviewViewModel::class.java]
        controller.create().start().resume().visible()
        await { !auth.state.value.loading }
        val photo = ViewModelProvider(activity)[PhotoReviewViewModel::class.java]
        photo.open()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().start().resume().visible()
        await { !auth.state.value.loading }
        assertTrue(photo.state.value.open)
        assertNull(auth.state.value.user)
        assertNull(grant)
        assertEquals(0, calls)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun backgroundDuringRegistrationDiscardsPhotosAndDraftWithoutPostOrRestore() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        ViewModelProvider(activity, factory())[AuthViewModel::class.java]
        ViewModelProvider(activity, factory())[PhotoReviewViewModel::class.java]
        controller.create().start().resume().visible()
        await { !auth.state.value.loading }
        auth.showRegistration()
        auth.prepareRegistration(user.name, user.email, "password", "password", withPhotos = true)
        auth.continueRegistration(true)
        val photo = ViewModelProvider(activity)[PhotoReviewViewModel::class.java]
        await { photo.state.value.open && auth.state.value.screen == AccessScreen.REGISTER_PHOTOS }
        val file = PhotoFiles(activity.cacheDir).create()
        controller.pause().stop()
        controller.start().resume().visible()
        await { auth.state.value.screen == AccessScreen.REGISTER && !photo.state.value.open }
        assertFalse(file.exists())
        assertNull(photo.state.value.preview)
        assertFalse(photo.state.value.documentReceived)
        assertFalse(photo.state.value.selfieReceived)
        auth.completeRegistrationPhotos()
        auth.confirmRegistration()
        assertEquals(AccessScreen.REGISTER, auth.state.value.screen)
        assertEquals(0, calls)
        assertEquals(0, registerCalls)
        assertNull(auth.state.value.user)
        assertNull(grant)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

}
