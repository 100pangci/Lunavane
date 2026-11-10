package dev.nova.zerotier.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nova.zerotier.DiagnosticEntry
import dev.nova.zerotier.TunnelUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun HomeScreen(
    state: TunnelUiState,
    initialNetworkId: String,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOrbit: (String, String) -> Unit,
    onDeorbit: (String) -> Unit,
    diagnostics: List<DiagnosticEntry>,
    onClearDiagnostics: () -> Unit,
) {
    var networkId by remember { mutableStateOf(initialNetworkId) }
    var moonWorldId by remember { mutableStateOf("") }
    var moonSeed by remember { mutableStateOf("") }
    val active = state.tunnelActive
    val moonAvailable = active && state.nodeReady
    val validMoonWorldId = moonWorldId.length == 16 &&
            moonWorldId.all { it.digitToIntOrNull(16) != null } && moonWorldId.toULongOrNull(16) != 0uL
    val validMoonSeed = moonSeed.length == 10 &&
            moonSeed.all { it.digitToIntOrNull(16) != null } && moonSeed.toULongOrNull(16) != 0uL

    LaunchedEffect(initialNetworkId) {
        if (networkId.isBlank()) networkId = initialNetworkId
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "LUNAVANE  /  ZERO TIER",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text("Network", fontSize = 32.sp, fontWeight = FontWeight.Medium)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.width(10.dp))
                Text(state.status, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            }
            if (state.detail.isNotBlank()) {
                Text(state.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            if (state.nodeAddress.isNotBlank()) {
                DataRow("Node", state.nodeAddress)
            }
            if (state.networkName.isNotBlank()) {
                DataRow("Name", state.networkName)
            }
            state.assignedAddresses.forEach { DataRow("Address", it) }
            if (active) {
                Text("Routes", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (state.activeRoutes.isEmpty()) {
                    Text("No active routes", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    state.activeRoutes.forEach { DataRow("Route", it) }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Join network", fontWeight = FontWeight.Medium, fontSize = 17.sp)
            OutlinedTextField(
                value = networkId,
                onValueChange = { networkId = it.filter { char -> char.digitToIntOrNull(16) != null }.take(16) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("16-character network ID") },
                singleLine = true,
                enabled = !active,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onConnect(networkId) },
                    enabled = !active && networkId.length == 16 && networkId.all { it.digitToIntOrNull(16) != null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Connect")
                }
                if (active) {
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Moons", fontWeight = FontWeight.Medium, fontSize = 17.sp)
            Text(
                "Orbit with a Moon world ID and one of its seed node addresses.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            OutlinedTextField(
                value = moonWorldId,
                onValueChange = { moonWorldId = it.filter { char -> char.digitToIntOrNull(16) != null }.take(16) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Moon world ID · 16 hex digits") },
                singleLine = true,
                enabled = moonAvailable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
            )
            OutlinedTextField(
                value = moonSeed,
                onValueChange = { moonSeed = it.filter { char -> char.digitToIntOrNull(16) != null }.take(10) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Seed node address · 10 hex digits") },
                singleLine = true,
                enabled = moonAvailable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
            )
            Button(
                onClick = { onOrbit(moonWorldId, moonSeed) },
                enabled = moonAvailable && validMoonWorldId && validMoonSeed,
            ) {
                Text("Orbit")
            }
            if (state.moonMessage.isNotBlank()) {
                Text(state.moonMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            if (state.moonSeeds.isEmpty()) {
                Text("No saved Moon orbit requests.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                state.moonSeeds.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.worldId, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Text("Seed ${entry.seed}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onDeorbit(entry.worldId) }, enabled = moonAvailable) {
                            Text("Deorbit", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Diagnostics", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                TextButton(onClick = onClearDiagnostics, enabled = diagnostics.isNotEmpty()) {
                    Text("Clear")
                }
            }
            if (diagnostics.isEmpty()) {
                Text("No recent events.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                diagnostics.takeLast(10).asReversed().forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            Instant.ofEpochMilli(entry.timestampMillis)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                        Text(entry.message, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp), fontSize = 13.sp)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}
