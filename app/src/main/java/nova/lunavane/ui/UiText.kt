package nova.lunavane.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import nova.lunavane.MoonOperation
import nova.lunavane.MoonOperationOutcome
import nova.lunavane.MoonOperationType
import nova.lunavane.NodeState
import nova.lunavane.R
import nova.lunavane.VirtualNetworkState
import nova.lunavane.vpn.NetworkRecoveryPhase
import nova.lunavane.vpn.PeerRole
import nova.lunavane.vpn.UnderlayTransport

@Composable
internal fun connectionStatusLabel(status: ConnectionStatus): String = stringResource(
    when (status) {
        ConnectionStatus.DISCONNECTED -> R.string.home_status_disconnected
        ConnectionStatus.CONNECTING -> R.string.home_status_connecting
        ConnectionStatus.WAITING_FOR_AUTHORIZATION -> R.string.home_status_waiting
        ConnectionStatus.CONNECTED -> R.string.home_status_connected
        ConnectionStatus.SWITCHING_NETWORK -> R.string.home_status_switching
        ConnectionStatus.RECOVERING -> R.string.home_status_recovering
        ConnectionStatus.RECOVERY_FAILED -> R.string.home_status_recovery_failed
        ConnectionStatus.ERROR -> R.string.home_status_error
    },
)

@Composable
internal fun connectionStatusDescription(status: ConnectionStatus): String = stringResource(
    when (status) {
        ConnectionStatus.DISCONNECTED -> R.string.home_desc_disconnected
        ConnectionStatus.CONNECTING -> R.string.home_desc_connecting
        ConnectionStatus.WAITING_FOR_AUTHORIZATION -> R.string.home_desc_waiting
        ConnectionStatus.CONNECTED -> R.string.home_desc_connected
        ConnectionStatus.SWITCHING_NETWORK -> R.string.home_desc_switching
        ConnectionStatus.RECOVERING -> R.string.home_desc_recovering
        ConnectionStatus.RECOVERY_FAILED -> R.string.home_desc_recovery_failed
        ConnectionStatus.ERROR -> R.string.home_desc_error
    },
)

@Composable
internal fun nodeStateLabel(state: NodeState): String = stringResource(
    when (state) {
        NodeState.IDLE -> R.string.node_state_idle
        NodeState.STARTING -> R.string.node_state_starting
        NodeState.READY -> R.string.node_state_ready
        NodeState.FAILED -> R.string.node_state_failed
    },
)

@Composable
internal fun networkStateLabel(state: VirtualNetworkState): String = stringResource(
    when (state) {
        VirtualNetworkState.IDLE -> R.string.network_state_idle
        VirtualNetworkState.JOINING -> R.string.network_state_joining
        VirtualNetworkState.AUTHORIZED -> R.string.network_state_authorized
        VirtualNetworkState.PENDING -> R.string.network_state_pending
        VirtualNetworkState.ACCESS_DENIED -> R.string.network_state_access_denied
        VirtualNetworkState.NOT_FOUND -> R.string.network_state_not_found
        VirtualNetworkState.PORT_ERROR -> R.string.network_state_port_error
        VirtualNetworkState.CLIENT_TOO_OLD -> R.string.network_state_client_too_old
        VirtualNetworkState.AUTH_REQUIRED -> R.string.network_state_auth_required
        VirtualNetworkState.JOIN_FAILED -> R.string.network_state_join_failed
        VirtualNetworkState.LEFT -> R.string.network_state_left
        VirtualNetworkState.CONFIG_ERROR -> R.string.network_state_config_error
    },
)

@Composable
internal fun recoveryPhaseLabel(phase: NetworkRecoveryPhase): String = stringResource(
    when (phase) {
        NetworkRecoveryPhase.WAITING_FOR_NETWORK -> R.string.recovery_waiting_for_network
        NetworkRecoveryPhase.SWITCHING -> R.string.recovery_switching
        NetworkRecoveryPhase.RECOVERING -> R.string.recovery_recovering
        NetworkRecoveryPhase.RETRY_WAIT -> R.string.recovery_retry_wait
        NetworkRecoveryPhase.READY -> R.string.recovery_ready
        NetworkRecoveryPhase.EXHAUSTED -> R.string.recovery_exhausted
        NetworkRecoveryPhase.STOPPED -> R.string.recovery_stopped
    },
)

@Composable
internal fun underlayLabel(transport: UnderlayTransport?): String = stringResource(
    when (transport) {
        UnderlayTransport.WIFI -> R.string.underlay_wifi
        UnderlayTransport.CELLULAR -> R.string.underlay_cellular
        UnderlayTransport.ETHERNET -> R.string.underlay_ethernet
        UnderlayTransport.VPN -> R.string.underlay_vpn
        UnderlayTransport.OTHER -> R.string.underlay_other
        null -> R.string.value_unavailable
    },
)

/**
 * Maps the VPN service's internal status tokens to localized text. Unknown values are
 * shown verbatim so diagnostics never hide a service state.
 */
