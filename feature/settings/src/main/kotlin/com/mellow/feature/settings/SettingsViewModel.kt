package com.mellow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mellow.core.data.AppearanceRepository
import com.mellow.core.data.ExportOutcome
import com.mellow.core.data.ImportOutcome
import com.mellow.core.data.LibraryRepository
import com.mellow.core.data.PlaylistRepository
import com.mellow.core.model.Appearance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Settings has two jobs. One is the app's look: light, dark, or whichever the phone is
 * in. The other is keeping what lives only inside the app safe across a reinstall. That
 * is the playlists and whatever the user has edited about their songs. Removing the app
 * removes them; a backup file, saved wherever the user likes, brings them back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val appearance: AppearanceRepository,
) : ViewModel() {

    private val progress = MutableStateFlow(Progress())

    /** Counted again whenever a playlist or the phone's music (and with it, any song edit) changes. */
    private val contents = combine(playlists.revision, library.revision) { _, _ -> }.mapLatest { playlists.backupContents() }

    val uiState: StateFlow<SettingsUiState> =
        combine(contents, progress, appearance.appearance) { contents, progress, appearance ->
            SettingsUiState(
                appearance = appearance,
                playlistCount = contents.playlists,
                editedSongCount = contents.songs,
                working = progress.working,
                result = progress.result,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(appearance = appearance.appearance.value))

    /** The user picked a look. It takes effect at once, on the app and on its widgets. */
    fun chooseAppearance(chosen: Appearance) = appearance.choose(chosen)

    /** What to offer as the backup's name. Dated, so two backups in one folder can be told apart. */
    fun backupFileName(): String = "Mellow backup ${LocalDate.now()}.json"

    /** The user chose where to save the backup. */
    fun saveBackupTo(destinationUri: String) = start {
        when (val outcome = playlists.exportTo(destinationUri)) {
            is ExportOutcome.Saved -> BackupResult(
                when {
                    outcome.songs == 0 -> "Saved ${counted(outcome.playlists, "playlist")} to the file."
                    outcome.playlists == 0 -> "Saved your edits to ${counted(outcome.songs, "song")} to the file."
                    else -> "Saved ${counted(outcome.playlists, "playlist")} and your edits to ${counted(outcome.songs, "song")} to the file."
                },
            )
            ExportOutcome.NeedsAccess -> BackupResult(NeedsAccess, problem = true)
            ExportOutcome.Failed -> BackupResult(
                "The backup could not be saved there. Save it to another folder, such as Downloads.",
                problem = true,
            )
        }
    }

    /** The user chose a backup file to bring in. */
    fun importBackupFrom(sourceUri: String) = start {
        when (val outcome = playlists.importFrom(sourceUri)) {
            is ImportOutcome.Restored -> BackupResult(restored(outcome))
            ImportOutcome.NeedsAccess -> BackupResult(NeedsAccess, problem = true)
            ImportOutcome.NotABackup -> BackupResult(
                "That file is not a Mellow backup. Choose a file that was saved from this page.",
                problem = true,
            )
            ImportOutcome.Unreadable -> BackupResult(
                "That file could not be opened. Check that it is still where it was saved, then choose it again.",
                problem = true,
            )
        }
    }

    private fun restored(outcome: ImportOutcome.Restored): String {
        val added = when {
            outcome.playlists == 0 && outcome.songs == 0 -> "That backup holds nothing."
            outcome.tracks == 0 && outcome.missing == 0 && outcome.songs == 0 -> "Everything in that backup is already here."
            outcome.tracks == 0 && outcome.songs == 0 -> "Nothing was added."
            outcome.tracks == 0 -> "Put back your edits to ${counted(outcome.songs, "song")}."
            outcome.songs == 0 -> "Imported ${counted(outcome.playlists, "playlist")}, adding ${counted(outcome.tracks, "song")}."
            else -> "Imported ${counted(outcome.playlists, "playlist")}, adding ${counted(outcome.tracks, "song")}, " +
                "and put back your edits to ${counted(outcome.songs, "song")}."
        }
        val left = when (outcome.missing) {
            0 -> ""
            1 -> " 1 song in the backup is not on this phone, so it was left out."
            else -> " ${outcome.missing} songs in the backup are not on this phone, so they were left out."
        }
        return added + left
    }

    /** One backup job at a time, with the page told while it runs and what came of it. */
    private fun start(job: suspend () -> BackupResult) {
        if (progress.value.working) return
        progress.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = job()
            progress.value = Progress(working = false, result = result)
        }
    }

    private fun counted(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    private data class Progress(val working: Boolean = false, val result: BackupResult? = null)

    companion object {
        private const val NeedsAccess =
            "Mellow has to read your music to match songs to the backup. Allow access to music on Home, then try again."

        fun factory(library: LibraryRepository, playlists: PlaylistRepository, appearance: AppearanceRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { SettingsViewModel(library, playlists, appearance) } }
    }
}
