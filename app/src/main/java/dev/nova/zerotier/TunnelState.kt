package dev.nova.zerotier

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class TunnelUiState(
    val status: String = "Disconnected",
    val detail: String = "",
    val tunnelActive: Boolean = false,
    val nodeReady: Boolean = false,
    val nodeAddress: String = "",
    val networkId: String = "",
    val networkName: String = "",
    val assignedAddresses: List<String> = emptyList(),
    val activeRoutes: List<String> = emptyList(),
    val moonSeeds: List<MoonSeed> = emptyList(),
    val moonMessage: String = "",
)

internal object TunnelState {
    private val mutableState = MutableStateFlow(TunnelUiState())
    val state = mutableState.asStateFlow()

    fun update(transform: (TunnelUiState) -> TunnelUiState) = mutableState.update(transform)
    fun reset() {
        mutableState.update { current ->
            TunnelUiState(moonSeeds = current.moonSeeds, moonMessage = current.moonMessage)
        }
    }
}
