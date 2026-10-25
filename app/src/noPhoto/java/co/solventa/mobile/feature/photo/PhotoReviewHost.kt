package co.solventa.mobile.feature.photo

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import co.solventa.mobile.feature.auth.AuthViewModel

@Composable
internal fun PhotoReviewHost(
    activity: ComponentActivity,
    auth: AuthViewModel,
    content: @Composable ((() -> Unit)?, () -> Unit) -> Unit,
) = content(null, {})
