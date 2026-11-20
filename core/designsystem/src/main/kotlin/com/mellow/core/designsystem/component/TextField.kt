package com.mellow.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.theme.ContinuousCornerShape
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * One line of text to type, for naming things. A quiet well in whatever it sits on; unlike
 * the search field it is not glass, because it lives inside sheets that already are.
 *
 * It takes a [TextFieldValue] so the caller can hand it text that is already selected: a
 * name being changed is usually being replaced.
 *
 * @param label what is being typed, for screen readers. The visible label is the sheet's title.
 * @param onDone the keyboard's done key was pressed.
 */
@Composable
fun TextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onDone: () -> Unit = {},
) {
    val colors = MellowTheme.colors
    val type = MellowTheme.type
    val shape = remember { ContinuousCornerShape(18.dp) }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(TextFieldHeight)
            .drawBehind { drawOutline(shape.createOutline(size, layoutDirection, this), colors.wash) }
            .semantics { contentDescription = label },
        textStyle = type.body.copy(color = colors.ink),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { innerTextField ->
            Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    Text(placeholder, style = type.body, color = colors.inkFaint, maxLines = 1)
                }
                innerTextField()
            }
        },
    )
}

val TextFieldHeight = 54.dp
