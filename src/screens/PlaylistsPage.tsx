import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import {
  FlatList,
  Pressable,
  StyleSheet,
  View,
  type GestureResponderEvent,
} from 'react-native';
import {Text} from 'react-native-paper';
import {
  CaretLeft,
  DotsThreeVertical,
  PencilSimple,
  Play,
  Playlist as PlaylistIcon,
  Plus,
  Shuffle,
  Trash,
} from 'phosphor-react-native';
import BottomSheet from '../components/BottomSheet';
import ConfirmSheet from '../components/ConfirmSheet';
import LoadingModal from '../components/LoadingModal';
import CreatePlaylistModal from '../components/CreatePlaylistModal';
import RenameSheet from '../components/RenameSheet';
import SongCover from '../components/SongCover';
import SongRow from '../components/SongRow';
import {ListSkeleton} from '../components/skeleton';
import {
  createPlaylist,
  deletePlaylist,
  getPlaylistSongIds,
  getPlaylists,
  renamePlaylist,
  removeSongFromPlaylist,
  setPlaylistCoverSong,
  type Playlist,
} from '../db/playlists';
import {type LibrarySong} from '../data/mockSongs';
import {playFromList, playShuffled} from '../player/setup';
import {palette} from '../theme/theme';

type Props = {
  songs: LibrarySong[];
  activeId: string | null;
  refreshKey?: number;
};

type PlaylistIconButtonProps = {
  label: string;
  onPress: (event: GestureResponderEvent) => void;
  children: ReactNode;
};

type SongActionSheetProps = {
  visible: boolean;
  song: LibrarySong | null;
  onClose: () => void;
  onDismiss: () => void;
  onSetCover: (song: LibrarySong) => void;
  onRemove: (song: LibrarySong) => void;
};

type PlaylistActionSheetProps = {
  visible: boolean;
  playlist: Playlist | null;
  onClose: () => void;
  onDismiss: () => void;
  onRename: (playlist: Playlist) => void;
  onDelete: (playlist: Playlist) => void;
};

function PlaylistSeparator() {
  return <View style={styles.playlistSeparator} />;
}

function SongSeparator() {
  return <View style={styles.songSeparator} />;
}

function PlaylistIconButton({label, onPress, children}: PlaylistIconButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      hitSlop={8}
      onPress={onPress}
      android_ripple={{color: palette.hairline, borderless: true, radius: 18}}
      style={({pressed}) => [styles.iconBtn, pressed && styles.rowPressed]}
    >
      {children}
    </Pressable>
  );
}

function SheetActionRow({
  label,
  destructive,
  children,
  onPress,
}: PlaylistIconButtonProps & {destructive?: boolean}) {
  return (
    <View style={styles.sheetRowClip}>
      <Pressable
        accessibilityRole="button"
        onPress={onPress}
        android_ripple={{
          color: palette.hairline,
          borderless: false,
          foreground: true,
        }}
        style={({pressed}) => [styles.sheetRow, pressed && styles.rowPressed]}
      >
        <View style={styles.sheetIcon}>{children}</View>
        <Text
          variant="titleSmall"
          style={[styles.sheetLabel, destructive && styles.destructiveLabel]}
        >
          {label}
        </Text>
      </Pressable>
    </View>
  );
}

function PlaylistSongActionsSheet({
  visible,
  song,
  onClose,
  onDismiss,
  onSetCover,
  onRemove,
}: SongActionSheetProps) {
  const [shownSong, setShownSong] = useState(song);

  useEffect(() => {
    if (song) {
      setShownSong(song);
    }
  }, [song]);

  const currentSong = song ?? shownSong;

  const run = (action: (song: LibrarySong) => void) => {
    if (!currentSong) {
      return;
    }
    onClose();
    action(currentSong);
  };

  return (
    <BottomSheet visible={visible} onClose={onClose} onDismiss={onDismiss}>
      <View style={styles.sheetHeader}>
        <SongCover uri={currentSong?.artwork ?? ''} size={44} />
        <View style={styles.meta}>
          <Text variant="titleSmall" numberOfLines={1} style={styles.name}>
            {currentSong?.title ?? ''}
          </Text>
          <Text variant="bodySmall" numberOfLines={1} style={styles.sub}>
            {currentSong?.artist ?? ''}
          </Text>
        </View>
      </View>

      <SheetActionRow
        label="Set as playlist cover"
        onPress={() => run(onSetCover)}
      >
        <PlaylistIcon size={22} color={palette.deep} weight="fill" />
      </SheetActionRow>
      <SheetActionRow
        label="Remove from playlist"
        destructive
        onPress={() => run(onRemove)}
      >
        <Trash size={22} color={palette.clay} weight="bold" />
      </SheetActionRow>
    </BottomSheet>
  );
}

