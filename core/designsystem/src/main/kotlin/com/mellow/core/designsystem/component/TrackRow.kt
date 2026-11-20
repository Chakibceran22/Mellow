package com.mellow.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.designsystem.theme.tabular

/** The measurements of a [TrackRow], shared with [TrackRowSkeleton] so the two cannot drift apart. */
object TrackRowDefaults {
    val Thumb = 52.dp
    val ThumbRadius = 14.dp
    val VerticalPadding = 9.dp
    val Gap = 14.dp

    /** How far the wash behind a picked row stops short of the screen edge. */
    val SelectedInset = 8.dp
    val SelectedRadius = 20.dp
}

/**
 * One item in a list of music: a cover, a title, a line under it, and something short at
 * the far end. Built for tracks; it suits an album or anything else with a cover as well.
 *
 * The item that is loaded in the player takes the accent colour and trades its trailing
 * text for moving bars.
 *
 * While the list is being selected from, the far end shows a [SelectionMark] instead, and
 * a picked row lies on a soft wash so a run of picked rows reads as one block.
 *
 * @param trailing short text at the end of the row, such as a duration. Use [formatDuration].
 * @param current true for the track loaded in the player, playing or paused.
 * @param playing whether sound is coming out right now. Only matters when [current].
 * @param selected null when the list is not being selected from; otherwise whether this
 *   row is picked. The caller decides what a tap does in each case.
 * @param onLongClick pressing and holding, which is how selecting starts.
 */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    current: Boolean = false,
    playing: Boolean = false,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MellowTheme.colors
    val thumbShape = remember { ContinuousCornerShape(TrackRowDefaults.ThumbRadius) }
    val wash by animateFloatAsState(if (selected == true) 1f else 0f, MellowMotion.settle(), label = "rowWash")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (selected != null) this.selected = selected }
            .pressable(
                onClick = onClick,
                pressedScale = 0.98f,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null && selected == null) "Select" else null,
            )
            .drawBehind {
                if (wash > 0f) {
                    val inset = TrackRowDefaults.SelectedInset.toPx()
                    val gap = 2.dp.toPx()
                    drawRoundRect(
                        color = colors.wash,
                        topLeft = Offset(inset, gap),
                        size = Size(size.width - inset * 2, size.height - gap * 2),
                        cornerRadius = CornerRadius(TrackRowDefaults.SelectedRadius.toPx()),
                        alpha = wash.coerceIn(0f, 1f),
                    )
                }
            }
            .padding(horizontal = MellowTheme.spacing.gutter, vertical = TrackRowDefaults.VerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(artworkUri, Modifier.size(TrackRowDefaults.Thumb), shape = thumbShape, elevation = 4.dp)
        Spacer(Modifier.width(TrackRowDefaults.Gap))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MellowTheme.type.headline,
                color = if (current) colors.accent else colors.ink,
                maxLines = 1,
            )
            Text(subtitle, style = MellowTheme.type.caption, color = colors.inkMuted, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.widthIn(min = 36.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                selected != null -> SelectionMark(selected)
                current -> EqualizerBars(playing = playing)
                trailing != null -> Text(trailing, style = MellowTheme.type.caption.tabular, color = colors.inkFaint)
            }
        }
    }
}

/**
 * A [TrackRow] that has not arrived yet: the same height, the same cover, bars where the
 * words will be.
 *
 * @param index position in the list. Titles are different lengths; so are these.
 */
@Composable
fun TrackRowSkeleton(modifier: Modifier = Modifier, index: Int = 0) {
    val thumbShape = remember { ContinuousCornerShape(TrackRowDefaults.ThumbRadius) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MellowTheme.spacing.gutter, vertical = TrackRowDefaults.VerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Skeleton(Modifier.size(TrackRowDefaults.Thumb), thumbShape)
        Spacer(Modifier.width(TrackRowDefaults.Gap))
        Column(Modifier.weight(1f)) {
            SkeletonTextLine(width = (112 + (index * 37) % 64).dp, lineHeight = 22.dp, ink = 14.dp)
            SkeletonTextLine(width = 84.dp, lineHeight = 18.dp, ink = 11.dp)
        }
        SkeletonTextLine(width = 30.dp, lineHeight = 18.dp, ink = 11.dp)
    }
}

/** A length of time as minutes and seconds: 3:07. */
fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
