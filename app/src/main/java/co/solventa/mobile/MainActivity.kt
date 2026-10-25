package co.solventa.mobile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
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

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val auth by lazy { ViewModelProvider(this)[AuthViewModel::class.java] }
    private val biometrics by lazy { ViewModelProvider(this)[BiometricViewModel::class.java] }
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
        enableEdgeToEdge()
        setContent {
            PhotoReviewHost(this, auth) { openPhoto, beforeAccessChange ->
                SolventaApp(auth, if (BuildConfig.BIOMETRIC_LOGIN_ENABLED) biometrics else null, signer,
                    openPhoto, beforeAccessChange)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.BIOMETRIC_LOGIN_ENABLED || !biometrics.state.value.busy) auth.restore()
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
