package nova.lunavane.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import nova.lunavane.R
import nova.lunavane.SavedNetwork
import nova.lunavane.TunnelUiState
import nova.lunavane.VirtualNetworkState
import nova.lunavane.fatalVirtualNetworkStates
import nova.lunavane.vpn.PeerInfo
import nova.lunavane.vpn.PeerRole
import nova.lunavane.ui.components.DataRow
import nova.lunavane.ui.components.EmptyMessage
import nova.lunavane.ui.components.InlineHint
import nova.lunavane.ui.components.PeerPathCard
import nova.lunavane.ui.components.SectionCard
import nova.lunavane.ui.components.SectionHeader
import nova.lunavane.ui.components.StatusPill
import nova.lunavane.ui.components.StatusTone

@Composable
internal fun NetworksScreen(
    state: TunnelUiState,
    savedNetworkId: String,
    savedNetworks: List<SavedNetwork>,
    actionPending: Boolean,
    connectPending: Boolean,
    permissionDenied: Boolean,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSelectNetwork: (String) -> Unit,
    onAddNetwork: (String, String) -> Boolean,
    onRenameNetwork: (String, String) -> Boolean,
    onDeleteNetwork: (String) -> Unit,
    onMoveNetwork: (String, Int) -> Unit,
) {
    var selectedPeerAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPeer = selectedPeerAddress?.let { address ->
        state.peers.firstOrNull { it.address == address }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(selectedPeerAddress, state.peers) {
        if (selectedPeerAddress != null && selectedPeer == null) selectedPeerAddress = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            AddNetworkCard(
                existingIds = savedNetworks.map(SavedNetwork::networkId).toSet(),
                actionPending = actionPending,
                onAddNetwork = onAddNetwork,
            )
        }
        item {
            SectionHeader(
                title = stringResource(R.string.networks_saved_title),
                trailing = if (savedNetworks.isNotEmpty()) {
                    { StatusPill(text = savedNetworks.size.toString()) }
                } else {
                    null
                },
            )
        }
        if (savedNetworks.isEmpty()) {
            item {
                SectionCard { EmptyMessage(stringResource(R.string.networks_saved_empty)) }
            }
        } else {
            items(savedNetworks, key = SavedNetwork::networkId) { profile ->
                SavedNetworkCard(
                    profile = profile,
                    selected = profile.networkId == savedNetworkId,
                    state = state,
                    actionPending = actionPending,
                    connectPending = connectPending && profile.networkId == savedNetworkId,
                    permissionDenied = permissionDenied,
                    canMoveUp = savedNetworks.firstOrNull()?.networkId != profile.networkId,
                    canMoveDown = savedNetworks.lastOrNull()?.networkId != profile.networkId,
                    onSelect = { onSelectNetwork(profile.networkId) },
                    onConnect = { onConnect(profile.networkId) },
                    onDisconnect = onDisconnect,
                    onRename = { name -> onRenameNetwork(profile.networkId, name) },
                    onDelete = { onDeleteNetwork(profile.networkId) },
                    onMove = { offset -> onMoveNetwork(profile.networkId, offset) },
                )
            }
        }
        item {
            NetworkDetailsCard(state = state, savedNetworkId = savedNetworkId)
        }
        item {
            SectionHeader(
                title = stringResource(R.string.networks_peers_title),
                trailing = {
                    if (state.peersQueried) {
                        StatusPill(
                            text = pluralStringResource(
                                R.plurals.peers_count,
                                state.peers.size,
                                state.peers.size,
                            ),
                        )
                    }
                },
            )
        }
        when {
            !state.tunnelActive -> item {
                SectionCard {
                    EmptyMessage(stringResource(R.string.networks_peers_not_connected))
                }
            }
            !state.peersQueried -> item {
                SectionCard {
                    EmptyMessage(stringResource(R.string.networks_peers_pending))
                }
            }
            state.peers.isEmpty() -> item {
                SectionCard {
                    EmptyMessage(stringResource(R.string.networks_peers_empty))
                }
            }
            else -> items(state.peers, key = PeerInfo::address) { peer ->
                PeerCard(peer = peer, onClick = { selectedPeerAddress = peer.address })
            }
        }
    }

    if (selectedPeer != null) {
        PeerDetailSheet(peer = selectedPeer, onDismiss = { selectedPeerAddress = null })
    }
}

