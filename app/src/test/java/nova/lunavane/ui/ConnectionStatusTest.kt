package nova.lunavane.ui

import nova.lunavane.NodeState
import nova.lunavane.TunnelUiState
import nova.lunavane.VirtualNetworkState
import nova.lunavane.vpn.NetworkRecoveryPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusTest {
    @Test
    fun `inactive tunnel is disconnected`() {
        assertEquals(ConnectionStatus.DISCONNECTED, deriveConnectionStatus(TunnelUiState()))
    }

    @Test
    fun `inactive tunnel with last error is error`() {
        val state = TunnelUiState(lastError = "Core initialization failed")
        assertEquals(ConnectionStatus.ERROR, deriveConnectionStatus(state))
    }

    @Test
    fun `active tunnel alone is not connected`() {
        val state = TunnelUiState(tunnelActive = true, nodeState = NodeState.STARTING)
        assertEquals(ConnectionStatus.CONNECTING, deriveConnectionStatus(state))
    }

    @Test
    fun `joining network is connecting`() {
        val state = TunnelUiState(
            tunnelActive = true,
            nodeState = NodeState.READY,
            networkState = VirtualNetworkState.JOINING,
            recoveryPhase = NetworkRecoveryPhase.READY,
        )
        assertEquals(ConnectionStatus.CONNECTING, deriveConnectionStatus(state))
    }

    @Test
    fun `pending network waits for authorization`() {
        val state = TunnelUiState(
            tunnelActive = true,
            nodeReady = true,
            nodeState = NodeState.READY,
            networkState = VirtualNetworkState.PENDING,
            recoveryPhase = NetworkRecoveryPhase.READY,
        )
        assertEquals(ConnectionStatus.WAITING_FOR_AUTHORIZATION, deriveConnectionStatus(state))
    }

    @Test
    fun `authorized network with ready transport is connected`() {
        val state = authorizedState()
        assertEquals(ConnectionStatus.CONNECTED, deriveConnectionStatus(state))
    }

    @Test
    fun `authorized network during switch reports switching`() {
        val state = authorizedState(recoveryPhase = NetworkRecoveryPhase.SWITCHING)
        assertEquals(ConnectionStatus.SWITCHING_NETWORK, deriveConnectionStatus(state))
    }

    @Test
    fun `authorized network during recovery reports recovering`() {
        val state = authorizedState(recoveryPhase = NetworkRecoveryPhase.RECOVERING)
        assertEquals(ConnectionStatus.RECOVERING, deriveConnectionStatus(state))
        assertEquals(
            ConnectionStatus.RECOVERING,
            deriveConnectionStatus(authorizedState(recoveryPhase = NetworkRecoveryPhase.RETRY_WAIT)),
        )
    }

    @Test
    fun `exhausted recovery is recovery failure`() {
        val state = authorizedState(recoveryPhase = NetworkRecoveryPhase.EXHAUSTED)
        assertEquals(ConnectionStatus.RECOVERY_FAILED, deriveConnectionStatus(state))
    }

    @Test
    fun `fatal network states are errors`() {
        for (networkState in listOf(
            VirtualNetworkState.ACCESS_DENIED,
            VirtualNetworkState.NOT_FOUND,
            VirtualNetworkState.PORT_ERROR,
            VirtualNetworkState.CLIENT_TOO_OLD,
            VirtualNetworkState.AUTH_REQUIRED,
            VirtualNetworkState.JOIN_FAILED,
            VirtualNetworkState.CONFIG_ERROR,
        )) {
            assertEquals(
                networkState.name,
                ConnectionStatus.ERROR,
                deriveConnectionStatus(authorizedState().copy(networkState = networkState)),
            )
        }
    }

    @Test
    fun `failed node is error`() {
        val state = authorizedState().copy(nodeState = NodeState.FAILED)
        assertEquals(ConnectionStatus.ERROR, deriveConnectionStatus(state))
    }

    @Test
    fun `degraded or offline status while transport is ready reports recovering`() {
        assertEquals(
            ConnectionStatus.RECOVERING,
            deriveConnectionStatus(authorizedState().copy(status = "Degraded")),
        )
        assertEquals(
            ConnectionStatus.RECOVERING,
            deriveConnectionStatus(authorizedState().copy(status = "Offline")),
        )
    }

    private fun authorizedState(
        recoveryPhase: NetworkRecoveryPhase = NetworkRecoveryPhase.READY,
    ): TunnelUiState = TunnelUiState(
        status = "Connected",
        tunnelActive = true,
        nodeReady = true,
        nodeState = NodeState.READY,
        networkState = VirtualNetworkState.AUTHORIZED,
        recoveryPhase = recoveryPhase,
    )
}
