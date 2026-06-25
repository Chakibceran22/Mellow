import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import {Text} from 'react-native-paper';
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import {MagnifyingGlass, X} from 'phosphor-react-native';
import {useActiveMediaItem} from '@rntp/player';
import SongRow from '../components/SongRow';
import PlayerSheet from '../components/PlayerSheet';
import {MOCK_SONGS, type LibrarySong} from '../data/mockSongs';
import {playFromList, setupPlayer} from '../player/setup';
import {palette} from '../theme/theme';

// Top navigation — horizontally scrollable. Only "Songs" is built for now.
const TABS = ['Songs', 'Albums', 'Artists', 'Playlists', 'Favorites'] as const;

export default function LibraryScreen() {
  const [query, setQuery] = useState('');
  const [activeTab, setActiveTab] = useState<(typeof TABS)[number]>('Songs');
  const [searchActive, setSearchActive] = useState(false);
  const searchOpen = useSharedValue(0);
  const inputRef = useRef<TextInput>(null);

  const active = useActiveMediaItem();
  const activeId = active?.mediaId ?? null;

  // Boot the audio engine once when the screen mounts.
  useEffect(() => {
    setupPlayer();
  }, []);

  // Drive the open/close animation and focus the field when it opens.
  useEffect(() => {
    searchOpen.value = withTiming(searchActive ? 1 : 0, {duration: 220});
    if (searchActive) {
      const t = setTimeout(() => inputRef.current?.focus(), 80);
      return () => clearTimeout(t);
    }
  }, [searchActive, searchOpen]);

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

  const closeSearch = () => {
    setSearchActive(false);
    setQuery('');
    inputRef.current?.blur();
  };

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

  const tabsAnimStyle = useAnimatedStyle(() => ({
    opacity: interpolate(searchOpen.value, [0, 1], [1, 0], Extrapolation.CLAMP),
  }));
  const searchAnimStyle = useAnimatedStyle(() => ({
    opacity: searchOpen.value,
    transform: [
      {translateX: interpolate(searchOpen.value, [0, 1], [40, 0], Extrapolation.CLAMP)},
    ],
  }));

  return (
    <View style={styles.flex}>
      <View style={styles.header}>
        {/* Scrollable tab nav + search trigger */}
        <Animated.View
          style={[styles.tabsRow, tabsAnimStyle]}
          pointerEvents={searchActive ? 'none' : 'auto'}>
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.tabsContent}
            keyboardShouldPersistTaps="handled">
            {TABS.map(tab => {
              const isActive = tab === activeTab;
              return (
                <Pressable
                  key={tab}
                  onPress={() => setActiveTab(tab)}
                  style={[styles.tab, isActive && styles.tabActive]}>
                  <Text
                    variant="labelLarge"
                    style={[styles.tabText, isActive && styles.tabTextActive]}>
                    {tab}
                  </Text>
                </Pressable>
              );
            })}
          </ScrollView>
          <Pressable onPress={() => setSearchActive(true)} hitSlop={10} style={styles.searchIcon}>
            <MagnifyingGlass size={22} color={palette.ink} weight="bold" />
          </Pressable>
        </Animated.View>

        {/* Animated search bar (expands over the tabs) */}
        <Animated.View
          style={[styles.searchOverlay, searchAnimStyle]}
          pointerEvents={searchActive ? 'auto' : 'none'}>
          <MagnifyingGlass size={18} color={palette.inkSoft} />
          <TextInput
            ref={inputRef}
            value={query}
            onChangeText={setQuery}
            placeholder="Search songs, artists, albums"
            placeholderTextColor={palette.inkSoft}
            style={styles.searchInput}
            returnKeyType="search"
            autoCorrect={false}
          />
          <Pressable onPress={closeSearch} hitSlop={10}>
            <X size={18} color={palette.inkSoft} weight="bold" />
          </Pressable>
        </Animated.View>
      </View>

      {activeTab === 'Songs' ? (
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
      ) : (
        <View style={styles.placeholder}>
          <Text variant="titleMedium" style={styles.placeholderTitle}>
            {activeTab}
          </Text>
          <Text variant="bodySmall" style={styles.placeholderSub}>
            Coming soon
          </Text>
        </View>
      )}

      <PlayerSheet />
    </View>
  );
}

const HEADER_PAD_TOP = 8;
const ROW_H = 44;

const styles = StyleSheet.create({
  flex: {flex: 1},
  header: {paddingTop: HEADER_PAD_TOP, paddingHorizontal: 20, paddingBottom: 10},

  // Tabs
  tabsRow: {flexDirection: 'row', alignItems: 'center', height: ROW_H},
  tabsContent: {alignItems: 'center', paddingRight: 8, gap: 8},
  tab: {paddingHorizontal: 16, paddingVertical: 8, borderRadius: 20},
  tabActive: {backgroundColor: palette.deep},
  tabText: {color: palette.inkSoft, fontWeight: '600'},
  tabTextActive: {color: '#FFFFFF'},
  searchIcon: {paddingLeft: 12, paddingVertical: 4},

  // Animated search bar
  searchOverlay: {
    position: 'absolute',
    left: 20,
    right: 20,
    top: HEADER_PAD_TOP,
    height: ROW_H,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    backgroundColor: palette.surfaceAlt,
    borderRadius: 14,
    paddingHorizontal: 14,
  },
  searchInput: {flex: 1, paddingVertical: 0, color: palette.ink, fontSize: 15},

  // Content
  listPad: {paddingTop: 6, paddingBottom: 92},
  empty: {
    textAlign: 'center',
    color: palette.inkSoft,
    marginTop: 40,
    paddingHorizontal: 20,
  },
  placeholder: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  placeholderTitle: {color: palette.ink, fontWeight: '700'},
  placeholderSub: {color: palette.inkSoft, marginTop: 4},
});