@Composable
private fun AddNetworkCard(
    existingIds: Set<String>,
    actionPending: Boolean,
    onAddNetwork: (String, String) -> Boolean,
) {
    var networkId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    val normalized = networkId.lowercase()
    val valid = isValidNetworkId(normalized)
    val duplicate = normalized in existingIds
    val saveFailedMessage = stringResource(R.string.networks_save_failed)

    SectionCard(title = stringResource(R.string.networks_add_title)) {
        OutlinedTextField(
            value = networkId,
            onValueChange = { networkId = sanitizeHexInput(it, 16); error = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.networks_network_id_label)) },
            placeholder = {
                Text(
                    text = stringResource(R.string.networks_network_id_example),
                    fontFamily = FontFamily.Monospace,
                )
            },
            supportingText = { Text(stringResource(R.string.networks_network_id_hint)) },
            enabled = !actionPending,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.networks_custom_name)) },
            enabled = !actionPending,
            singleLine = true,
        )
        if (duplicate) InlineHint(text = stringResource(R.string.networks_duplicate))
        if (error.isNotBlank()) InlineHint(text = error)
        Button(
            onClick = {
                if (onAddNetwork(normalized, name)) {
                    networkId = ""
                    name = ""
                    error = ""
                } else {
                    error = saveFailedMessage
                }
            },
            enabled = valid && !duplicate && !actionPending,
        ) {
            Text(stringResource(R.string.action_add_network))
        }
    }
}

