package co.solventa.mobile.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout

internal val LocalPageRefreshInProgress = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SolventaPage(
    header: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    background: (@Composable () -> Unit)? = null,
    headerAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    feedback: (@Composable () -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    refreshEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            background?.invoke()
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                val minimumHeight = maxHeight
                val refreshLabel = stringResource(R.string.claim_status_refresh)
                val pageContent: @Composable () -> Unit = {
                    Column(
                        Modifier.widthIn(max = SolventaLayout.contentWidth).fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = minimumHeight)
                            .padding(horizontal = SolventaLayout.horizontalPadding, vertical = SolventaLayout.verticalPadding),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        header?.let {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = headerAlignment,
                                verticalArrangement = Arrangement.spacedBy(SolventaLayout.sectionGap), content = it)
                        }
                        Column(Modifier.fillMaxWidth().padding(
                            top = if (header != null) 24.dp else 0.dp,
                            bottom = if (actions != null) 24.dp else 0.dp),
                            verticalArrangement = Arrangement.spacedBy(SolventaLayout.sectionGap), content = content)
                        actions?.let {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(SolventaLayout.sectionGap), content = it)
                        }
                    }
                }
                if (onRefresh == null) {
                    pageContent()
                } else {
                    val refreshState = rememberPullToRefreshState()
                    var requested by remember { mutableStateOf(false) }
                    LaunchedEffect(isRefreshing) {
                        if (!isRefreshing) requested = false
                    }
                    val pageRefreshing = requested && isRefreshing
                    val refresh = {
                        if (refreshEnabled && !isRefreshing) {
                            requested = true
                            onRefresh()
                        }
                    }
                    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = {
                        refresh()
                    }, state = refreshState, indicator = {
                        if (pageRefreshing || (refreshEnabled && !isRefreshing && refreshState.distanceFraction > 0f)) {
                            PullToRefreshDefaults.Indicator(
                                modifier = Modifier.align(Alignment.TopCenter).testTag("page-refresh-indicator"),
                                isRefreshing = pageRefreshing,
                                state = refreshState,
                            )
                        }
                    }, modifier = Modifier.widthIn(max = SolventaLayout.contentWidth).fillMaxSize()
                        .testTag("page-refresh").semantics {
                            customActions = if (refreshEnabled && !isRefreshing) listOf(
                                CustomAccessibilityAction(refreshLabel) { refresh(); true },
                            ) else emptyList()
                        }) {
                        CompositionLocalProvider(LocalPageRefreshInProgress provides pageRefreshing) { pageContent() }
                    }
                }
                feedback?.let {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) { it() }
                }
            }
        }
    }
}

@Composable
internal fun SolventaBrandHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        SolventaBrandMark(32.dp)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium)
    }
}
