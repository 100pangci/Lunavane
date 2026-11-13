package nova.lunavane.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nova.lunavane.MoonOperationOutcome
import nova.lunavane.R
import nova.lunavane.TunnelUiState
import nova.lunavane.vpn.PeerInfo
import nova.lunavane.ui.components.DataRow
import nova.lunavane.ui.components.EmptyMessage
import nova.lunavane.ui.components.PeerPathCard
import nova.lunavane.ui.components.SectionCard
import nova.lunavane.ui.components.SectionHeader
import nova.lunavane.ui.components.StatusPill
import nova.lunavane.ui.components.StatusTone

@Composable
internal fun MoonScreen(
    state: TunnelUiState,
    onOrbit: (String, String) -> Unit,
    onDeorbit: (String) -> Unit,
) {
    var worldId by rememberSaveable { mutableStateOf("") }
    var seed by rememberSaveable { mutableStateOf("") }
    val moonAvailable = state.tunnelActive && state.nodeReady
    val worldIdValid = isValidMoonWorldId(worldId)
    val seedValid = isValidMoonSeed(seed)
    val groups = groupMoonSeeds(state.moonSeeds)
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            SectionCard(title = stringResource(R.string.moon_orbit_title)) {
                Text(
                    text = stringResource(R.string.moon_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = worldId,
                    onValueChange = { worldId = sanitizeHexInput(it, 16) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.moon_world_id_label)) },
                    supportingText = { Text(stringResource(R.string.moon_world_id_hint)) },
                    singleLine = true,
                    enabled = moonAvailable,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = sanitizeHexInput(it, 10) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.moon_seed_label)) },
                    supportingText = { Text(stringResource(R.string.moon_seed_hint)) },
                    singleLine = true,
                    enabled = moonAvailable,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                )
                Button(
                    onClick = { onOrbit(worldId, seed) },
                    enabled = moonAvailable && worldIdValid && seedValid,
                ) {
                    Text(stringResource(R.string.action_orbit))
                }
                if (!moonAvailable) {
                    Text(
                        text = stringResource(R.string.moon_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val operation = state.moonOperation
        if (operation != null || state.moonMessage.isNotBlank()) {
            item {
                SectionCard(title = stringResource(R.string.moon_last_operation)) {
                    if (operation != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = moonOperationLabel(operation),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val tone = when {
                                operation.outcome == MoonOperationOutcome.ACCEPTED -> StatusTone.SUCCESS
                                moonOperationIsWarning(operation) -> StatusTone.WARNING
                                else -> StatusTone.NEUTRAL
                            }
                            StatusPill(
                                text = when (tone) {
                                    StatusTone.SUCCESS -> stringResource(R.string.moon_result_accepted)
                                    StatusTone.WARNING -> stringResource(R.string.moon_result_attention)
                                    else -> stringResource(R.string.moon_result_info)
                                },
                                tone = tone,
                            )
                        }
                        if (operation.worldId.isNotBlank()) {
                            DataRow(
                                label = stringResource(R.string.moon_world_id_short),
                                value = operation.worldId,
                                monospace = true,
                            )
                        }
                        if (operation.seed.isNotBlank()) {
                            DataRow(
                                label = stringResource(R.string.moon_seed_short),
                                value = operation.seed,
                                monospace = true,
                            )
                        }
                    } else {
                        Text(
                            text = state.moonMessage,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.moon_status_legend_title)) {
                LegendItem(
                    title = stringResource(R.string.moon_status_orbit),
                    body = stringResource(R.string.moon_status_orbit_desc),
                )
                LegendItem(
                    title = stringResource(R.string.moon_status_seed),
                    body = stringResource(R.string.moon_status_seed_desc),
                )
                LegendItem(
                    title = stringResource(R.string.moon_status_definition),
                    body = stringResource(R.string.moon_status_definition_desc),
                )
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.moon_saved_title),
                trailing = {
                    if (state.moonSeeds.isNotEmpty()) {
                        StatusPill(
                            text = pluralStringResource(
                                R.plurals.moon_saved_requests,
                                state.moonSeeds.size,
                                state.moonSeeds.size,
                            ),
                        )
                    }
                },
            )
        }

        if (groups.isEmpty()) {
            item {
                SectionCard {
                    EmptyMessage(stringResource(R.string.moon_saved_empty))
                }
            }
        } else {
            groups.forEach { group ->
                item(key = group.worldId) {
                    MoonWorldCard(
                        group = group,
                        state = state,
                        deorbitEnabled = moonAvailable,
                        onDeorbit = onDeorbit,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MoonWorldCard(
    group: MoonWorldGroup,
    state: TunnelUiState,
    deorbitEnabled: Boolean,
    onDeorbit: (String) -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.moon_world_id_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = group.worldId,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(
                onClick = { onDeorbit(group.worldId) },
                enabled = deorbitEnabled,
            ) {
                Text(
                    text = stringResource(R.string.action_deorbit),
                    color = if (deorbitEnabled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        group.seeds.forEachIndexed { index, seed ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            MoonSeedEntry(seed = seed, state = state)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.moon_saved_request),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusPill(
                text = stringResource(R.string.moon_saved_request_value),
                tone = StatusTone.SUCCESS,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.moon_status_definition),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusPill(
                text = stringResource(R.string.moon_definition_pending),
                tone = StatusTone.WARNING,
            )
        }
        if (!deorbitEnabled) {
            Text(
                text = stringResource(R.string.moon_deorbit_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MoonSeedEntry(seed: String, state: TunnelUiState) {
    val peer = if (state.peersQueried) {
        state.peers.firstOrNull { it.address == seed }
    } else {
        null
    }
    val visibility = seedVisibility(peer = peer, peersQueried = state.peersQueried)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.moon_seed_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = seed,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            StatusPill(text = visibility.label, tone = visibility.tone)
        }
        Text(
            text = visibility.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (peer != null && peer.paths.isNotEmpty()) {
            peer.paths.forEach { path -> PeerPathCard(path) }
        }
    }
}

private data class SeedVisibility(
    val label: String,
    val detail: String,
    val tone: StatusTone,
)

@Composable
private fun seedVisibility(peer: PeerInfo?, peersQueried: Boolean): SeedVisibility {
    if (!peersQueried) {
        return SeedVisibility(
            label = stringResource(R.string.moon_seed_visibility_pending),
            detail = stringResource(R.string.moon_seed_status_pending),
            tone = StatusTone.NEUTRAL,
        )
    }
    if (peer == null) {
        return SeedVisibility(
            label = stringResource(R.string.moon_seed_visibility_not_visible),
            detail = stringResource(R.string.moon_seed_status_not_visible),
            tone = StatusTone.NEUTRAL,
        )
    }
    val latency = peer.latencyMillis?.let {
        stringResource(R.string.unit_milliseconds, it)
    } ?: stringResource(R.string.value_unknown)
    val paths = pluralStringResource(R.plurals.paths_count, peer.paths.size, peer.paths.size)
    return SeedVisibility(
        label = stringResource(R.string.moon_seed_visibility_visible),
        detail = stringResource(
            R.string.moon_seed_status_visible,
            peerRoleLabel(peer.role),
            latency,
            paths,
        ),
        tone = StatusTone.SUCCESS,
    )
}

@Composable
private fun LegendItem(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
