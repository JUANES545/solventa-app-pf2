package co.solventa.mobile.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R

@Composable
internal fun SolventaRefreshing(busy: Boolean, content: @Composable () -> Unit) {
    val label = stringResource(R.string.ui_refreshing)
    Box(Modifier.fillMaxWidth()) {
        content()
        if (busy && !LocalPageRefreshInProgress.current) LinearProgressIndicator(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp)
                .height(2.dp).testTag("content-refresh-indicator").semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            })
    }
}
