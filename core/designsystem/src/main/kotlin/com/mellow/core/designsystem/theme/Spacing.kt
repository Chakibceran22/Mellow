package com.mellow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A 4dp rhythm. [gutter] is the margin between content and the screen edge. */
@Immutable
data class MellowSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 28.dp,
    val xxl: Dp = 40.dp,
    val gutter: Dp = 20.dp,
)
