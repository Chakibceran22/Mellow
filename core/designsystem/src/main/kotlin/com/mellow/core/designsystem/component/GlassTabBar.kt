package com.mellow.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.theme.MellowTheme

@Immutable
data class TabItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * The floating tab bar: one glass capsule with a lozenge marking the current tab. The
 * lozenge is inset from the capsule by a constant gap, so the two curves stay concentric
 * wherever it rests.
 *
 * @param position where the lozenge is, in tabs: 0 is the first tab, 1.5 is halfway
 *   between the second and third. Read while drawing, so it can follow a finger dragging
 *   between pages without recomposing anything.
 */
@Composable
fun GlassTabBar(
    tabs: List<TabItem>,
    selectedIndex: Int,
    position: () -> Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MellowTheme.colors

    GlassSurface(modifier = modifier.height(TabBarHeight), shape = GlassShape.Capsule) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup()
                .drawBehind {
                    val inset = LozengeInset.toPx()
                    val slot = size.width / tabs.size
                    val height = size.height - inset * 2
                    drawRoundRect(
                        color = colors.wash,
                        topLeft = Offset(slot * position().coerceIn(0f, tabs.size - 1f) + inset, inset),
                        size = Size(slot - inset * 2, height),
                        cornerRadius = CornerRadius(height / 2f),
                    )
                },
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(if (selected) colors.accent else colors.inkMuted, label = "tabTint")
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = selected }
                        .pressable(onClick = { onSelect(index) }, role = Role.Tab, pressedScale = 0.90f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = tint, size = 23.dp)
                    Text(tab.label, style = MellowTheme.type.label, color = tint, maxLines = 1)
                }
            }
        }
    }
}

val TabBarHeight = 64.dp
private val LozengeInset = 5.dp
