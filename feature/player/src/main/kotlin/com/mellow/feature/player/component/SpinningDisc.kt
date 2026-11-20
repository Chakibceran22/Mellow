package com.mellow.feature.player.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.CoverArt
import com.mellow.core.designsystem.light.turning
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * The cover as a record: round, with a spindle hole, turning while [playing] and holding
 * its angle when paused. The same disc that sits in the Mellow mark.
 */
@Composable
internal fun SpinningDisc(
    artworkUri: String?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CoverArt(
            uri = artworkUri,
            modifier = Modifier.fillMaxSize().turning(running = playing, secondsPerTurn = SecondsPerTurn),
            shape = CircleShape,
            elevation = 3.dp,
        )
        Box(Modifier.size(9.dp).background(MellowTheme.colors.surface, CircleShape))
    }
}

private const val SecondsPerTurn = 14f
