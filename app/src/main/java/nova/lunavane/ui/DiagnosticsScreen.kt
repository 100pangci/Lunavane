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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nova.lunavane.DiagnosticEntry
import nova.lunavane.R
import nova.lunavane.TunnelUiState
import nova.lunavane.vpn.NetworkRecoveryPhase
import nova.lunavane.ui.components.DataRow
import nova.lunavane.ui.components.EmptyMessage
import nova.lunavane.ui.components.SectionCard
import nova.lunavane.ui.components.StatusPill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LogTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
internal fun DiagnosticsScreen(
    state: TunnelUiState,
    diagnostics: List<DiagnosticEntry>,
    onClearDiagnostics: () -> Unit,
) {
    val listState = rememberLazyListState()
    val status = deriveConnectionStatus(state)
    val activePathPeers = state.peers.count { peer -> peer.paths.any { it.active } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            SectionCard(title = stringResource(R.string.diagnostics_runtime_title)) {
                DataRow(
                    label = stringResource(R.string.diagnostics_connection),
                    value = connectionStatusLabel(status),
                )
                DataRow(
                    label = stringResource(R.string.diagnostics_node),
                    value = nodeStateLabel(state.nodeState),
                )
                DataRow(
                    label = stringResource(R.string.diagnostics_network),
                    value = networkStateLabel(state.networkState),
                )
                DataRow(
                    label = stringResource(R.string.diagnostics_udp),
                    value = udpStatusLabel(state),
                )
                DataRow(
                    label = stringResource(R.string.diagnostics_recovery),
                    value = if (state.tunnelActive) {
                        recoveryPhaseLabel(state.recoveryPhase)
                    } else {
                        stringResource(R.string.value_unavailable)
                    },
                )
                DataRow(
                    label = stringResource(R.string.diagnostics_underlay),
                    value = underlayLabel(state.underlayTransport),
                )
                DataRow(
                    label = stringResource(R.string.diagnostics_peers),
                    value = if (state.peersQueried) {
                        stringResource(
                            R.string.diagnostics_peers_value,
                            state.peers.size,
                            activePathPeers,
                        )
                    } else {
                        stringResource(R.string.diagnostics_peers_pending)
                    },
                )
                DataRow(
                    label = stringResource(R.string.diagnostics_network_id),
                    value = state.networkId.ifBlank { stringResource(R.string.value_unavailable) },
                    monospace = true,
                )
                DataRow(
                    label = stringResource(R.string.diagnostics_node_id),
                    value = state.nodeAddress.ifBlank { stringResource(R.string.value_unavailable) },
                    monospace = true,
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.diagnostics_service_status)) {
                DataRow(
                    label = stringResource(R.string.diagnostics_service_state),
                    value = serviceStatusLabel(state.status),
                )
                if (state.detail.isNotBlank()) {
                    DataRow(
                        label = stringResource(R.string.diagnostics_service_detail),
                        value = state.detail,
                    )
                }
                val lastError = state.lastError
                if (lastError != null) {
                    DataRow(
                        label = stringResource(R.string.diagnostics_last_error),
                        value = lastError,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.diagnostics_logs),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (diagnostics.isNotEmpty()) {
                        StatusPill(
                            text = pluralStringResource(
                                R.plurals.log_entry_count,
                                diagnostics.size,
                                diagnostics.size,
                            ),
                        )
                    }
                }
                TextButton(
                    onClick = onClearDiagnostics,
                    enabled = diagnostics.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.action_clear_logs))
                }
            }
        }

        if (diagnostics.isEmpty()) {
            item {
                SectionCard {
                    EmptyMessage(stringResource(R.string.diagnostics_logs_empty))
                }
            }
        } else {
            items(diagnostics.asReversed(), key = DiagnosticEntry::id) { entry ->
                LogEntry(entry)
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun udpStatusLabel(state: TunnelUiState): String {
    if (!state.tunnelActive) return stringResource(R.string.diagnostics_udp_stopped)
    return when (state.recoveryPhase) {
        NetworkRecoveryPhase.READY -> stringResource(R.string.diagnostics_udp_ready)
        NetworkRecoveryPhase.RECOVERING,
        NetworkRecoveryPhase.RETRY_WAIT,
        NetworkRecoveryPhase.SWITCHING,
        -> stringResource(R.string.diagnostics_udp_recovering)
        NetworkRecoveryPhase.WAITING_FOR_NETWORK ->
            stringResource(R.string.diagnostics_udp_waiting)
        NetworkRecoveryPhase.EXHAUSTED -> stringResource(R.string.diagnostics_udp_failed)
        NetworkRecoveryPhase.STOPPED -> stringResource(R.string.diagnostics_udp_stopped)
    }
}

@Composable
private fun LogEntry(entry: DiagnosticEntry) {
    Column(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        SelectionContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = Instant.ofEpochMilli(entry.timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .format(LogTimeFormatter),
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
    }
}
