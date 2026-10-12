/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auramusic.app.R
import com.auramusic.innertube.models.Artist

/**
 * Channel chooser for videos that credit more than one channel.
 *
 * A credit line like "A & B" carries a link per channel, and a plain text row can only honour
 * one of them. This dialog lists every linked channel - with its art, not a grey glyph - so
 * the viewer picks which page to open.
 */
@Composable
fun ChannelPickerDialog(
    channels: List<Artist>,
    onDismiss: () -> Unit,
    onSelect: (Artist) -> Unit,
    primaryThumbnailUrl: String? = null,
    subscribedChannelIds: Set<String> = emptySet(),
    onToggleSubscribe: ((Artist) -> Unit)? = null,
) {
    ListDialog(onDismiss = onDismiss) {
        items(channels, key = { it.id ?: it.name }) { channel ->
            val subscribed = channel.id != null && channel.id in subscribedChannelIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = channel.id != null) { onSelect(channel) }
                    .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CreditedChannelAvatar(
                    channel = channel,
                    thumbnailUrl = if (channel == channels.firstOrNull()) primaryThumbnailUrl else null,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // A collaboration credits more than one channel, and each is followed
                // in its own right - so the follow control sits on the row rather than
                // applying to whichever channel happens to be first.
                if (onToggleSubscribe != null && channel.id != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = { onToggleSubscribe(channel) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (subscribed) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.height(32.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(
                                    if (subscribed) R.string.subscribed else R.string.subscribe,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (subscribed) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
