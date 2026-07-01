import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import {Text} from 'react-native-paper';
import {CaretRight, Playlist as PlaylistIcon, Plus} from 'phosphor-react-native';
import BottomSheet from './BottomSheet';
import SongCover from './SongCover';
import {
  addSongsToPlaylist,
  createPlaylist,
  getPlaylists,
  type Playlist,
} from '../db/playlists';
import {type LibrarySong} from '../data/mockSongs';
import {playlistCoverUri} from '../data/playlistCover';
import {palette} from '../theme/theme';

type Props = {
  visible: boolean;
  /** MediaStore ids of the songs to drop into the chosen playlist. */
  songIds: string[];
  /** Full library — used only to resolve playlist cover art. */
  songs: LibrarySong[];
  onClose: () => void;
  /** Called after the songs are added (new or existing playlist). */
  onAdded?: () => void;
};

/**
 * Batch "add to playlist" sheet. Unlike the single-song sheet this doesn't
 * toggle membership per playlist — tapping a playlist drops the whole selection
 * into it (idempotent) and closes. The top row creates a new playlist inline and
 * seeds it with the selection.
 */
export default function AddSongsToPlaylistSheet({
  visible,
  songIds,
  songs,
  onClose,
  onAdded,
}: Props) {
  const [playlists, setPlaylists] = useState<Playlist[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [busy, setBusy] = useState(false);
  const inputRef = useRef<TextInput>(null);

  const songsById = useMemo(() => {
    const map = new Map<string, LibrarySong>();
    songs.forEach(s => map.set(s.id, s));
    return map;
  }, [songs]);

  // Hold the count steady while the sheet slides out (the parent may clear the
  // selection the instant it closes).
  const [shownCount, setShownCount] = useState(songIds.length);
  useEffect(() => {
    if (songIds.length > 0) {
      setShownCount(songIds.length);
    }
  }, [songIds.length]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setPlaylists(await getPlaylists());
    } catch (e) {
      console.warn('[addSongs] load failed', e);
    } finally {
      setLoading(false);
    }
  }, []);

  // Refresh contents (and reset the create form) each time it opens.
  useEffect(() => {
    if (visible) {
      setCreating(false);
      setNewName('');
      setBusy(false);
      load();
    }
  }, [visible, load]);

  const addToExisting = useCallback(
    async (playlist: Playlist) => {
      if (busy || songIds.length === 0) {
        return;
      }
      setBusy(true);
      try {
        await addSongsToPlaylist(playlist.id, songIds);
        onAdded?.();
        onClose();
      } catch (e) {
        console.warn('[addSongs] add failed', e);
        setBusy(false);
      }
    },
    [busy, songIds, onAdded, onClose],
  );

  const startCreate = useCallback(() => {
    setCreating(true);
    setNewName('');
    setTimeout(() => inputRef.current?.focus(), 120);
  }, []);

  const submitCreate = useCallback(async () => {
    const name = newName.trim();
    if (!name || busy || songIds.length === 0) {
      return;
    }
    setBusy(true);
    try {
      const id = await createPlaylist(name);
      await addSongsToPlaylist(id, songIds);
      onAdded?.();
      onClose();
    } catch (e) {
      console.warn('[addSongs] create failed', e);
      setBusy(false);
    }
  }, [newName, busy, songIds, onAdded, onClose]);

  const renderItem = useCallback(
    ({item}: {item: Playlist}) => {
      const coverUri = playlistCoverUri(item, songsById);
      return (
        <View style={styles.rowClip}>
          <Pressable
            onPress={() => addToExisting(item)}
            android_ripple={{
              color: palette.hairline,
              borderless: false,
              foreground: true,
            }}
            style={({pressed}) => [styles.row, pressed && styles.pressed]}>
            {coverUri ? (
              <SongCover uri={coverUri} size={THUMB} />
            ) : (
              <View style={styles.thumb}>
                <PlaylistIcon size={20} color={palette.deep} weight="fill" />
              </View>
            )}
            <View style={styles.meta}>
              <Text variant="titleSmall" numberOfLines={1} style={styles.name}>
                {item.name}
              </Text>
              <Text variant="bodySmall" numberOfLines={1} style={styles.sub}>
                {item.songCount} {item.songCount === 1 ? 'song' : 'songs'}
              </Text>
            </View>
            <CaretRight size={18} color={palette.inkSoft} weight="bold" />
          </Pressable>
        </View>
      );
    },
    [songsById, addToExisting],
  );

  return (
    <BottomSheet visible={visible} onClose={onClose} dismissable={!busy}>
      <View style={styles.header}>
        <View style={styles.headerText}>
          <Text variant="titleMedium" style={styles.title}>
            Add to playlist
          </Text>
          <Text variant="bodySmall" numberOfLines={1} style={styles.headerSub}>
            {shownCount} {shownCount === 1 ? 'song' : 'songs'} selected
          </Text>
        </View>
      </View>

      {creating ? (
        <View style={styles.createRow}>
          <View style={styles.thumb}>
            <PlaylistIcon size={20} color={palette.deep} weight="fill" />
          </View>
          <TextInput
            ref={inputRef}
            value={newName}
            onChangeText={setNewName}
            placeholder="Playlist name"
            placeholderTextColor={palette.inkSoft}
            style={styles.input}
            returnKeyType="done"
            onSubmitEditing={submitCreate}
            maxLength={60}
            autoCorrect={false}
          />
          <View style={styles.createBtnClip}>
            <Pressable
              onPress={submitCreate}
              disabled={!newName.trim() || busy}
              android_ripple={
                newName.trim()
                  ? {
                      color: 'rgba(255,255,255,0.18)',
                      borderless: false,
                      foreground: true,
                    }
                  : undefined
              }
              style={({pressed}) => [
                styles.createBtn,
                (!newName.trim() || busy) && styles.createBtnDisabled,
                pressed && styles.pressed,
              ]}>
              <Text style={styles.createBtnLabel}>Create</Text>
            </Pressable>
          </View>
        </View>
      ) : (
        <View style={styles.rowClip}>
          <Pressable
            onPress={startCreate}
            android_ripple={{
              color: palette.hairline,
              borderless: false,
              foreground: true,
            }}
            style={({pressed}) => [styles.row, pressed && styles.pressed]}>
            <View style={[styles.thumb, styles.thumbAccent]}>
              <Plus size={20} color="#FFFFFF" weight="bold" />
            </View>
            <Text variant="titleSmall" style={[styles.name, styles.newLabel]}>
              New playlist
            </Text>
          </Pressable>
        </View>
      )}

      <View style={styles.divider} />

      {loading ? (
        <ActivityIndicator
          color={palette.deep}
          style={styles.loader}
          size="small"
        />
      ) : (
        <FlatList
          data={playlists}
          keyExtractor={p => String(p.id)}
          renderItem={renderItem}
          style={styles.list}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
          ListEmptyComponent={
            <Text style={styles.empty}>
              No playlists yet — tap “New playlist” to make one.
            </Text>
          }
        />
      )}
    </BottomSheet>
  );
}