@Composable
internal fun serviceStatusLabel(status: String): String {
    if (status.isBlank()) return stringResource(R.string.value_unavailable)
    val resource = when (status) {
        "Starting" -> R.string.notification_starting
        "Requesting configuration" -> R.string.notification_requesting_config
        "Connected" -> R.string.notification_connected
        "Disconnected" -> R.string.notification_disconnected
        "Degraded" -> R.string.notification_degraded
        "Reconnecting" -> R.string.notification_reconnecting
        "Offline" -> R.string.notification_offline
        "Online" -> R.string.notification_online
        "Failed" -> R.string.notification_failed
        "Configuration error" -> R.string.notification_configuration_error
        "Tunnel error" -> R.string.notification_tunnel_error
        "Access denied" -> R.string.notification_access_denied
        "Network not found" -> R.string.notification_not_found
        "Port error" -> R.string.notification_port_error
        "Client too old" -> R.string.notification_client_too_old
        "Authentication required" -> R.string.notification_auth_required
        "Network pending" -> R.string.notification_pending
        "Node event" -> R.string.notification_node_event
        else -> null
    }
    return if (resource != null) stringResource(resource) else status
}

@Composable
internal fun peerRoleLabel(role: PeerRole): String = when (role) {
    PeerRole.LEAF -> "LEAF"
    PeerRole.MOON -> "MOON"
    PeerRole.PLANET -> "PLANET"
    PeerRole.UNKNOWN -> stringResource(R.string.value_unknown)
}

@Composable
internal fun moonOperationLabel(operation: MoonOperation): String = when (operation.type) {
    MoonOperationType.ORBIT -> when (operation.outcome) {
        MoonOperationOutcome.ACCEPTED -> stringResource(R.string.moon_orbit_accepted)
        MoonOperationOutcome.ACCEPTED_NOT_SAVED -> stringResource(R.string.moon_orbit_accepted_not_saved)
        MoonOperationOutcome.SAVED_PENDING -> stringResource(R.string.moon_orbit_saved_pending)
        MoonOperationOutcome.SAVE_FAILED -> stringResource(R.string.moon_save_failed)
        MoonOperationOutcome.REJECTED -> stringResource(
            R.string.moon_orbit_rejected,
            operation.coreCode ?: -1,
        )
        MoonOperationOutcome.UNAVAILABLE -> stringResource(R.string.moon_orbit_unavailable)
        MoonOperationOutcome.INVALID_INPUT -> stringResource(R.string.moon_orbit_invalid)
        else -> stringResource(R.string.moon_operation_unknown)
    }
    MoonOperationType.DEORBIT -> when (operation.outcome) {
        MoonOperationOutcome.ACCEPTED -> stringResource(R.string.moon_deorbit_accepted)
        MoonOperationOutcome.FILE_REMAINS -> stringResource(R.string.moon_deorbit_file_remains)
        MoonOperationOutcome.FILE_STATUS_UNKNOWN -> stringResource(R.string.moon_deorbit_status_unknown)
        MoonOperationOutcome.RECORD_REMOVE_FAILED -> stringResource(R.string.moon_deorbit_record_failed)
        MoonOperationOutcome.REJECTED -> stringResource(
            R.string.moon_deorbit_rejected,
            operation.coreCode ?: -1,
        )
        MoonOperationOutcome.UNAVAILABLE -> stringResource(R.string.moon_deorbit_unavailable)
        MoonOperationOutcome.INVALID_INPUT -> stringResource(R.string.moon_deorbit_invalid)
        else -> stringResource(R.string.moon_operation_unknown)
    }
    MoonOperationType.LOCAL_REMOVE -> when (operation.outcome) {
        MoonOperationOutcome.LOCAL_REMOVED -> stringResource(R.string.moon_local_remove_accepted)
        MoonOperationOutcome.LOCAL_REMOVE_FAILED -> stringResource(R.string.moon_local_remove_failed)
        else -> stringResource(R.string.moon_operation_unknown)
    }
    MoonOperationType.RESTORE -> when (operation.outcome) {
        MoonOperationOutcome.ACCEPTED -> pluralStringResource(
            R.plurals.moon_restored,
            operation.succeeded,
            operation.succeeded,
        )
        MoonOperationOutcome.PARTIAL -> stringResource(
            R.string.moon_restored_partial,
            operation.succeeded,
            operation.succeeded + operation.failed,
        )
        else -> stringResource(R.string.moon_operation_unknown)
    }
}

internal fun moonOperationIsWarning(operation: MoonOperation): Boolean = when (operation.outcome) {
    MoonOperationOutcome.ACCEPTED,
    MoonOperationOutcome.SAVED_PENDING,
    MoonOperationOutcome.LOCAL_REMOVED,
    -> false
    MoonOperationOutcome.ACCEPTED_NOT_SAVED,
    MoonOperationOutcome.SAVE_FAILED,
    MoonOperationOutcome.LOCAL_REMOVE_FAILED,
    MoonOperationOutcome.PARTIAL,
    MoonOperationOutcome.REJECTED,
    MoonOperationOutcome.FILE_REMAINS,
    MoonOperationOutcome.FILE_STATUS_UNKNOWN,
    MoonOperationOutcome.RECORD_REMOVE_FAILED,
    -> true
    MoonOperationOutcome.UNAVAILABLE,
    MoonOperationOutcome.INVALID_INPUT,
    -> false
}
