package com.mellow.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import com.mellow.core.designsystem.component.SearchField
import com.mellow.core.designsystem.component.SearchFieldHeight

/** How tall [SearchBar] is, so the shell can leave room for it above the Search page. */
val SearchBarHeight: Dp = SearchFieldHeight

/**
 * The glass bar the Search page is typed into.
 *
 * It is a separate composable from [SearchScreen] because the two belong at different
 * depths. Pages are part of the backdrop that the app's glass looks through, and glass
 * cannot sit inside the thing it samples. So the shell places this with the rest of the
 * floating chrome, over the pages, where it refracts the lit room and the results that
 * scroll beneath it out of the one frost the app already makes.
 *
 * It never takes focus by itself: the keyboard comes up when the bar is tapped and at no
 * other time.
 *
 * @param active true while Search is the page on screen. Leaving the page takes the
 *   keyboard with it, and while another page is showing the bar cannot be focused at all:
 *   it is still there, parked off to the side, and Android hands focus to the first text
 *   field it finds when an app opens under a hardware keyboard. Without this the keyboard
 *   would come up over Home for a field nobody can see.
 * @param modifier where the bar goes: its width, its position, and its slide with the pager.
 */
@Composable
fun SearchBar(
    viewModel: SearchViewModel,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active && focused) focusManager.clearFocus()
    }

    SearchField(
        query = viewModel.query,
        onQueryChange = viewModel::onQueryChange,
        placeholder = "Songs, albums, artists",
        // Results are already live; the search key only has to get the keyboard out of the way.
        onSearch = { focusManager.clearFocus() },
        modifier = modifier
            .focusProperties { canFocus = active }
            .onFocusChanged { focused = it.hasFocus },
    )
}
