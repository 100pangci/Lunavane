package nova.lunavane.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun BrandMark(size: Dp = 34.dp) {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.085f
            val left = this.size.width * 0.37f
            val top = this.size.height * 0.27f
            val bottom = this.size.height * 0.71f
            val right = this.size.width * 0.66f
            drawLine(
                color = color,
                start = Offset(left, top),
                end = Offset(left, bottom),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(left, bottom),
                end = Offset(right, bottom),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
