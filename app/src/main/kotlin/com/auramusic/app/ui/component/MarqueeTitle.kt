/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One-line title that scrolls sideways when it does not fit.
 *
 * Video titles are routinely longer than the row they sit in, and clipping them to an
 * ellipsis hides the part that tells two videos apart. The text drifts left and wraps
 * round to a second copy, and only when it genuinely overflows - short titles never move.
 *
 * The scroll is a translation of two copies of the text rather than Compose's own
 * marquee, which measures its content in a subcomposition: inside a lazy grid cell, or a
 * row that hands this a zero width while it is being laid out, that path can be asked to
 * measure with constraints it does not expect and comes back null. This one only ever
 * shifts pixels it has already measured.
 */
@Composable
fun MarqueeTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { MarqueeGap.roundToPx() }

    var textWidth by remember(text, style) { mutableIntStateOf(0) }
    var containerWidth by remember { mutableIntStateOf(0) }
    val overflows = textWidth > containerWidth && containerWidth > 0

    val transition = rememberInfiniteTransition(label = "marquee")
    // Speed is in pixels per second, so the same title travels the same way whatever
    // its length and whatever the text size.
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -textWidth.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (textWidth > 0) {
                    ((textWidth + gapPx) * 1000f / MarqueeVelocityPxPerSecond)
                        .roundToInt().coerceAtLeast(1200)
                } else {
                    1200
                },
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "marqueeOffset",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { containerWidth = it.width },
    ) {
        Row(
            modifier = Modifier.graphicsLayer {
                // Holds still until the text is actually wider than its row.
                translationX = if (overflows) offset else 0f
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = style,
                fontWeight = fontWeight,
                maxLines = maxLines,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.onSizeChanged { textWidth = it.width },
            )
            if (overflows) {
                Spacer(modifier = Modifier.width(MarqueeGap))
                // The second copy is what the first scrolls into, so the loop has no
                // visible seam.
                Text(
                    text = text,
                    style = style,
                    fontWeight = fontWeight,
                    maxLines = maxLines,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** Quiet moment between the end of one copy and the start of the next. */
private val MarqueeGap = 28.dp

private const val MarqueeVelocityPxPerSecond = 34f