function PlaylistActionsSheet({
  visible,
  playlist,
  onClose,
  onDismiss,
  onRename,
  onDelete,
}: PlaylistActionSheetProps) {
  const [shownPlaylist, setShownPlaylist] = useState(playlist);

  useEffect(() => {
    if (playlist) {
      setShownPlaylist(playlist);
    }
  }, [playlist]);

  const currentPlaylist = playlist ?? shownPlaylist;

  return (
    <BottomSheet visible={visible} onClose={onClose} onDismiss={onDismiss}>
      <View style={styles.sheetTitleBlock}>
        <Text variant="titleMedium" numberOfLines={1} style={styles.sheetTitle}>
          {currentPlaylist?.name ?? ''}
        </Text>
        <Text variant="bodySmall" style={styles.sub}>
          {currentPlaylist?.songCount ?? 0}{' '}
          {currentPlaylist?.songCount === 1 ? 'song' : 'songs'}
        </Text>
      </View>

      <SheetActionRow
        label="Rename playlist"
        onPress={() => currentPlaylist && onRename(currentPlaylist)}
      >
        <PencilSimple size={22} color={palette.deep} weight="bold" />
      </SheetActionRow>
      <SheetActionRow
        label="Delete playlist"
        destructive
        onPress={() => currentPlaylist && onDelete(currentPlaylist)}
      >
        <Trash size={22} color={palette.clay} weight="bold" />
      </SheetActionRow>
    </BottomSheet>
  );
}

