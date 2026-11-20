package com.mellow.app

import android.content.Context
import com.mellow.core.data.LibraryRepository
import com.mellow.core.data.PlaylistRepository
import com.mellow.core.data.device.DeviceLibraryRepository
import com.mellow.core.data.playlist.LocalPlaylistRepository
import com.mellow.core.playback.MediaPlaybackController
import com.mellow.core.playback.PlaybackController

/**
 * Where interfaces meet their implementations. This is the only place that names a
 * concrete repository or controller; everything else receives the interface.
 *
 * Plain constructor wiring rather than a DI framework: the graph is two objects, and it
 * keeps annotation processing out of the build. Every class already takes its
 * dependencies through its constructor, so adopting one later changes this file only.
 */
class AppContainer(context: Context) {

    private val deviceLibrary = DeviceLibraryRepository(context)

    /** The music already stored on the phone, as the user has edited it. */
    val library: LibraryRepository = deviceLibrary

    /**
     * The playlists the user makes out of it, kept in the app's own storage. It is given
     * the library as the class it is because it also writes the backup, and the backup
     * carries the library's song edits.
     */
    val playlists: PlaylistRepository = LocalPlaylistRepository(context, deviceLibrary)

    val playback: PlaybackController = MediaPlaybackController(context)
}
