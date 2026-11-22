package com.mellow.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mellow.core.designsystem.component.Button
import com.mellow.core.designsystem.component.ButtonKind
import com.mellow.core.designsystem.component.GlassIconButton
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.glass.GlassShape
import com.mellow.core.designsystem.glass.GlassSurface
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.model.Appearance

/**
 * Settings: the things that are set up once and then left alone, kept off the pages that
 * are used every day. The look of the app first, then the backup.
 *
 * Like every page it draws no background of its own, and its buttons are glass looking
 * through to the lit room.
 *
 * @param contentPadding room to leave for the status bar above and the floating bars below.
 * @param onBack the arrow at the top was pressed.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Android's own "save as" and "open" screens. The user picks the place; Mellow is
    // given that one file and nothing else, so no storage permission is involved.
    val chooseWhereToSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { chosen ->
        if (chosen != null) viewModel.saveBackupTo(chosen.toString())
    }
    val chooseBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        if (chosen != null) viewModel.importBackupFrom(chosen.toString())
    }

    SettingsContent(
        state = state,
        contentPadding = contentPadding,
        onBack = onBack,
        onSave = { chooseWhereToSave.launch(viewModel.backupFileName()) },
        // Any file can be offered: phones disagree about what type a .json file is, and
        // one that is not a backup is told apart by reading it.
        onImport = { chooseBackup.launch(arrayOf("*/*")) },
        onAppearance = viewModel::chooseAppearance,
        modifier = modifier,
    )
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onAppearance: (Appearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MellowTheme.spacing
    val colors = MellowTheme.colors
    val gutter = Modifier.padding(horizontal = spacing.gutter)
    val count = state.playlistCount
    val songs = state.editedSongCount
    val anything = count != null && (count > 0 || songs > 0)

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "bar") {
            GlassIconButton(
                icon = MellowIcons.Back,
                contentDescription = "Back to Library",
                onClick = onBack,
                modifier = gutter.padding(top = spacing.xs),
            )
        }
        item(key = "title") {
            Text(
                text = "Settings",
                style = MellowTheme.type.display,
                modifier = gutter.padding(top = spacing.md, bottom = spacing.lg).semantics { heading() },
            )
        }
        item(key = "appearance") {
            Column(gutter.fillMaxWidth().padding(bottom = spacing.xl)) {
                Text("Appearance", style = MellowTheme.type.title, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = "Mellow is light or dark with your phone, unless you choose one here. Its widget follows the same choice.",
                    color = colors.inkMuted,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(spacing.md))
                AppearanceChoice(chosen = state.appearance, onChoose = onAppearance)
            }
        }
        item(key = "backup") {
            Column(gutter.fillMaxWidth()) {
                Text("Backup", style = MellowTheme.type.title, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = "Your playlists are kept inside Mellow, so removing the app removes them. Save a " +
                        "backup file before you reinstall, and import it afterwards to get them back. What " +
                        "you change about a song, its names or its cover, is written into the song itself, so " +
                        "it stays with the song and needs no backup. The file lists your songs; the songs " +
                        "themselves stay on the phone.",
                    color = colors.inkMuted,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = when {
                        count == null -> " "
                        !anything -> "Nothing to back up yet: no playlists, no edited songs."
                        songs == 0 -> if (count == 1) "1 playlist to back up." else "$count playlists to back up."
                        count == 0 -> if (songs == 1) "1 edited song to back up." else "$songs edited songs to back up."
                        else -> (if (count == 1) "1 playlist" else "$count playlists") + " and " +
                            (if (songs == 1) "1 edited song" else "$songs edited songs") + " to back up."
                    },
                    style = MellowTheme.type.callout,
                )
                Spacer(Modifier.height(spacing.lg))
                // Saving nothing would leave a file that looks like a backup and is not one.
                if (anything) {
                    Button(
                        text = if (state.working) "Working" else "Save a backup file",
                        onClick = { if (!state.working) onSave() },
                        icon = MellowIcons.SaveFile,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(spacing.sm))
                }
                Button(
                    text = if (state.working && !anything) "Working" else "Import a backup file",
                    onClick = { if (!state.working) onImport() },
                    kind = if (anything) ButtonKind.Quiet else ButtonKind.Primary,
                    icon = MellowIcons.OpenFile,
                    modifier = Modifier.fillMaxWidth(),
                )

                val result = state.result
                if (result != null) {
                    Spacer(Modifier.height(spacing.md))
                    Text(
                        text = result.message,
                        color = if (result.problem) colors.danger else colors.ink,
                        modifier = Modifier.widthIn(max = 360.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/**
 * The three looks, side by side: one of them is always chosen. Each is a piece of glass,
 * and the chosen one fills with green, the way the shuffle switch does when it is on, so
 * the choice can be read without reading the words.
 */
@Composable
private fun AppearanceChoice(chosen: Appearance, onChoose: (Appearance) -> Unit) {
    val colors = MellowTheme.colors
    val glass = MellowTheme.glass
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (appearance in Appearance.entries) {
            val isChosen = appearance == chosen
            GlassSurface(
                modifier = Modifier
                    .weight(1f)
                    .height(ChoiceHeight)
                    .semantics { selected = isChosen },
                shape = GlassShape.Capsule,
                style = if (isChosen) glass.accent else glass.regular,
                onClick = { onChoose(appearance) },
                role = Role.RadioButton,
                pressScale = 1.05f,
                contentColor = if (isChosen) colors.onAccent else colors.ink,
            ) {
                Text(
                    text = when (appearance) {
                        Appearance.Automatic -> "Automatic"
                        Appearance.Light -> "Light"
                        Appearance.Dark -> "Dark"
                    },
                    style = MellowTheme.type.callout,
                    maxLines = 1,
                )
            }
        }
    }
}

private val ChoiceHeight = 46.dp
