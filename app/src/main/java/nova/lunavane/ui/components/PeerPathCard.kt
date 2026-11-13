package nova.lunavane.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nova.lunavane.R
import nova.lunavane.vpn.PeerPathInfo

@Composable
internal fun PeerPathCard(path: PeerPathInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer {
                Text(
                    text = path.endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (path.preferred) {
                    StatusPill(
                        text = stringResource(R.string.networks_peer_path_preferred),
                        tone = StatusTone.SUCCESS,
                    )
                }
                if (path.active) {
                    StatusPill(
                        text = stringResource(R.string.networks_peer_path_active),
                        tone = StatusTone.PRIMARY,
                    )
                }
                if (!path.preferred && !path.active) {
                    StatusPill(text = stringResource(R.string.networks_peer_path_idle))
                }
            }
        }
    }
}
