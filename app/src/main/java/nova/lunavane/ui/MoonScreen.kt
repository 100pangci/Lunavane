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
import androidx.compose.material3.AlertDialog
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
    onRemoveLocally: (String) -> Unit,
) {
    var worldId by rememberSaveable { mutableStateOf("") }
    var seed by rememberSaveable { mutableStateOf("") }
    var statusInfoExpanded by rememberSaveable { mutableStateOf(false) }
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
                SectionCard { EmptyMessage(stringResource(R.string.moon_saved_empty)) }
            }
        } else {
            groups.forEach { group ->
                item(key = group.worldId) {
                    MoonWorldCard(
                        group = group,
                        state = state,
                        deorbitEnabled = moonAvailable,
                        onDeorbit = onDeorbit,
                        onRemoveLocally = onRemoveLocally,
                    )
                }
            }
        }

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
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = sanitizeHexInput(it, 10) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.moon_seed_label)) },
                    supportingText = { Text(stringResource(R.string.moon_seed_hint)) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                )
                Button(
                    onClick = { onOrbit(worldId, seed) },
                    enabled = worldIdValid && seedValid,
                ) {
                    Text(
                        stringResource(
                            if (moonAvailable) R.string.moon_save_and_orbit else R.string.moon_save_for_later,
                        ),
                    )
                }
                if (!moonAvailable) {
                    Text(
                        text = stringResource(R.string.moon_offline_save_hint),
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
                        Text(text = state.moonMessage, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            SectionCard(
                title = stringResource(R.string.moon_status_legend_title),
                action = {
                    TextButton(onClick = { statusInfoExpanded = !statusInfoExpanded }) {
                        Text(
                            stringResource(
                                if (statusInfoExpanded) R.string.action_collapse else R.string.action_expand,
                            ),
                        )
                    }
                },
            ) {
                if (statusInfoExpanded) {
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
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun MoonWorldCard(
    group: MoonWorldGroup,
    state: TunnelUiState,
    deorbitEnabled: Boolean,
    onDeorbit: (String) -> Unit,
    onRemoveLocally: (String) -> Unit,
) {
    var detailsExpanded by rememberSaveable(group.worldId) { mutableStateOf(false) }
    var confirmation by rememberSaveable(group.worldId) { mutableStateOf("") }
    val lastOperation = state.moonOperation?.takeIf {
        it.worldId == group.worldId && it.type != nova.lunavane.MoonOperationType.RESTORE
    }
    val requestTone = when {
        lastOperation?.outcome == MoonOperationOutcome.ACCEPTED -> StatusTone.SUCCESS
        lastOperation != null && moonOperationIsWarning(lastOperation) -> StatusTone.WARNING
        else -> StatusTone.NEUTRAL
    }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            StatusPill(
                text = pluralStringResource(
                    R.plurals.moon_seed_count,
                    group.seeds.size,
                    group.seeds.size,
                ),
                tone = StatusTone.NEUTRAL,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.moon_saved_request),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusPill(
                text = lastOperation?.let { moonOperationLabel(it) }
                    ?: stringResource(R.string.moon_saved_request_value),
                tone = requestTone,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { confirmation = "deorbit" },
                enabled = deorbitEnabled,
            ) {
                Text(
                    text = stringResource(R.string.moon_deorbit_core),
                    color = if (deorbitEnabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { confirmation = "local" },
            ) {
                Text(
                    text = stringResource(R.string.moon_remove_local),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(
                    stringResource(
                        if (detailsExpanded) R.string.action_collapse else R.string.action_expand,
                    ),
                )
            }
        }
        if (detailsExpanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            group.seeds.forEachIndexed { index, seed ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoonSeedEntry(seed = seed, state = state)
            }
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
    }

    if (confirmation.isNotBlank()) {
        val coreDeorbit = confirmation == "deorbit"
        AlertDialog(
            onDismissRequest = { confirmation = "" },
            title = {
                Text(
                    stringResource(
                        if (coreDeorbit) R.string.moon_deorbit_confirm_title
                        else R.string.moon_remove_local_confirm_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (coreDeorbit) R.string.moon_deorbit_confirm_body
                        else R.string.moon_remove_local_confirm_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = ""
                    if (coreDeorbit) onDeorbit(group.worldId) else onRemoveLocally(group.worldId)
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
