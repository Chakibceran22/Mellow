import {useCallback, useEffect, useMemo, useState} from 'react';
import {
  FlatList,
  Pressable,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import {Text} from 'react-native-paper';
import {MagnifyingGlass, Play, Shuffle} from 'phosphor-react-native';
import {useActiveMediaItem} from '@rntp/player';
import SongRow from '../components/SongRow';
import PlayerSheet from '../components/PlayerSheet';
import {MOCK_SONGS, type LibrarySong} from '../data/mockSongs';
import {playFromList, setupPlayer} from '../player/setup';
import {palette} from '../theme/theme';

export default function LibraryScreen() {
  const [query, setQuery] = useState('');
  const active = useActiveMediaItem();
  const activeId = active?.mediaId ?? null;

  // Boot the audio engine once when the screen mounts (loads no songs yet —
  // tapping a row is what fills the queue, safely after the native controller
  // has connected).
  useEffect(() => {
    setupPlayer();
  }, []);

  const songs = useMemo<LibrarySong[]>(() => {
    const q = query.trim().toLowerCase();
    if (!q) {
      return MOCK_SONGS;
    }
    return MOCK_SONGS.filter(
      s =>
        s.title.toLowerCase().includes(q) ||
        s.artist.toLowerCase().includes(q) ||
        s.album.toLowerCase().includes(q),
    );
  }, [query]);

  const playAll = useCallback(() => {
    if (songs.length) {
      playFromList(songs, 0);
    }
  }, [songs]);

  const shuffle = useCallback(() => {
    if (!songs.length) {
      return;
    }
    const shuffled = [...songs].sort(() => Math.random() - 0.5);
    playFromList(shuffled, 0);
  }, [songs]);

  const renderItem = useCallback(
    ({item, index}: {item: LibrarySong; index: number}) => (
      <SongRow
        song={item}
        active={item.id === activeId}
        onPress={() => playFromList(songs, index)}
      />
    ),
    [songs, activeId],
  );

  return (
    <View style={styles.flex}>
      {/* Fixed header — kept out of the FlatList so the search field never loses focus. */}
      <View style={styles.header}>
        <View style={styles.brandRow}>
          <Text variant="labelLarge" style={styles.brand}>
            MELLOW
          </Text>
        </View>

        <Text variant="headlineMedium" style={styles.title}>
          Songs
        </Text>
        <Text variant="bodyMedium" style={styles.count}>
          {MOCK_SONGS.length} songs
        </Text>

        <View style={styles.search}>
          <MagnifyingGlass size={18} color={palette.inkSoft} />
          <TextInput
            value={query}
            onChangeText={setQuery}
            placeholder="Search songs, artists, albums"
            placeholderTextColor={palette.inkSoft}
            style={styles.searchInput}
            returnKeyType="search"
            autoCorrect={false}
          />
        </View>

        <View style={styles.actions}>
          <Pressable
            onPress={playAll}
            android_ripple={{color: '#ffffff30', borderless: false}}
            style={({pressed}) => [styles.playBtn, pressed && styles.pressed]}>
            <Play size={18} color="#FFFFFF" weight="fill" />
            <Text variant="labelLarge" style={styles.playLabel}>
              Play all
            </Text>
          </Pressable>

          <Pressable
            onPress={shuffle}
            android_ripple={{color: palette.hairline}}
            style={({pressed}) => [styles.shuffleBtn, pressed && styles.pressed]}>
            <Shuffle size={18} color={palette.deep} weight="bold" />
            <Text variant="labelLarge" style={styles.shuffleLabel}>
              Shuffle
            </Text>
          </Pressable>
        </View>
      </View>

      <FlatList
        data={songs}
        keyExtractor={s => s.id}
        renderItem={renderItem}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.listPad}
        keyboardShouldPersistTaps="handled"
        ListEmptyComponent={
          <Text style={styles.empty}>No songs match “{query}”.</Text>
        }
      />

      <PlayerSheet />
    </View>
  );
}

const styles = StyleSheet.create({
  flex: {flex: 1},
  header: {paddingTop: 8, paddingHorizontal: 20, paddingBottom: 12},
  brandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  brand: {color: palette.deep, letterSpacing: 3, fontWeight: '700'},
  title: {color: palette.ink, fontWeight: '700', marginTop: 14},
  count: {color: palette.inkSoft, marginTop: 2},
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    backgroundColor: palette.surfaceAlt,
    borderRadius: 14,
    paddingHorizontal: 14,
    marginTop: 16,
  },
  searchInput: {
    flex: 1,
    paddingVertical: 11,
    color: palette.ink,
    fontSize: 15,
  },
  actions: {flexDirection: 'row', gap: 12, marginTop: 16},
  playBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: palette.deep,
    paddingVertical: 10,
    paddingHorizontal: 22,
    borderRadius: 24,
  },
  playLabel: {color: '#FFFFFF', fontWeight: '700'},
  shuffleBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: palette.surfaceAlt,
    paddingVertical: 10,
    paddingHorizontal: 22,
    borderRadius: 24,
  },
  shuffleLabel: {color: palette.deep, fontWeight: '700'},
  pressed: {opacity: 0.8},
  listPad: {paddingTop: 6, paddingBottom: 92},
  empty: {
    textAlign: 'center',
    color: palette.inkSoft,
    marginTop: 40,
    paddingHorizontal: 20,
  },
});
