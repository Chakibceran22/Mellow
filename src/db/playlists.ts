import {open, type DB} from '@op-engineering/op-sqlite';

/**
 * Local persistence for user playlists, backed by op-sqlite.
 *
 * Two tables: `playlists` (just a name + creation time for now) and
 * `playlist_songs` (the join, keyed by the MediaStore song id). Songs aren't
 * added yet — that modal comes later — but the schema + count query are already
 * here so the UI can show "N songs" without a migration down the line.
 */

export type Playlist = {
  id: number;
  name: string;
  createdAt: number;
  songCount: number;
  coverSongId: string | null;
};

const DB_NAME = 'mellow.db';

let db: DB | null = null;
// Opened + migrated lazily on first use (never at import time) so a binary
// without the native module can't crash the app at startup — the caller's
// try/catch handles the rejection instead.
let readyPromise: Promise<void> | null = null;

function getDb(): DB {
  if (!db) {
    db = open({name: DB_NAME});
  }
  return db;
}

async function migrate(): Promise<void> {
  const d = getDb();
  await d.execute('PRAGMA foreign_keys = ON;');
  await d.execute(
    `CREATE TABLE IF NOT EXISTS playlists (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       name TEXT NOT NULL,
       created_at INTEGER NOT NULL
     );`,
  );
  await d.execute(
    `CREATE TABLE IF NOT EXISTS playlist_songs (
       playlist_id INTEGER NOT NULL,
       song_id TEXT NOT NULL,
       added_at INTEGER NOT NULL,
       PRIMARY KEY (playlist_id, song_id),
       FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
     );`,
  );
  const columns = await d.execute('PRAGMA table_info(playlists);');
  const hasCoverSong = (columns.rows ?? []).some(
    r => String(r.name) === 'cover_song_id',
  );
  if (!hasCoverSong) {
    await d.execute('ALTER TABLE playlists ADD COLUMN cover_song_id TEXT;');
  }
}

function ready(): Promise<void> {
  if (!readyPromise) {
    readyPromise = migrate();
  }
  return readyPromise;
}

/** All playlists, newest first, each with its current song count. */
export async function getPlaylists(): Promise<Playlist[]> {
  await ready();
  const res = await getDb().execute(
    `SELECT p.id        AS id,
            p.name      AS name,
            p.created_at AS createdAt,
            COALESCE(
              p.cover_song_id,
              (
                SELECT ps2.song_id
                  FROM playlist_songs ps2
                 WHERE ps2.playlist_id = p.id
                 ORDER BY ps2.added_at ASC
                 LIMIT 1
              )
            ) AS coverSongId,
            COUNT(ps.song_id) AS songCount
       FROM playlists p
       LEFT JOIN playlist_songs ps ON ps.playlist_id = p.id
      GROUP BY p.id
      ORDER BY p.created_at DESC;`,
  );

  return (res.rows ?? []).map(r => ({
    id: Number(r.id),
    name: String(r.name),
    createdAt: Number(r.createdAt),
    songCount: Number(r.songCount),
    coverSongId: r.coverSongId == null ? null : String(r.coverSongId),
  }));
}

/** Create a playlist with just a name. Returns the new row id. */
export async function createPlaylist(name: string): Promise<number> {
  await ready();
  const res = await getDb().execute(
    'INSERT INTO playlists (name, created_at) VALUES (?, ?);',
    [name.trim(), Date.now()],
  );
  return res.insertId ?? 0;
}

/** Rename a playlist in place. */
export async function renamePlaylist(id: number, name: string): Promise<void> {
  await ready();
  await getDb().execute('UPDATE playlists SET name = ? WHERE id = ?;', [
    name.trim(),
    id,
  ]);
}

/** Delete a playlist (its song rows cascade away via the FK). */
export async function deletePlaylist(id: number): Promise<void> {
  await ready();
  await getDb().execute('DELETE FROM playlists WHERE id = ?;', [id]);
}

/**
 * Add a song to a playlist. Idempotent — re-adding the same song is a no-op
 * (the composite PRIMARY KEY makes INSERT OR IGNORE swallow the duplicate).
 */
export async function addSongToPlaylist(
  playlistId: number,
  songId: string,
): Promise<void> {
  await ready();
  await getDb().execute(
    `INSERT OR IGNORE INTO playlist_songs (playlist_id, song_id, added_at)
     VALUES (?, ?, ?);`,
    [playlistId, songId, Date.now()],
  );
}

/** Remove a song from a playlist. */
export async function removeSongFromPlaylist(
  playlistId: number,
  songId: string,
): Promise<void> {
  await ready();
  await getDb().execute(
    'DELETE FROM playlist_songs WHERE playlist_id = ? AND song_id = ?;',
    [playlistId, songId],
  );
  await getDb().execute(
    `UPDATE playlists
        SET cover_song_id = NULL
      WHERE id = ? AND cover_song_id = ?;`,
    [playlistId, songId],
  );
}

/** Use an existing song in the playlist as the playlist cover source. */
export async function setPlaylistCoverSong(
  playlistId: number,
  songId: string,
): Promise<void> {
  await ready();
  await getDb().execute(
    `UPDATE playlists
        SET cover_song_id = ?
      WHERE id = ?
        AND EXISTS (
          SELECT 1 FROM playlist_songs
           WHERE playlist_id = ? AND song_id = ?
        );`,
    [songId, playlistId, playlistId, songId],
  );
}

/** The ids of every playlist that currently contains a given song. */
export async function getPlaylistIdsForSong(
  songId: string,
): Promise<number[]> {
  await ready();
  const res = await getDb().execute(
    'SELECT playlist_id FROM playlist_songs WHERE song_id = ?;',
    [songId],
  );
  return (res.rows ?? []).map(r => Number(r.playlist_id));
}

/** The MediaStore song ids in a playlist, in the order they were added. */
export async function getPlaylistSongIds(
  playlistId: number,
): Promise<string[]> {
  await ready();
  const res = await getDb().execute(
    `SELECT song_id FROM playlist_songs
      WHERE playlist_id = ?
      ORDER BY added_at ASC;`,
    [playlistId],
  );
  return (res.rows ?? []).map(r => String(r.song_id));
}
