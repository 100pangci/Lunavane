package nova.lunavane.ui

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Reflects the system "remove animations" preference so status animations can be
 * replaced with static indicators.
 */
@Composable
internal fun rememberMotionEnabled(): Boolean {
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = ValueAnimator.areAnimatorsEnabled()
    }
    return enabled
}