const THUMB = 42;

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingBottom: 10,
    paddingHorizontal: 4,
  },
  headerText: {flex: 1, minWidth: 0},
  title: {color: palette.ink, fontWeight: '700'},
  headerSub: {color: palette.inkSoft, marginTop: 2},

  divider: {
    height: 1,
    backgroundColor: palette.hairline,
    marginTop: 4,
    marginBottom: 4,
  },

  list: {maxHeight: 360},
  loader: {paddingVertical: 28},

  rowClip: {
    borderRadius: 14,
    overflow: 'hidden',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    minHeight: 60,
    paddingVertical: 8,
    paddingHorizontal: 6,
    borderRadius: 14,
  },
  pressed: {opacity: 0.6},
  thumb: {
    width: THUMB,
    height: THUMB,
    borderRadius: 12,
    backgroundColor: palette.tint,
    alignItems: 'center',
    justifyContent: 'center',
  },
  thumbAccent: {backgroundColor: palette.deep},
  meta: {flex: 1, minWidth: 0},
  name: {color: palette.ink, fontWeight: '600'},
  newLabel: {flex: 1},
  sub: {color: palette.inkSoft, marginTop: 2},

  createRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 8,
  },
  input: {
    flex: 1,
    backgroundColor: palette.surfaceAlt,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 10,
    color: palette.ink,
    fontSize: 15,
  },
  createBtnClip: {
    borderRadius: 12,
    overflow: 'hidden',
  },
  createBtn: {
    paddingHorizontal: 16,
    height: 42,
    borderRadius: 12,
    backgroundColor: palette.deep,
    alignItems: 'center',
    justifyContent: 'center',
  },
  createBtnDisabled: {opacity: 0.4},
  createBtnLabel: {color: '#FFFFFF', fontWeight: '700'},

  empty: {
    textAlign: 'center',
    color: palette.inkSoft,
    paddingVertical: 24,
    paddingHorizontal: 12,
  },
});
