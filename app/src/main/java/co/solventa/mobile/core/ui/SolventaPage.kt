package co.solventa.mobile.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout

@Composable
internal fun SolventaPage(
    header: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = SolventaLayout.contentWidth).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = SolventaLayout.horizontalPadding, vertical = SolventaLayout.verticalPadding),
                verticalArrangement = if (header != null || actions != null) Arrangement.SpaceBetween else Arrangement.Top,
            ) {
                header?.let {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
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
