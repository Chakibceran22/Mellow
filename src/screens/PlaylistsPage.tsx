import {useCallback, useEffect, useState, type ReactNode} from 'react';
import {Alert, FlatList, Pressable, StyleSheet, View} from 'react-native';
import {Text} from 'react-native-paper';
import {
  DotsThreeVertical,
  Play,
  Playlist as PlaylistIcon,
  Plus,
  Shuffle,
} from 'phosphor-react-native';
import LoadingModal from '../components/LoadingModal';
import CreatePlaylistModal from '../components/CreatePlaylistModal';
import {ListSkeleton} from '../components/skeleton';
import {createPlaylist, getPlaylists, type Playlist} from '../db/playlists';
import {palette} from '../theme/theme';

type PlaylistActionButtonProps = {
  disabled?: boolean;
  label: string;
  onPress: () => void;
  children: ReactNode;
};

function PlaylistActionButton({
  disabled,
  label,
  onPress,
  children,
}: PlaylistActionButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      disabled={disabled}
      hitSlop={8}
      onPress={onPress}
      android_ripple={
        disabled
          ? undefined
          : {color: palette.hairline, borderless: true, radius: 18}
      }
      style={({pressed}) => [
        styles.iconBtn,
        disabled && styles.iconBtnDisabled,
        pressed && !disabled && styles.rowPressed,
      ]}
    >
      {children}
    </Pressable>
  );
}

export default function PlaylistsPage() {
  const [playlists, setPlaylists] = useState<Playlist[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);

  const load = useCallback(async () => {
    try {
      setPlaylists(await getPlaylists());
    } catch (e) {
      // Likely op-sqlite isn't in this binary yet (needs a rebuild) — don't crash.
      console.warn('[playlists] load failed', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

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

  const onPlaylistPress = useCallback((playlist: Playlist) => {
    Alert.alert(
      playlist.name,
      `${playlist.songCount} ${playlist.songCount === 1 ? 'song' : 'songs'}`,
    );
  }, []);

  const onPlayPlaylist = useCallback((playlist: Playlist) => {
    Alert.alert(playlist.name, 'Playlist playback is coming soon.');
  }, []);

  const onShufflePlaylist = useCallback((playlist: Playlist) => {
    Alert.alert(playlist.name, 'Playlist shuffle is coming soon.');
  }, []);

  const onMorePlaylist = useCallback((playlist: Playlist) => {
    Alert.alert(playlist.name, 'Playlist options are coming soon.');
  }, []);

  const renderItem = useCallback(
    ({item}: {item: Playlist}) => (
      <Pressable
        onPress={() => onPlaylistPress(item)}
        android_ripple={{color: palette.hairline}}
        style={({pressed}) => [styles.row, pressed && styles.rowPressed]}
      >
        <View style={styles.thumb}>
          <PlaylistIcon size={22} color={palette.deep} weight="fill" />
        </View>
        <View style={styles.meta}>
          <Text variant="titleMedium" style={styles.name} numberOfLines={1}>
            {item.name}
          </Text>
          <Text variant="bodySmall" style={styles.sub} numberOfLines={1}>
            {item.songCount} {item.songCount === 1 ? 'song' : 'songs'}
          </Text>
        </View>
        <View style={styles.actions}>
          <PlaylistActionButton
            label={`Play ${item.name}`}
            disabled={item.songCount === 0}
            onPress={() => onPlayPlaylist(item)}
          >
            <Play
              size={17}
              color={item.songCount === 0 ? palette.inkSoft : palette.deep}
              weight="fill"
            />
          </PlaylistActionButton>
          <PlaylistActionButton
            label={`Shuffle ${item.name}`}
            disabled={item.songCount === 0}
            onPress={() => onShufflePlaylist(item)}
          >
            <Shuffle
              size={17}
              color={item.songCount === 0 ? palette.inkSoft : palette.deep}
              weight="bold"
            />
          </PlaylistActionButton>
          <PlaylistActionButton
            label={`More options for ${item.name}`}
            onPress={() => onMorePlaylist(item)}
          >
            <DotsThreeVertical
              size={20}
              color={palette.inkSoft}
              weight="bold"
            />
          </PlaylistActionButton>
        </View>
      </Pressable>
    ),
    [onMorePlaylist, onPlayPlaylist, onPlaylistPress, onShufflePlaylist],
  );

  return (
    <View style={styles.flex}>
      {/* Minimal top bar: count on the left, + on the right */}
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
          renderItem={renderItem}
          ItemSeparatorComponent={() => <View style={styles.separator} />}
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
  actions: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 6,
  },
  iconBtn: {
    width: 34,
    height: 34,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconBtnDisabled: {opacity: 0.42},
  separator: {
    height: 1,
    marginLeft: 17 + 3 + THUMB + 14,
    marginRight: 20,
    backgroundColor: palette.sage,
    opacity: 0.45,
  },
  empty: {
    textAlign: 'center',
    color: palette.inkSoft,
    marginTop: 40,
    paddingHorizontal: 20,
  },
});
