package co.solventa.mobile

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import co.solventa.mobile.feature.auth.AuthApi
import co.solventa.mobile.feature.auth.AuthGrant
import co.solventa.mobile.feature.auth.AuthRepository
import co.solventa.mobile.feature.auth.AuthStore
import co.solventa.mobile.feature.auth.AuthViewModel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application = SolventaApplication::class, sdk = [26, 31, 35])
class LaunchConfigurationTest {
    @get:Rule val compose = createComposeRule()
    private val application get() = RuntimeEnvironment.getApplication()

    @Test fun launcherRequestsPortraitWithoutChangingTheApplicationTheme() {
        val info = application.packageManager.getActivityInfo(
            ComponentName(application, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation)
        assertEquals(R.style.Theme_Solventa_Starting, info.theme)
        assertEquals(R.style.Theme_Solventa, application.applicationInfo.theme)
        assertTrue(info.exported)
    }

    @Test fun launcherAndRoundIconUseTheSameAdaptiveBrandForeground() {
        assertEquals(R.mipmap.ic_launcher, application.applicationInfo.icon)
        for (iconId in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = application.getDrawable(iconId)
            assertTrue(icon is AdaptiveIconDrawable)
            assertNotNull((icon as AdaptiveIconDrawable).foreground)
            assertNotNull(icon.background)
        }
        assertNotNull(application.getDrawable(R.drawable.solventa_brand))
    }

    @Test fun startingThemeUsesSystemPaletteAndRestoresTheContentTheme() {
        for (night in listOf(false, true)) {
            val configuration = Configuration(application.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = application.createConfigurationContext(configuration)
            val theme = ContextThemeWrapper(context, R.style.Theme_Solventa_Starting).theme
            fun attribute(id: Int): TypedValue = TypedValue().also {
                assertTrue(theme.resolveAttribute(id, it, true))
            }
            assertEquals(if (night) Color.rgb(11, 15, 25) else Color.rgb(247, 249, 252),
                attribute(androidx.core.splashscreen.R.attr.windowSplashScreenBackground).data)
            assertEquals(R.drawable.solventa_brand,
                attribute(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon).resourceId)
            assertEquals(R.style.Theme_Solventa,
                attribute(androidx.core.splashscreen.R.attr.postSplashScreenTheme).resourceId)
            assertEquals(!night, context.resources.getBoolean(R.bool.solventa_launch_light_bars))
            assertEquals(if (android.os.Build.VERSION.SDK_INT < 27) Color.BLACK else
                if (night) Color.rgb(11, 15, 25) else Color.rgb(247, 249, 252),
                attribute(android.R.attr.navigationBarColor).data)
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                val lightNavigation = attribute(android.R.attr.windowLightNavigationBar)
                assertEquals(TypedValue.TYPE_INT_BOOLEAN, lightNavigation.type)
                assertEquals(!night, lightNavigation.data != 0)
            }
            assertEquals(if (night) Color.rgb(0, 210, 255) else Color.rgb(18, 59, 109),
                context.getColor(R.color.solventa_launch_primary))
        }
    }

    @Test fun disabledSystemAnimationsRemoveSplashImmediately() = withAnimatorScale(0f) {
        val view = View(application)
        var removals = 0
        fadeSplashExit(view) { removals++ }
        assertEquals(1, removals)
        assertEquals(1f, view.alpha, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(1, removals)
    }

    @Test fun enabledSystemAnimationsFadeAndRemoveSplashOnce() = withAnimatorScale(1f) {
        val view = View(application)
        var removals = 0
        fadeSplashExit(view) { removals++ }
        assertEquals(0, removals)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(0f, view.alpha, 0f)
        assertEquals(1, removals)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(1, removals)
    }

    @Test fun coldWarmAndSavedStateLaunchStillRestoreWithoutCreatingASession() = withAnimatorScale(0f) {
        val reads = AtomicInteger()
        val store = object : AuthStore {
            override fun read(): AuthGrant? { reads.incrementAndGet(); return null }
            override fun write(grant: AuthGrant) = error("Launch must not create a grant")
            override fun clear() = error("Guest launch must not alter storage")
        }
        val api = object : AuthApi {
            override fun register(name: String, email: String, password: String) = error("Unexpected HTTP")
            override fun login(email: String, password: String) = error("Unexpected HTTP")
            override fun refresh(token: String) = error("Unexpected HTTP")
            override fun current(token: String) = error("Unexpected HTTP")
            override fun logout(accessToken: String, refreshToken: String) = error("Unexpected HTTP")
        }
        fun launch(savedState: Bundle?) =
            Robolectric.buildActivity(MainActivity::class.java).also { controller ->
                val auth = AuthViewModel(AuthRepository(api, store))
                @Suppress("UNCHECKED_CAST")
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        require(modelClass == AuthViewModel::class.java)
                        return auth as T
                    }
                }
                val activity = controller.get()
                ViewModelProvider(activity, factory)[AuthViewModel::class.java]
                val before = reads.get()
                controller.create(savedState).start().resume().visible()
                awaitRestore(auth, reads, before)
                assertEquals(1, activity.findViewById<ViewGroup>(android.R.id.content).childCount)
                assertEquals(R.style.Theme_Solventa,
                    ReflectionHelpers.callInstanceMethod<Int>(activity, "getThemeResId"))
            }
        val cold = launch(null)
        try {
            val auth = ViewModelProvider(cold.get())[AuthViewModel::class.java]
            val before = reads.get()
            cold.pause().stop().start().resume().visible()
            awaitRestore(auth, reads, before)
            val savedState = Bundle()
            cold.saveInstanceState(savedState).pause().stop().destroy()
            val recreated = launch(savedState)
            recreated.pause().stop().destroy()
        } finally {
            if (!cold.get().isDestroyed) cold.pause().stop().destroy()
        }
    }

    private fun awaitRestore(auth: AuthViewModel, reads: AtomicInteger, before: Int) {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            reads.get() > before && !auth.state.value.loading
        }
        assertNull(auth.state.value.user)
        assertNull(auth.state.value.error)
    }

    private fun withAnimatorScale(scale: Float, block: () -> Unit) {
        // Robolectric has no system service to propagate the developer animation-scale setting.
        val previous = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        fun set(value: Float) {
            ReflectionHelpers.callStaticMethod<Any>(
                ValueAnimator::class.java, "setDurationScale",
                ClassParameter.from(Float::class.javaPrimitiveType!!, value))
        }
        try {
            set(scale)
            block()
        } finally {
            set(previous)
        }
    }
}