@Composable
private fun SavedNetworkCard(
    profile: SavedNetwork,
    selected: Boolean,
    state: TunnelUiState,
    actionPending: Boolean,
    connectPending: Boolean,
    permissionDenied: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRename: (String) -> Boolean,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var editDialogOpen by rememberSaveable(profile.networkId) { mutableStateOf(false) }
    var deleteDialogOpen by rememberSaveable(profile.networkId) { mutableStateOf(false) }
    var editName by rememberSaveable(profile.networkId) { mutableStateOf(profile.name) }
    val active = state.tunnelActive && state.networkId == profile.networkId
    val status = if (active) deriveConnectionStatus(state) else null
    val healthy = active && status == ConnectionStatus.CONNECTED

    SectionCard(
        title = profile.name.ifBlank { profile.networkId },
        action = {
            IconButton(onClick = { menuExpanded = true }, enabled = !actionPending) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.networks_more_options),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.networks_edit_name)) },
                    onClick = { menuExpanded = false; editName = profile.name; editDialogOpen = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.networks_move_up)) },
                    enabled = canMoveUp,
                    onClick = { menuExpanded = false; onMove(-1) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.networks_move_down)) },
                    enabled = canMoveDown,
                    onClick = { menuExpanded = false; onMove(1) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    enabled = !actionPending,
                    onClick = { menuExpanded = false; deleteDialogOpen = true },
                )
            }
        },
    ) {
        DataRow(
            label = stringResource(R.string.networks_network_id),
            value = profile.networkId,
            monospace = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) StatusPill(text = stringResource(R.string.networks_selected), tone = StatusTone.PRIMARY)
            if (active && state.networkState != VirtualNetworkState.IDLE) {
                StatusPill(
                    text = connectionStatusLabel(status ?: ConnectionStatus.DISCONNECTED),
                    tone = when {
                        status?.isFailure == true -> StatusTone.ERROR
                        healthy -> StatusTone.SUCCESS
                        else -> StatusTone.NEUTRAL
                    },
                )
            }
            if (active && state.networkState in fatalVirtualNetworkStates) {
                StatusPill(
                    text = networkStateLabel(state.networkState),
                    tone = StatusTone.ERROR,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!selected && (!state.tunnelActive || active)) {
                TextButton(onClick = onSelect, enabled = !actionPending) {
                    Text(stringResource(R.string.networks_select_network))
                }
            }
            if (active && status?.isFailure != true) {
                TextButton(onClick = onDisconnect, enabled = !actionPending) {
                    Text(stringResource(R.string.action_disconnect))
                }
            } else {
                Button(onClick = onConnect, enabled = !actionPending) {
                    if (connectPending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.networks_connecting))
                    } else {
                        Text(
                            stringResource(
                                if (state.tunnelActive && !active) {
                                    R.string.networks_switch_and_connect
                                } else {
                                    R.string.action_connect
                                },
                            ),
                        )
                    }
                }
            }
        }
        if (permissionDenied) InlineHint(text = stringResource(R.string.home_permission_denied))
    }

    if (editDialogOpen) {
        AlertDialog(
            onDismissRequest = { editDialogOpen = false },
            title = { Text(stringResource(R.string.networks_edit_name)) },
            text = {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text(stringResource(R.string.networks_custom_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onRename(editName)) editDialogOpen = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editDialogOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (deleteDialogOpen) {
        AlertDialog(
            onDismissRequest = { deleteDialogOpen = false },
            title = { Text(stringResource(R.string.networks_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (active) R.string.networks_delete_connected else R.string.networks_delete_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteDialogOpen = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun NetworkDetailsCard(state: TunnelUiState, savedNetworkId: String) {
    SectionCard(title = stringResource(R.string.networks_details_title)) {
        if (!state.tunnelActive && state.networkState == VirtualNetworkState.IDLE) {
            EmptyMessage(stringResource(R.string.networks_details_empty))
            return@SectionCard
        }
        DataRow(
            label = stringResource(R.string.networks_name),
            value = state.networkName.ifBlank { stringResource(R.string.value_unavailable) },
        )
        DataRow(
            label = stringResource(R.string.networks_network_id),
            value = state.networkId.ifBlank { savedNetworkId }
                .ifBlank { stringResource(R.string.value_unavailable) },
            monospace = true,
        )
        DataRow(
            label = stringResource(R.string.networks_node_id),
            value = state.nodeAddress.ifBlank { stringResource(R.string.value_unavailable) },
            monospace = true,
        )
        DataRow(
            label = stringResource(R.string.networks_ipv4),
            value = state.assignedAddresses
                .filterNot { it.contains(':') }
                .joinToString(" · ")
                .ifBlank { stringResource(R.string.value_unavailable) },
            monospace = true,
        )
        DataRow(
            label = stringResource(R.string.networks_ipv6),
            value = state.assignedAddresses
                .filter { it.contains(':') }
                .joinToString(" · ")
                .ifBlank { stringResource(R.string.value_unavailable) },
            monospace = true,
        )
        PillRow(
            label = stringResource(R.string.networks_authorization),
            pill = {
                StatusPill(
                    text = networkStateLabel(state.networkState),
                    tone = networkStateTone(state.networkState),
                )
            },
        )
        DataRow(
            label = stringResource(R.string.networks_routes),
            value = when {
                state.activeRoutes.isNotEmpty() -> state.activeRoutes.joinToString("\n")
                state.networkState == VirtualNetworkState.AUTHORIZED ->
                    stringResource(R.string.networks_no_routes)
                else -> stringResource(R.string.value_unavailable)
            },
            monospace = state.activeRoutes.isNotEmpty(),
        )
        if (state.dnsServers.isNotEmpty()) {
            DataRow(
                label = stringResource(R.string.networks_dns),
                value = state.dnsServers.joinToString(", "),
                monospace = true,
            )
        }
        if (state.dnsDomain.isNotBlank()) {
            DataRow(
                label = stringResource(R.string.networks_search_domain),
                value = state.dnsDomain,
                monospace = true,
            )
        }
    }
}

@Composable
private fun PillRow(label: String, pill: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pill()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerCard(peer: PeerInfo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = peer.address,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    StatusPill(text = peerRoleLabel(peer.role), tone = peerRoleTone(peer.role))
                }
                Text(
                    text = peerSummary(peer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerDetailSheet(peer: PeerInfo, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.networks_peer_details_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = peer.address,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(text = peerRoleLabel(peer.role), tone = peerRoleTone(peer.role))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DataRow(
                label = stringResource(R.string.networks_peer_id),
                value = peer.address,
                monospace = true,
            )
            PillRow(
                label = stringResource(R.string.networks_peer_role),
                pill = { StatusPill(text = peerRoleLabel(peer.role), tone = peerRoleTone(peer.role)) },
            )
            DataRow(
                label = stringResource(R.string.networks_peer_latency),
                value = peer.latencyMillis?.let {
                    stringResource(R.string.unit_milliseconds, it)
                } ?: stringResource(R.string.value_unknown),
            )
            DataRow(
                label = stringResource(R.string.networks_peer_version),
                value = peer.version?.let {
                    stringResource(R.string.networks_peer_version_value, it)
                } ?: stringResource(R.string.value_unknown),
            )
            if (peer.bonded) {
                DataRow(
                    label = stringResource(R.string.networks_peer_bonded),
                    value = if (peer.aliveLinks != null && peer.totalLinks != null) {
                        stringResource(
                            R.string.networks_peer_bonded_value,
                            peer.aliveLinks,
                            peer.totalLinks,
                        )
                    } else {
                        stringResource(R.string.networks_peer_bonded_unknown)
                    },
                )
            }
            DataRow(
                label = stringResource(R.string.networks_peer_paths),
                value = pluralStringResource(
                    R.plurals.paths_count,
                    peer.paths.size,
                    peer.paths.size,
                ),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.networks_peer_paths_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (peer.paths.isEmpty()) {
                EmptyMessage(stringResource(R.string.networks_peer_no_paths))
            } else {
                peer.paths.forEach { path -> PeerPathCard(path) }
            }
        }
    }
}

@Composable
private fun peerSummary(peer: PeerInfo): String {
    val latency = peer.latencyMillis?.let {
        stringResource(R.string.unit_milliseconds, it)
    } ?: stringResource(R.string.value_unknown)
    val paths = pluralStringResource(R.plurals.paths_count, peer.paths.size, peer.paths.size)
    return stringResource(R.string.networks_peer_summary, latency, paths)
}

private fun networkStateTone(state: VirtualNetworkState): StatusTone = when (state) {
    VirtualNetworkState.AUTHORIZED -> StatusTone.SUCCESS
    VirtualNetworkState.JOINING -> StatusTone.PRIMARY
    VirtualNetworkState.PENDING -> StatusTone.WARNING
    VirtualNetworkState.ACCESS_DENIED,
    VirtualNetworkState.NOT_FOUND,
    VirtualNetworkState.PORT_ERROR,
    VirtualNetworkState.CLIENT_TOO_OLD,
    VirtualNetworkState.AUTH_REQUIRED,
    VirtualNetworkState.JOIN_FAILED,
    VirtualNetworkState.CONFIG_ERROR,
    -> StatusTone.ERROR
    VirtualNetworkState.IDLE,
    VirtualNetworkState.LEFT,
    -> StatusTone.NEUTRAL
}

private fun peerRoleTone(role: PeerRole): StatusTone = when (role) {
    PeerRole.MOON -> StatusTone.WARNING
    PeerRole.PLANET -> StatusTone.PRIMARY
    PeerRole.LEAF,
    PeerRole.UNKNOWN,
    -> StatusTone.NEUTRAL
}
