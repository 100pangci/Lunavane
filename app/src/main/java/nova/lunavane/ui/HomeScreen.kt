package nova.lunavane.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nova.lunavane.R
import nova.lunavane.TunnelUiState
import nova.lunavane.ui.components.DataRow
import nova.lunavane.ui.components.PowerButton
import nova.lunavane.ui.components.ScreenContentPadding
import nova.lunavane.ui.components.SectionCard

@Composable
internal fun HomeScreen(
    state: TunnelUiState,
    savedNetworkId: String,
    actionPending: Boolean,
    permissionDenied: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenNetworks: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val status = deriveConnectionStatus(state)
    val effectiveNetworkId = savedNetworkId.ifBlank { state.networkId }
    val hasNetwork = effectiveNetworkId.isNotBlank()
    val currentNetwork = state.networkName.ifBlank { effectiveNetworkId }
    val motionEnabled = rememberMotionEnabled()
    val powerEnabled = hasNetwork && !actionPending
    val powerDescription = when {
        !hasNetwork -> stringResource(R.string.home_no_network)
        state.tunnelActive -> stringResource(R.string.action_disconnect)
        else -> stringResource(R.string.action_connect)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val buttonSize = when {
            maxHeight < 560.dp -> 132.dp
            maxWidth < 360.dp -> 148.dp
            else -> 176.dp
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ScreenContentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = if (currentNetwork.isBlank()) {
                        stringResource(R.string.home_no_network)
                    } else {
                        stringResource(R.string.home_current_network, currentNetwork)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                PowerButton(
                    status = status,
                    enabled = powerEnabled,
                    contentDescription = powerDescription,
                    stateDescription = connectionStatusLabel(status),
                    onClick = if (state.tunnelActive) onDisconnect else onConnect,
                    diameter = buttonSize,
                )

                Crossfade(
                    targetState = status,
                    animationSpec = if (motionEnabled) tween(180) else snap(),
                    label = "connection-status",
                ) { shownStatus ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = connectionStatusLabel(shownStatus),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                shownStatus.isFailure -> MaterialTheme.colorScheme.error
                                shownStatus == ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = if (!hasNetwork) {
                                stringResource(R.string.home_add_network_body)
                            } else {
                                connectionStatusDescription(shownStatus)
                            },
                            modifier = Modifier.widthIn(max = 380.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                if (!hasNetwork) {
                    AddNetworkCard(onOpenNetworks = onOpenNetworks)
                }

                if (status == ConnectionStatus.WAITING_FOR_AUTHORIZATION) {
                    HintCard(
                        text = if (state.nodeAddress.isBlank()) {
                            stringResource(R.string.home_waiting_authorization_hint)
                        } else {
                            stringResource(
                                R.string.home_waiting_authorization_hint_node,
                                state.nodeAddress,
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }

                if (permissionDenied && !status.isFailure) {
                    HintCard(
                        text = stringResource(R.string.home_permission_denied),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }

                if (status.isFailure) {
                    Card(
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.home_error_hint),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onOpenDiagnostics) {
                                Text(stringResource(R.string.action_open_diagnostics))
                            }
                        }
                    }
                }

                SectionCard(title = stringResource(R.string.home_connection_details)) {
                    DataRow(
                        label = stringResource(R.string.home_metric_network),
                        value = currentNetwork.ifBlank { stringResource(R.string.value_unavailable) },
                    )
                    DataRow(
                        label = stringResource(R.string.home_metric_ip),
                        value = state.assignedAddresses.joinToString(" · ")
                            .ifBlank { stringResource(R.string.value_unavailable) },
                        monospace = true,
                    )
                    DataRow(
                        label = stringResource(R.string.home_metric_peers),
                        value = if (state.peersQueried) {
                            pluralStringResource(
                                R.plurals.peers_count,
                                state.peers.size,
                                state.peers.size,
                            )
                        } else {
                            stringResource(R.string.value_unavailable)
                        },
                    )
                    DataRow(
                        label = stringResource(R.string.home_metric_underlay),
                        value = underlayLabel(state.underlayTransport),
                    )
                    DataRow(
                        label = stringResource(R.string.home_metric_recovery),
                        value = if (state.tunnelActive) {
                            recoveryPhaseLabel(state.recoveryPhase)
                        } else {
                            stringResource(R.string.value_unavailable)
                        },
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AddNetworkCard(onOpenNetworks: () -> Unit) {
    Card(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_add_network_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.home_add_network_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onOpenNetworks,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.action_add_network),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HintCard(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Card(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
