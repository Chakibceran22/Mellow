package com.mellow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * One thing that can be done to whatever is selected.
 *
 * @property label a word or two under the icon.
 * @property description the whole of what it does, for screen readers, when [label] is short for it.
 * @property destructive true for an action that deletes for good; it is drawn in the danger colour.
 */
@Immutable
class BarAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val description: String = label,
    val destructive: Boolean = false,
)

/**
 * The bar that takes the place of the tab bar while things are selected: how many are
 * picked, a way out, and what can be done with them. Give it only the actions that make
 * sense where the user is; that is what keeps it short.
 *
 * Glass, so it belongs in the content layer of a `GlassScene`.
 *
 * @param title what is selected, in words: "3 songs selected".
 * @param onClose leave selection without doing anything.
 */
@Composable
fun SelectionBar(
    title: String,
    actions: List<BarAction>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MellowTheme.colors
    GlassSurface(
        modifier = modifier,
        shape = GlassShape.Continuous(MellowTheme.radii.card),
        // Thick: it carries small labels, and it comes up over a list that is still showing.
        style = MellowTheme.glass.thick,
        contentAlignment = Alignment.TopStart,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(MellowIcons.Close, "Stop selecting", onClose, iconSize = 18.dp)
                Text(
                    text = title,
                    style = MellowTheme.type.headline,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(Modifier.fillMaxWidth().height(ActionRowHeight)) {
                actions.forEach { action ->
                    val tint = if (action.destructive) colors.danger else colors.ink
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressable(onClick = action.onClick, onClickLabel = action.description, pressedScale = 0.92f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Icon(action.icon, contentDescription = null, tint = tint, size = 23.dp)
                        Text(action.label, style = MellowTheme.type.label, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val ActionRowHeight = 58.dp
