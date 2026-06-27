import {useCallback, useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import {Text} from 'react-native-paper';
import {Check, Playlist as PlaylistIcon, Plus} from 'phosphor-react-native';
import BottomSheet from './BottomSheet';
import {
  addSongToPlaylist,
  createPlaylist,
  getPlaylistIdsForSong,
  getPlaylists,
  removeSongFromPlaylist,
  type Playlist,
} from '../db/playlists';
import {type LibrarySong} from '../data/mockSongs';
import {palette} from '../theme/theme';

type Props = {
  visible: boolean;
  song: LibrarySong | null;
  onClose: () => void;
  /** Called after any add/remove so the caller can refresh playlist counts. */
  onChanged?: () => void;
};

/**
 * "Add to playlist" sheet. Shows every playlist with a check toggle for the
 * current song (tap to add / remove, persisted to op-sqlite). The top row flips
 * the body into an inline "new playlist" input — no nested modal — and drops the
 * song into the freshly-created list.
 */
export default function AddToPlaylistSheet({
  visible,
  song,
  onClose,
  onChanged,
}: Props) {
  const [playlists, setPlaylists] = useState<Playlist[]>([]);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const inputRef = useRef<TextInput>(null);

  const load = useCallback(async () => {
    if (!song) {
      return;
    }
    setLoading(true);
    try {
      const [lists, ids] = await Promise.all([
        getPlaylists(),
        getPlaylistIdsForSong(song.id),
      ]);
      setPlaylists(lists);
      setSelected(new Set(ids));
    } catch (e) {
      console.warn('[addToPlaylist] load failed', e);
    } finally {
      setLoading(false);
    }
  }, [song]);

  // Refresh contents (and reset the create form) each time it opens.
  useEffect(() => {
    if (visible) {
      setCreating(false);
      setNewName('');
      load();
    }
  }, [visible, load]);

  // Keep the song title in the header while the sheet slides out (the parent
  // clears `song` the instant it closes).
  const [shownTitle, setShownTitle] = useState(song?.title ?? '');
  useEffect(() => {
    if (song) {
      setShownTitle(song.title);
    }
  }, [song]);

  const toggle = useCallback(
    async (playlist: Playlist) => {
      if (!song) {
        return;
      }
      const wasIn = selected.has(playlist.id);
      // Optimistic flip — revert if the write fails.
      setSelected(prev => {
        const next = new Set(prev);
        if (wasIn) {
          next.delete(playlist.id);
        } else {
          next.add(playlist.id);
        }
        return next;
      });
      try {
        if (wasIn) {
          await removeSongFromPlaylist(playlist.id, song.id);
        } else {
          await addSongToPlaylist(playlist.id, song.id);
        }
        onChanged?.();
      } catch (e) {
        console.warn('[addToPlaylist] toggle failed', e);
        setSelected(prev => {
          const next = new Set(prev);
          if (wasIn) {
            next.add(playlist.id);
          } else {
            next.delete(playlist.id);
          }
          return next;
        });
      }
    },
    [song, selected, onChanged],
  );

  const startCreate = useCallback(() => {
    setCreating(true);
    setNewName('');
    setTimeout(() => inputRef.current?.focus(), 120);
  }, []);

  const submitCreate = useCallback(async () => {
    const name = newName.trim();
    if (!name || !song) {
      return;
    }
    try {
      const id = await createPlaylist(name);
      await addSongToPlaylist(id, song.id);
      onChanged?.();
      setCreating(false);
      setNewName('');
      await load();
    } catch (e) {
      console.warn('[addToPlaylist] create failed', e);
    }
  }, [newName, song, load, onChanged]);

  const renderItem = useCallback(
    ({item}: {item: Playlist}) => {
      const isIn = selected.has(item.id);
      return (
        <Pressable
          onPress={() => toggle(item)}
          android_ripple={{color: palette.hairline}}
          style={({pressed}) => [styles.row, pressed && styles.pressed]}>
          <View style={styles.thumb}>
            <PlaylistIcon size={20} color={palette.deep} weight="fill" />
          </View>
          <View style={styles.meta}>
            <Text variant="titleSmall" numberOfLines={1} style={styles.name}>
              {item.name}
            </Text>
            <Text variant="bodySmall" numberOfLines={1} style={styles.sub}>
              {item.songCount} {item.songCount === 1 ? 'song' : 'songs'}
            </Text>
          </View>
          <View style={[styles.check, isIn && styles.checkOn]}>
            {isIn ? <Check size={15} color="#FFFFFF" weight="bold" /> : null}
          </View>
        </Pressable>
      );
    },
    [selected, toggle],
  );

  return (
    <BottomSheet visible={visible} onClose={onClose}>
      <View style={styles.header}>
        <View style={styles.headerText}>
          <Text variant="titleMedium" style={styles.title}>
            Add to playlist
          </Text>
          <Text variant="bodySmall" numberOfLines={1} style={styles.headerSub}>
            {shownTitle}
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
          <Pressable
            onPress={submitCreate}
            disabled={!newName.trim()}
            style={({pressed}) => [
              styles.createBtn,
              !newName.trim() && styles.createBtnDisabled,
              pressed && styles.pressed,
            ]}>
            <Text style={styles.createBtnLabel}>Create</Text>
          </Pressable>
        </View>
      ) : (
        <Pressable
          onPress={startCreate}
          android_ripple={{color: palette.hairline}}
          style={({pressed}) => [styles.row, pressed && styles.pressed]}>
          <View style={[styles.thumb, styles.thumbAccent]}>
            <Plus size={20} color="#FFFFFF" weight="bold" />
          </View>
          <Text variant="titleSmall" style={[styles.name, styles.newLabel]}>
            New playlist
          </Text>
        </Pressable>
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
  closeBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: palette.surfaceAlt,
    alignItems: 'center',
    justifyContent: 'center',
  },

  divider: {
    height: 1,
    backgroundColor: palette.hairline,
    marginTop: 4,
    marginBottom: 4,
  },

  list: {maxHeight: 360},
  loader: {paddingVertical: 28},

  // Plain list rows on the white sheet.
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    minHeight: 60,
    paddingVertical: 8,
    paddingHorizontal: 6,
    borderRadius: 12,
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
  check: {
    width: 24,
    height: 24,
    borderRadius: 12,
    borderWidth: 2,
    borderColor: palette.sage,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkOn: {backgroundColor: palette.deep, borderColor: palette.deep},

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
