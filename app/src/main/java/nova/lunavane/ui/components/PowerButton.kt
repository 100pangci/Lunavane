package nova.lunavane.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nova.lunavane.ui.ConnectionStatus
import nova.lunavane.ui.isBusy
import nova.lunavane.ui.isFailure
import nova.lunavane.ui.rememberMotionEnabled

@Composable
internal fun PowerButton(
    status: ConnectionStatus,
    enabled: Boolean,
    contentDescription: String,
    stateDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 176.dp,
) {
    val colors = MaterialTheme.colorScheme
    val motionEnabled = rememberMotionEnabled()
    val busy = status.isBusy
    val connected = status == ConnectionStatus.CONNECTED
    val failure = status.isFailure

    val containerColor = when {
        connected -> colors.primaryContainer
        failure -> colors.errorContainer
        else -> colors.surfaceContainerHigh
    }
    val ringColor = when {
        connected -> colors.primary
        failure -> colors.error
        busy -> colors.primary
        else -> colors.outlineVariant
    }
    val iconColor = when {
        connected -> colors.onPrimaryContainer
        failure -> colors.onErrorContainer
        enabled -> colors.onSurface
        else -> colors.onSurfaceVariant
    }

    val rotation = if (busy && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "power-ring")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
            ),
            label = "power-ring-rotation",
        )
        value
    } else {
        0f
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = if (motionEnabled) tween(120) else snap(),
        label = "power-press-scale",
    )

    Box(
        modifier = modifier
            .size(diameter)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClickLabel = contentDescription,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = (size.minDimension * 0.022f).coerceAtLeast(3.dp.toPx())
            val glyphStroke = (size.minDimension * 0.018f).coerceAtLeast(2.5f.dp.toPx())
            val radius = size.minDimension / 2f - strokeWidth / 2f - size.minDimension * 0.03f
            val center = center
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            drawCircle(
                color = colors.outlineVariant.copy(alpha = 0.45f),
                radius = radius,
                style = Stroke(width = strokeWidth),
            )
            when {
                connected || failure -> drawCircle(
                    color = ringColor,
                    radius = radius,
                    style = Stroke(width = strokeWidth),
                )
                busy && motionEnabled -> drawArc(
                    color = ringColor,
                    startAngle = -90f + rotation,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                busy -> drawCircle(
                    color = ringColor,
                    radius = radius,
                    style = Stroke(width = strokeWidth),
                )
            }

            val glyphRadius = radius * 0.55f
            drawArc(
                color = iconColor,
                startAngle = -50f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(center.x - glyphRadius, center.y - glyphRadius),
                size = Size(glyphRadius * 2f, glyphRadius * 2f),
                style = Stroke(width = glyphStroke, cap = StrokeCap.Round),
            )
            drawLine(
                color = iconColor,
                start = Offset(center.x, center.y - glyphRadius * 1.2f),
                end = Offset(center.x, center.y - glyphRadius * 0.15f),
                strokeWidth = glyphStroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