export default function PlaylistsPage({songs, activeId, refreshKey}: Props) {
  const [playlists, setPlaylists] = useState<Playlist[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [selectedPlaylist, setSelectedPlaylist] = useState<Playlist | null>(null);
  const [playlistSongIds, setPlaylistSongIds] = useState<string[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [actionSong, setActionSong] = useState<LibrarySong | null>(null);
  const [optionsPlaylist, setOptionsPlaylist] = useState<Playlist | null>(null);
  const [renameTarget, setRenameTarget] = useState<Playlist | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Playlist | null>(null);
  const afterOptionsDismissRef = useRef<(() => void) | null>(null);
  const afterSongDismissRef = useRef<(() => void) | null>(null);

  const songsById = useMemo(() => {
    const map = new Map<string, LibrarySong>();
    songs.forEach(song => map.set(song.id, song));
    return map;
  }, [songs]);

  const playlistSongs = useMemo(
    () =>
      playlistSongIds
        .map(id => songsById.get(id))
        .filter((song): song is LibrarySong => Boolean(song)),
    [playlistSongIds, songsById],
  );

  const coverSong = useMemo(() => {
    if (!selectedPlaylist) {
      return null;
    }
    return (
      (selectedPlaylist.coverSongId
        ? songsById.get(selectedPlaylist.coverSongId)
        : null) ??
      playlistSongs[0] ??
      null
    );
  }, [playlistSongs, selectedPlaylist, songsById]);

  const selectedPlaylistId = selectedPlaylist?.id ?? null;

  const load = useCallback(async () => {
    try {
      const next = await getPlaylists();
      setPlaylists(next);
      setSelectedPlaylist(prev =>
        prev ? next.find(playlist => playlist.id === prev.id) ?? null : prev,
      );
    } catch (e) {
      // Likely op-sqlite isn't in this binary yet (needs a rebuild) — don't crash.
      console.warn('[playlists] load failed', e);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadPlaylistSongs = useCallback(async (playlistId: number) => {
    setDetailLoading(true);
    try {
      setPlaylistSongIds(await getPlaylistSongIds(playlistId));
    } catch (e) {
      console.warn('[playlists] load songs failed', e);
      setPlaylistSongIds([]);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load, refreshKey]);

  useEffect(() => {
    if (selectedPlaylistId !== null) {
      loadPlaylistSongs(selectedPlaylistId);
    }
  }, [loadPlaylistSongs, refreshKey, selectedPlaylistId]);

  const onCreate = useCallback(
    async (name: string) => {
      setCreateOpen(false);
      setCreating(true);
      try {
        await createPlaylist(name);
        await load();
      } catch (e) {
        console.warn('[playlists] create failed', e);
      } finally {
        setCreating(false);
      }
    },
    [load],
  );

  const onPlaylistPress = useCallback(
    (playlist: Playlist) => {
      setSelectedPlaylist(playlist);
      setPlaylistSongIds([]);
      loadPlaylistSongs(playlist.id);
    },
    [loadPlaylistSongs],
  );

  const onMorePlaylist = useCallback((playlist: Playlist) => {
    setOptionsPlaylist(playlist);
  }, []);

  const closePlaylistOptions = useCallback(() => {
    setOptionsPlaylist(null);
  }, []);

  const afterPlaylistOptionsDismiss = useCallback(() => {
    const action = afterOptionsDismissRef.current;
    afterOptionsDismissRef.current = null;
    action?.();
  }, []);

  const afterSongActionsDismiss = useCallback(() => {
    const action = afterSongDismissRef.current;
    afterSongDismissRef.current = null;
    action?.();
  }, []);

  const openAfterOptions = useCallback((action: () => void) => {
    afterOptionsDismissRef.current = action;
    setOptionsPlaylist(null);
  }, []);

  const onRenamePlaylistOption = useCallback(
    (playlist: Playlist) => {
      openAfterOptions(() => setRenameTarget(playlist));
    },
    [openAfterOptions],
  );

  const onDeletePlaylistOption = useCallback(
    (playlist: Playlist) => {
      openAfterOptions(() => setDeleteTarget(playlist));
    },
    [openAfterOptions],
  );

  const applyPlaylistRename = useCallback(
    async (name: string) => {
      if (!renameTarget) {
        return;
      }
      const playlistId = renameTarget.id;
      setRenameTarget(null);
      try {
        await renamePlaylist(playlistId, name);
        setPlaylists(list =>
          list.map(playlist =>
            playlist.id === playlistId ? {...playlist, name} : playlist,
          ),
        );
        setSelectedPlaylist(prev =>
          prev && prev.id === playlistId ? {...prev, name} : prev,
        );
      } catch (e) {
        console.warn('[playlists] rename failed', e);
      }
    },
    [renameTarget],
  );

  const confirmDeletePlaylist = useCallback(async () => {
    if (!deleteTarget) {
      return;
    }
    const playlistId = deleteTarget.id;
    setDeleteTarget(null);
    try {
      await deletePlaylist(playlistId);
      setPlaylists(list => list.filter(playlist => playlist.id !== playlistId));
      if (selectedPlaylist?.id === playlistId) {
        setSelectedPlaylist(null);
        setPlaylistSongIds([]);
        setActionSong(null);
      }
    } catch (e) {
      console.warn('[playlists] delete failed', e);
    }
  }, [deleteTarget, selectedPlaylist]);

  const applySetCover = useCallback(
    async (song: LibrarySong) => {
      if (!selectedPlaylist) {
        return;
      }
      try {
        await setPlaylistCoverSong(selectedPlaylist.id, song.id);
        setSelectedPlaylist(prev =>
          prev && prev.id === selectedPlaylist.id
            ? {...prev, coverSongId: song.id}
            : prev,
        );
        setPlaylists(list =>
          list.map(playlist =>
            playlist.id === selectedPlaylist.id
              ? {...playlist, coverSongId: song.id}
              : playlist,
          ),
        );
      } catch (e) {
        console.warn('[playlists] set cover failed', e);
      }
    },
    [selectedPlaylist],
  );

  const applyRemoveSong = useCallback(
    async (song: LibrarySong) => {
      if (!selectedPlaylist) {
        return;
      }
      const playlistId = selectedPlaylist.id;
      const nextCoverSongId =
        selectedPlaylist.coverSongId === song.id ? null : selectedPlaylist.coverSongId;

      try {
        await removeSongFromPlaylist(playlistId, song.id);
        setPlaylistSongIds(ids => ids.filter(id => id !== song.id));
        setSelectedPlaylist(prev =>
          prev && prev.id === playlistId
            ? {
                ...prev,
                coverSongId: nextCoverSongId,
                songCount: Math.max(0, prev.songCount - 1),
              }
            : prev,
        );
        setPlaylists(list =>
          list.map(playlist =>
            playlist.id === playlistId
              ? {
                  ...playlist,
                  coverSongId: nextCoverSongId,
                  songCount: Math.max(0, playlist.songCount - 1),
                }
              : playlist,
          ),
        );
      } catch (e) {
        console.warn('[playlists] remove song failed', e);
      }
    },
    [selectedPlaylist],
  );

  const onSetCover = useCallback(
    (song: LibrarySong) => {
      afterSongDismissRef.current = () => {
        applySetCover(song).catch(e => {
          console.warn('[playlists] queued set cover failed', e);
        });
      };
    },
    [applySetCover],
  );

  const onRemoveSong = useCallback(
    (song: LibrarySong) => {
      afterSongDismissRef.current = () => {
        applyRemoveSong(song).catch(e => {
          console.warn('[playlists] queued remove song failed', e);
        });
      };
    },
    [applyRemoveSong],
  );

  const renderPlaylistItem = useCallback(
    ({item}: {item: Playlist}) => {
      const listCover = item.coverSongId ? songsById.get(item.coverSongId) : null;
      return (
        <Pressable
          onPress={() => onPlaylistPress(item)}
          android_ripple={{color: palette.hairline}}
          style={({pressed}) => [styles.row, pressed && styles.rowPressed]}
        >
          {listCover ? (
            <SongCover uri={listCover.artwork} size={THUMB} />
          ) : (
            <View style={styles.thumb}>
              <PlaylistIcon size={22} color={palette.deep} weight="fill" />
            </View>
          )}
          <View style={styles.meta}>
            <Text variant="titleMedium" style={styles.name} numberOfLines={1}>
              {item.name}
            </Text>
            <Text variant="bodySmall" style={styles.sub} numberOfLines={1}>
              {item.songCount} {item.songCount === 1 ? 'song' : 'songs'}
            </Text>
          </View>
          <PlaylistIconButton
            label={`More options for ${item.name}`}
            onPress={event => {
              event.stopPropagation();
              onMorePlaylist(item);
            }}
          >
            <DotsThreeVertical
              size={20}
              color={palette.inkSoft}
              weight="bold"
            />
          </PlaylistIconButton>
        </Pressable>
      );
    },
    [onMorePlaylist, onPlaylistPress, songsById],
  );

  const renderSongItem = useCallback(
    ({item, index}: {item: LibrarySong; index: number}) => (
      <SongRow
        song={item}
        active={item.id === activeId}
        onPress={() => playFromList(playlistSongs, index)}
        onMorePress={() => setActionSong(item)}
      />
    ),
    [activeId, playlistSongs],
  );

  const playSelectedPlaylist = useCallback(() => {
    if (playlistSongs.length > 0) {
      playFromList(playlistSongs, 0);
    }
  }, [playlistSongs]);

  const shuffleSelectedPlaylist = useCallback(() => {
    if (playlistSongs.length > 0) {
      playShuffled(playlistSongs);
    }
  }, [playlistSongs]);

  const playlistManagementSheets = (
    <>
      <PlaylistActionsSheet
        visible={optionsPlaylist !== null}
        playlist={optionsPlaylist}
        onClose={closePlaylistOptions}
        onDismiss={afterPlaylistOptionsDismiss}
        onRename={onRenamePlaylistOption}
        onDelete={onDeletePlaylistOption}
      />
      <RenameSheet
        visible={renameTarget !== null}
        title="Rename playlist"
        placeholder="Playlist name"
        initialValue={renameTarget?.name ?? ''}
        onSave={applyPlaylistRename}
        onClose={() => setRenameTarget(null)}
      />
      <ConfirmSheet
        visible={deleteTarget !== null}
        title="Delete playlist?"
        message={
          deleteTarget
            ? `"${deleteTarget.name}" and its saved song list will be removed.`
            : undefined
        }
        confirmLabel="Delete"
        destructive
        onConfirm={confirmDeletePlaylist}
        onClose={() => setDeleteTarget(null)}
      />
    </>
  );

  if (selectedPlaylist) {
    const detailCount = playlistSongIds.length;
    const canPlayPlaylist = playlistSongs.length > 0;
    const detailHeader = (
      <>
        <View style={styles.detailHeader}>
          {coverSong ? (
            <SongCover uri={coverSong.artwork} size={104} />
          ) : (
            <View style={styles.heroFallback}>
              <PlaylistIcon size={42} color={palette.deep} weight="fill" />
            </View>
          )}
          <View style={styles.detailBody}>
            <View style={styles.detailTitleRow}>
              <View style={styles.detailText}>
                <Text
                  variant="headlineSmall"
                  numberOfLines={2}
                  style={styles.detailName}
                >
                  {selectedPlaylist.name}
                </Text>
                <Text variant="bodySmall" style={styles.detailSub}>
                  {detailCount} {detailCount === 1 ? 'song' : 'songs'}
                </Text>
              </View>
              <View style={styles.detailControls}>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`Shuffle ${selectedPlaylist.name}`}
                  disabled={!canPlayPlaylist}
                  hitSlop={8}
                  onPress={shuffleSelectedPlaylist}
                  android_ripple={
                    canPlayPlaylist
                      ? {color: palette.hairline, borderless: true, radius: 22}
                      : undefined
                  }
                  style={({pressed}) => [
                    styles.detailControlBtn,
                    styles.shuffleControlBtn,
                    !canPlayPlaylist && styles.controlDisabled,
                    pressed && canPlayPlaylist && styles.rowPressed,
                  ]}
                >
                  <Shuffle size={22} color={palette.deep} weight="bold" />
                </Pressable>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`Play ${selectedPlaylist.name}`}
                  disabled={!canPlayPlaylist}
                  hitSlop={8}
                  onPress={playSelectedPlaylist}
                  android_ripple={
                    canPlayPlaylist
                      ? {
                          color: 'rgba(255,255,255,0.18)',
                          borderless: true,
                          radius: 24,
                        }
                      : undefined
                  }
                  style={({pressed}) => [
                    styles.detailControlBtn,
                    styles.playControlBtn,
                    !canPlayPlaylist && styles.controlDisabled,
                    pressed && canPlayPlaylist && styles.rowPressed,
                  ]}
                >
                  <Play size={22} color="#FFFFFF" weight="fill" />
                </Pressable>
              </View>
            </View>
          </View>
        </View>
        <View style={styles.detailDivider} />
      </>
    );

    return (
      <View style={styles.flex}>
        <View style={styles.detailTopBar}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Back to playlists"
            hitSlop={10}
            onPress={() => {
              setSelectedPlaylist(null);
              setActionSong(null);
            }}
            style={styles.backBtn}
          >
            <CaretLeft size={22} color={palette.ink} weight="bold" />
          </Pressable>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`More options for ${selectedPlaylist.name}`}
            hitSlop={10}
            onPress={() => onMorePlaylist(selectedPlaylist)}
            style={styles.backBtn}
          >
            <DotsThreeVertical size={22} color={palette.inkSoft} weight="bold" />
          </Pressable>
        </View>

        {detailLoading ? (
          <View style={styles.flex}>
            {detailHeader}
            <ListSkeleton count={7} />
          </View>
        ) : (
          <FlatList
            data={playlistSongs}
            keyExtractor={song => song.id}
            renderItem={renderSongItem}
            ItemSeparatorComponent={SongSeparator}
            ListHeaderComponent={detailHeader}
            contentContainerStyle={styles.detailListPad}
            showsVerticalScrollIndicator={false}
            ListEmptyComponent={
              <Text style={styles.empty}>
                {playlistSongIds.length > 0
                  ? 'Songs will appear after your music library loads.'
                  : 'No songs in this playlist yet.'}
              </Text>
            }
          />
        )}

        <PlaylistSongActionsSheet
          visible={actionSong !== null}
          song={actionSong}
          onClose={() => setActionSong(null)}
          onDismiss={afterSongActionsDismiss}
          onSetCover={onSetCover}
          onRemove={onRemoveSong}
        />
        {playlistManagementSheets}
      </View>
    );
  }

  return (
    <View style={styles.flex}>
      <View style={styles.topBar}>
        <Text style={styles.count}>
          {loading
            ? 'Playlists'
            : `${playlists.length} ${
                playlists.length === 1 ? 'playlist' : 'playlists'
              }`}
        </Text>
        <Pressable
          onPress={() => setCreateOpen(true)}
          hitSlop={10}
          android_ripple={{
            color: palette.hairline,
            borderless: true,
            radius: 22,
          }}
          style={styles.addBtn}
        >
          <Plus size={20} color={palette.deep} weight="bold" />
        </Pressable>
      </View>

      {loading ? (
        <ListSkeleton count={8} />
      ) : (
        <FlatList
          data={playlists}
          keyExtractor={p => String(p.id)}
          renderItem={renderPlaylistItem}
          ItemSeparatorComponent={PlaylistSeparator}
          contentContainerStyle={styles.listPad}
          showsVerticalScrollIndicator={false}
          ListEmptyComponent={
            <Text style={styles.empty}>
              No playlists yet. Tap + to create one.
            </Text>
          }
        />
      )}

      <CreatePlaylistModal
        visible={createOpen}
        onCancel={() => setCreateOpen(false)}
        onCreate={onCreate}
      />
      <LoadingModal visible={creating} label="Creating playlist…" />
      {playlistManagementSheets}
    </View>
  );
}

const THUMB = 48;

const styles = StyleSheet.create({
  flex: {flex: 1},

  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingTop: 6,
    paddingBottom: 10,
  },
  count: {color: palette.inkSoft, fontWeight: '600'},
  addBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: palette.surfaceAlt,
    alignItems: 'center',
    justifyContent: 'center',
  },

  listPad: {paddingBottom: 100},
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    paddingRight: 14,
    paddingLeft: 17,
    gap: 14,
    borderLeftWidth: 3,
    borderLeftColor: 'transparent',
  },
  rowPressed: {opacity: 0.7},
  thumb: {
    width: THUMB,
    height: THUMB,
    borderRadius: 12,
    backgroundColor: palette.tint,
    alignItems: 'center',
    justifyContent: 'center',
  },
  meta: {flex: 1, minWidth: 0},
  name: {color: palette.ink, fontWeight: '600'},
  sub: {color: palette.inkSoft, marginTop: 2},
  iconBtn: {
    width: 34,
    height: 34,
    alignItems: 'center',
    justifyContent: 'center',
  },
  playlistSeparator: {
    height: 1,
    marginLeft: 17 + 3 + THUMB + 14,
    marginRight: 20,
    backgroundColor: palette.sage,
    opacity: 0.45,
  },
  songSeparator: {
    height: 1,
    marginLeft: 91,
    marginRight: 20,
    backgroundColor: palette.sage,
    opacity: 0.45,
  },
  empty: {
    textAlign: 'center',
    color: palette.inkSoft,
    marginTop: 34,
    paddingHorizontal: 20,
  },

  detailTopBar: {
    height: 48,
    paddingHorizontal: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  backBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  detailListPad: {paddingBottom: 100},
  detailHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
    paddingHorizontal: 20,
    paddingTop: 8,
    paddingBottom: 20,
  },
  detailDivider: {
    height: 1,
    marginHorizontal: 20,
    marginBottom: 6,
    backgroundColor: palette.sage,
    opacity: 0.5,
  },
  heroFallback: {
    width: 104,
    height: 104,
    borderRadius: 25,
    backgroundColor: palette.tint,
    alignItems: 'center',
    justifyContent: 'center',
  },
  detailBody: {flex: 1, minWidth: 0},
  detailTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
  },
  detailText: {flex: 1, minWidth: 0},
  detailName: {color: palette.ink, fontWeight: '800'},
  detailSub: {color: palette.inkSoft, marginTop: 6, fontWeight: '600'},
  detailControls: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  detailControlBtn: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
  },
  shuffleControlBtn: {
    backgroundColor: palette.surfaceAlt,
  },
  playControlBtn: {
    backgroundColor: palette.deep,
  },
  controlDisabled: {opacity: 0.35},

  sheetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingTop: 2,
    paddingBottom: 8,
  },
  sheetTitleBlock: {
    paddingHorizontal: 4,
    paddingTop: 2,
    paddingBottom: 8,
  },
  sheetTitle: {color: palette.ink, fontWeight: '800'},
  sheetRowClip: {
    borderRadius: 14,
    overflow: 'hidden',
  },
  sheetRow: {
    minHeight: 54,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
    borderRadius: 14,
    paddingHorizontal: 6,
  },
  sheetIcon: {
    width: 26,
    alignItems: 'center',
    justifyContent: 'center',
  },
  sheetLabel: {color: palette.ink, fontWeight: '700'},
  destructiveLabel: {color: palette.clay},
});
