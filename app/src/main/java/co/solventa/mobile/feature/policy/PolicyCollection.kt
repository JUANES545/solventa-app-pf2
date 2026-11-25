package co.solventa.mobile.feature.policy

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Composable
internal fun PolicyCollection(policies: List<Policy>, select: (Policy) -> Unit) {
    VisualCollection(policies, "wallet-appearance", R.plurals.wallet_count, select,
        { policy, modifier -> Artwork(policy, modifier) }, { policy, modifier -> Summary(policy, modifier) })
}

@Composable
internal fun <T> VisualCollection(items: List<T>, preferenceName: String, countLabel: Int, select: (T) -> Unit,
    artwork: @Composable (T, Modifier) -> Unit, summary: @Composable (T, Modifier) -> Unit,
    cardModifier: (T) -> Modifier = { Modifier }) {
    val preferences = LocalContext.current.getSharedPreferences(preferenceName, 0)
    var grid by remember { mutableStateOf(preferences.getBoolean("grid", true)) }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < colors.onSurface.luminance()
    val cardColor = if (dark) Color(0xFF172232) else colors.surfaceContainer
    val selectedColor = if (dark) Color(0xFF134457) else colors.primaryContainer
    val selectedInk = if (dark) Color(0xFF99F5F5) else colors.onPrimaryContainer
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(countLabel, items.size, items.size), Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = if (dark) Color(0xFF1C293C) else colors.surfaceContainerHigh) {
            Row(Modifier.selectableGroup().padding(3.dp)) {
                for (mode in listOf(false, true)) {
                    Surface(shape = MaterialTheme.shapes.small, color = if (mode == grid)
                        selectedColor else Color.Transparent) {
                        Box(Modifier.size(48.dp).selectable(mode == grid, role = Role.RadioButton, onClick = {
                            grid = mode
                            preferences.edit().putBoolean("grid", mode).apply()
                        }), contentAlignment = Alignment.Center) {
                            Icon(painterResource(if (mode) R.drawable.wallet_grid else R.drawable.wallet_list),
                                stringResource(if (mode) R.string.wallet_grid else R.string.wallet_list), Modifier.size(20.dp),
                                tint = if (mode == grid) selectedInk else colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (grid && maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item ->
                        Card(onClick = { select(item) }, modifier = Modifier.weight(1f).then(cardModifier(item)),
                            colors = CardDefaults.cardColors(containerColor = cardColor, contentColor = colors.onSurface)) {
                            if (grid) {
                                artwork(item, Modifier.fillMaxWidth().height(106.dp))
                                summary(item, Modifier.heightIn(min = 110.dp).padding(12.dp))
                            } else Row(verticalAlignment = Alignment.CenterVertically) {
                                artwork(item, Modifier.size(96.dp))
                                summary(item, Modifier.weight(1f).padding(12.dp))
                            }
                        }
                    }
                    if (columns > row.size) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Artwork(policy: Policy, modifier: Modifier) {
    val image = when (policy.product) {
        PolicyProduct.AUTO -> R.drawable.wallet_auto
        PolicyProduct.TRAVEL -> R.drawable.wallet_travel
        PolicyProduct.LIFE, PolicyProduct.MORTGAGE -> R.drawable.wallet_life
        PolicyProduct.DEVICE -> R.drawable.quote_device
        PolicyProduct.PAYMENT -> R.drawable.quote_payment
        PolicyProduct.PARAMETRIC -> R.drawable.quote_flight
        PolicyProduct.WEATHER -> R.drawable.quote_weather
    }
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer) {
        Image(painterResource(image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun Summary(policy: Policy, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(policyStatusText(policy.status)), style = MaterialTheme.typography.labelLarge,
            color = if (policy.status == PolicyStatus.ACTIVE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
        Text(stringResource(policyProductText(policy.product)), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.wallet_reference, policy.number.takeLast(4)), style = MaterialTheme.typography.bodySmall)
    }
}
