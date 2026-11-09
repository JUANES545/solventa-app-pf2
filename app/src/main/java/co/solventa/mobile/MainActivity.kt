package co.solventa.mobile

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.ViewModelProvider
import co.solventa.mobile.feature.auth.AuthViewModel
import co.solventa.mobile.feature.auth.BiometricViewModel
import co.solventa.mobile.feature.auth.AndroidBiometricSigner
import co.solventa.mobile.feature.photo.PhotoReviewHost
import co.solventa.mobile.feature.evidence.EvidenceViewModel
import co.solventa.mobile.feature.claim.ClaimViewModel
import co.solventa.mobile.feature.claim.ClaimStatusViewModel
import co.solventa.mobile.feature.notification.AndroidNotificationPresenter
import co.solventa.mobile.feature.notification.NotificationViewModel
import co.solventa.mobile.feature.assistance.AssistanceViewModel

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val auth by lazy { ViewModelProvider(this)[AuthViewModel::class.java] }
    private val biometrics by lazy { ViewModelProvider(this)[BiometricViewModel::class.java] }
    private val evidence by lazy { ViewModelProvider(this)[EvidenceViewModel::class.java] }
    private val claims by lazy { ViewModelProvider(this)[ClaimViewModel::class.java] }
    private val claimStatus by lazy { ViewModelProvider(this)[ClaimStatusViewModel::class.java] }
    private val notifications by lazy {
        ViewModelProvider(this)[NotificationViewModel::class.java]
    }
    private val assistance by lazy {
        ViewModelProvider(this)[AssistanceViewModel::class.java]
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifications.permissionResult(granted)
    }
    private var signer: AndroidBiometricSigner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            splash.setOnExitAnimationListener { provider ->
                fadeSplashExit(provider.view, provider::remove)
            }
        }
        if (BuildConfig.BIOMETRIC_LOGIN_ENABLED) signer = AndroidBiometricSigner(this)
        acceptNotificationIntent(intent)
        enableEdgeToEdge()
        setContent {
            PhotoReviewHost(this, auth) { openPhoto, beforeAccessChange ->
                SolventaApp(auth, if (BuildConfig.BIOMETRIC_LOGIN_ENABLED) biometrics else null, signer,
                    openPhoto, beforeAccessChange, evidence = evidence, claims = claims,
                    claimStatus = claimStatus, notifications = notifications,
                    assistance = assistance,
                    requestNotificationPermission = ::requestNotificationPermission)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.BIOMETRIC_LOGIN_ENABLED || !biometrics.state.value.busy) auth.restore()
        auth.state.value.user?.id?.let(notifications::synchronize)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notifications.permissionResult(true)
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun acceptNotificationIntent(intent: Intent?) {
        val notificationId =
            intent?.getStringExtra(AndroidNotificationPresenter.EXTRA_NOTIFICATION_ID)
        val resourceKind =
            intent?.getStringExtra(AndroidNotificationPresenter.EXTRA_RESOURCE_KIND)
        val resourceId =
            intent?.getStringExtra(AndroidNotificationPresenter.EXTRA_RESOURCE_ID)
        if (notificationId == null && resourceKind == null && resourceId == null) return
        notifications.acceptTarget(
            notificationId,
            resourceKind,
            resourceId,
        )
    }
}

internal fun fadeSplashExit(view: View, remove: () -> Unit) {
    if (!ValueAnimator.areAnimatorsEnabled()) {
        remove()
        return
    }
    ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).apply {
        duration = 180L
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = remove()
        })
        start()
    }
}
