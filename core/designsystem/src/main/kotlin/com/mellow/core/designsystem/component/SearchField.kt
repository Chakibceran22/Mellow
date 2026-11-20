package com.mellow.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.designsystem.theme.MellowTheme

/** How tall a [SearchField] is. Screens that float one over a list leave this much room for it. */
val SearchFieldHeight = 52.dp

/**
 * A capsule of glass to type a search into.
 *
 * It is glass, so it follows the rule for glass: it must sit in the `content` layer of a
 * `GlassScene`, floating over whatever it searches, never inside the scene's backdrop. And
 * that backdrop must be opaque wherever the field can be: glass shows what is behind it and
 * nothing else, so over an empty, see-through backdrop it would show black. Results that
 * scroll beneath it then bend at its rim like anything else behind glass. Outside a scene
 * it falls back to a flat fill.
 *
 * The whole capsule is the text field, magnifying glass included, so a tap anywhere on it
 * starts typing. It never takes focus by itself.
 *
 * @param placeholder shown while the field is empty; also what a screen reader calls it.
 * @param onSearch the keyboard's search key was pressed.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onSearch: () -> Unit = {},
) {
    val colors = MellowTheme.colors
    val type = MellowTheme.type
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // The glass takes the accent while it is being typed into: the one sign of focus.
    val glyph by animateColorAsState(
        targetValue = if (focused) colors.accent else colors.inkMuted,
        animationSpec = MellowMotion.settle(),
        label = "searchGlyph",
    )

    GlassSurface(
        modifier = modifier.height(SearchFieldHeight),
        shape = GlassShape.Capsule,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = placeholder },
            textStyle = type.body.copy(color = colors.ink),
            singleLine = true,
            // Names of songs and artists are not dictionary words; leave them as typed.
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxSize().padding(start = EdgeInset, end = ClearInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MellowIcons.Search, contentDescription = null, tint = glyph, size = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(placeholder, style = type.body, color = colors.inkFaint, maxLines = 1)
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(
                            icon = MellowIcons.Clear,
                            contentDescription = "Clear search",
                            onClick = { onQueryChange("") },
                            tint = colors.inkMuted,
                            iconSize = 16.dp,
                            touchTarget = ClearTarget,
                        )
                    } else {
                        // Keeps the text clear of the round end, as far as it is from the start.
                        Spacer(Modifier.width(EdgeInset - ClearInset))
                    }
                }
            },
        )
    }
}

private val EdgeInset = 18.dp
private val ClearTarget = 44.dp

/** Centres the clear button in the capsule's round end. */
private val ClearInset = (SearchFieldHeight - ClearTarget) / 2
