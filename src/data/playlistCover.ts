import {type Playlist} from '../db/playlists';
import {type LibrarySong} from './mockSongs';

/**
 * Resolve the cover image URI to show for a playlist. Priority:
 *   1. a user-uploaded image (a base64 `data:` URI)
 *   2. the chosen (or first-added) song's artwork
 *
 * Returns '' when nothing is available — `SongCover` renders its tinted
 * placeholder for an empty URI. Note `coverSongId` from `getPlaylists()` already
 * falls back to the first-added song, so no extra fallback is needed here.
 */
export function playlistCoverUri(
  playlist: Playlist,
  songsById: Map<string, LibrarySong>,
): string {
  if (playlist.coverImageUri) {
    return playlist.coverImageUri;
  }
  const song = playlist.coverSongId ? songsById.get(playlist.coverSongId) : null;
  return song?.artwork ?? '';
}
