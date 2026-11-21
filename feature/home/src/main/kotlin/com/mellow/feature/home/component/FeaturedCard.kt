package com.mellow.feature.home.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.CoverArt
import com.mellow.core.designsystem.component.CoverMosaic
import com.mellow.core.designsystem.component.Icon
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.component.pressable
import com.mellow.core.designsystem.glass.GlassScene
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.designsystem.theme.asShadowCaster
import com.mellow.feature.home.ShelfItem

/**
 * The card at the top of Home: a cover edge to edge, with a strip of clear glass along the
 * bottom that names it. The glass looks through to the cover itself, so the artwork's
 * shapes bend at the strip's rim.
 *
 * It shows one of the user's own playlists. Only when they have made none does it show an
 * album from the phone instead, so the top of the page is never empty.
 */
@Composable
internal fun FeaturedCard(
    item: ShelfItem,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MellowTheme.colors
    val radii = MellowTheme.radii
    val cardShape = remember(radii) { ContinuousCornerShape(radii.card) }

    val title: String
    val subtitle: String
    when (item) {
        is ShelfItem.OfAlbum -> {
            title = item.album.title
            subtitle = if (item.album.trackCount == 1) "1 track by ${item.album.artist}" else "${item.album.trackCount} tracks by ${item.album.artist}"
        }
        is ShelfItem.OfPlaylist -> {
            title = item.playlist.name
            subtitle = if (item.playlist.trackCount == 1) "Your playlist, 1 song" else "Your playlist, ${item.playlist.trackCount} songs"
        }
    }
    // A playlist's cover: the one picture chosen for it, or four of its songs' covers.
    val playlistCovers = remember(item) { (item as? ShelfItem.OfPlaylist)?.playlist?.covers?.map { it.uri } }
    val mosaic = playlistCovers != null && playlistCovers.size >= 4

    GlassScene(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(HomeDimens.FeaturedAspect)
            .pressable(
                onClick = onClick,
                onClickLabel = if (playing) "Pause $title" else "Play $title",
                pressedScale = 0.975f,
            )
            .shadow(22.dp, cardShape.asShadowCaster(), clip = false, ambientColor = colors.shadow, spotColor = colors.shadow),
        // The cover never moves under the strip, so its frost is made once and kept.
        frost = MellowTheme.glass.clear.blur,
        backdrop = {
            // The cover takes the card's shape itself; the strip sits inside it, so nothing
            // else needs clipping. It lies on a solid card of the same shape: glass and
            // the card's shadow both need something opaque there while the cover is
            // still on its way, exactly as in the skeleton this replaces.
            // Four covers are held together by a plain rounded clip (see CoverMosaic), so the
            // card under them takes that same outline and no edge of it shows at the corners.
            Box(Modifier.fillMaxSize().background(colors.surface, if (mosaic) cardShape.asShadowCaster() else cardShape)) {
                when (item) {
                    is ShelfItem.OfAlbum -> CoverArt(item.album.artwork.uri, Modifier.fillMaxSize(), shape = cardShape)
                    is ShelfItem.OfPlaylist -> CoverMosaic(playlistCovers.orEmpty(), Modifier.fillMaxSize(), radius = radii.card)
                }
            }
        },
    ) {
        GlassSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(HomeDimens.FeaturedStripInset)
                .fillMaxWidth()
                .height(HomeDimens.FeaturedStripHeight),
            // Concentric with the card: its radius, less the gap between them.
            shape = GlassShape.Continuous(radii.concentric(radii.card, HomeDimens.FeaturedStripInset)),
            style = MellowTheme.glass.clear,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MellowTheme.type.headline, maxLines = 1)
                    Text(
                        text = subtitle,
                        style = MellowTheme.type.caption,
                        color = colors.inkMuted,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(12.dp))
                // Green glass, like every other main button. It lies on the strip, so it
                // is painted straight onto it.
                GlassSurface(
                    modifier = Modifier.size(HomeDimens.FeaturedPlayButton),
                    shape = GlassShape.Capsule,
                    style = MellowTheme.glass.accent,
                    contentColor = colors.onAccent,
                ) {
                    AnimatedContent(
                        targetState = playing,
                        transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                        label = "featuredPlay",
                    ) { isPlaying ->
                        Icon(
                            icon = if (isPlaying) MellowIcons.Pause else MellowIcons.Play,
                            contentDescription = null,
                            tint = colors.onAccent,
                            size = 24.dp,
                        )
                    }
                }
            }
        }
    }
}
