/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One-line title that scrolls sideways when it does not fit.
 *
 * Video titles are routinely longer than the row they sit in, and clipping them to an
 * ellipsis hides the part that tells two videos apart. The marquee only moves when the
 * text actually overflows, so short titles sit still while long ones drift left.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeTitle(
    text: String,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Medium,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    iterations: Int = 3,
) {
    Text(
        text = text,
        style = style,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier.basicMarquee(
            iterations = iterations,
            // Immediate, not delayed: the title should already be drifting by the
            // time the row is read, not sitting still for a beat first.
            animationMode = MarqueeAnimationMode.Immediately,
            // A gap of a third of the row, so the loop point reads as a pause rather
            // than as a hard cut in the middle of the title.
            spacing = MarqueeSpacing.fractionOfContainer(1f / 3f),
            velocity = 36.dp,
        ),
    )
}