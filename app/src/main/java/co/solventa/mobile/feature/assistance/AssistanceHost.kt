package co.solventa.mobile.feature.assistance

import android.view.WindowManager
import co.solventa.mobile.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun AssistanceHost(model: AssistanceViewModel) {
    val context = LocalContext.current
    DisposableEffect(model) {
        val window = (context as? android.app.Activity)?.window
        val wasSecure =
            window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (!BuildConfig.SCREEN_CAPTURE_ALLOWED) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    AssistanceScreen(model)
}
