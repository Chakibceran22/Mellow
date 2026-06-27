import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  Dimensions,
  FlatList,
  LayoutChangeEvent,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import {Text} from 'react-native-paper';
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedScrollHandler,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import {MagnifyingGlass, Play, Shuffle, X} from 'phosphor-react-native';
import SongRow from '../components/SongRow';
import PlayerSheet from '../components/PlayerSheet';
import AddToPlaylistSheet from '../components/AddToPlaylistSheet';
import PlaylistsPage from './PlaylistsPage';
import {ListSkeleton} from '../components/skeleton';
import {type LibrarySong} from '../data/mockSongs';
import {fetchLibrarySongs} from '../services/musicLibrary';
import {playFromList, playShuffled, setupPlayer} from '../player/setup';
import {useCurrentMediaItem} from '../player/useCurrentMediaItem';
import {palette} from '../theme/theme';

// Top navigation — each entry is a full, swipeable page. "Songs" and
// "Playlists" are built; the rest are placeholders for now.
const TABS = ['Songs', 'Playlists'] as const;

// Horizontal inset that pulls the underline in to sit under the tab's text
// (matches the tab's horizontal padding) instead of spanning the tap target.
const INDICATOR_INSET = 16;

type TabLayout = {x: number; width: number};

export default function LibraryScreen() {
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const [searchActive, setSearchActive] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [allSongs, setAllSongs] = useState<LibrarySong[]>([]);
  const [addSong, setAddSong] = useState<LibrarySong | null>(null);
  const [denied, setDenied] = useState(false);
  const [playlistsVersion, setPlaylistsVersion] = useState(0);

  // Measured page size — pages and the underline math are driven off this so it
  // stays correct across rotation / different screens.
  const [size, setSize] = useState(() => {
    const w = Dimensions.get('window');
    return {width: w.width, height: w.height};
  });
  const [tabLayouts, setTabLayouts] = useState<TabLayout[]>([]);
  const [tabBarWidth, setTabBarWidth] = useState(0);

  const searchOpen = useSharedValue(0);
  const scrollX = useSharedValue(0); // pager horizontal offset, in px
  const inputRef = useRef<TextInput>(null);
  const pagerRef = useRef<ScrollView>(null);
  const tabBarRef = useRef<ScrollView>(null);

  const active = useCurrentMediaItem();
  const activeId = active?.mediaId ?? null;

  // Boot the audio engine once when the screen mounts.
  useEffect(() => {
    setupPlayer();
  }, []);

  // Pull the on-device library from the MusicLibrary TurboModule. `initial`
  // shows the skeleton; `refresh` drives the pull-to-refresh spinner instead.
  const load = useCallback(async (mode: 'initial' | 'refresh') => {
    if (mode === 'refresh') {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      const {songs: result, reason} = await fetchLibrarySongs();
      setAllSongs(result);
      setDenied(reason === 'permission-denied');
    } finally {
      setRefreshing(false);
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load('initial');
  }, [load]);

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
      return allSongs;
    }
    return allSongs.filter(
      s =>
        s.title.toLowerCase().includes(q) ||
        s.artist.toLowerCase().includes(q) ||
        s.album.toLowerCase().includes(q),
    );
  }, [query, allSongs]);

  // Keep the active tab comfortably in view inside the (scrollable) tab bar.
  const ensureTabVisible = useCallback(
    (index: number) => {
      const l = tabLayouts[index];
      const barW = tabBarWidth || size.width;
      if (!l || barW <= 0) {
        return;
      }
      const target = l.x + l.width / 2 - barW / 2;
      tabBarRef.current?.scrollTo({x: Math.max(0, target), animated: true});
    },
    [tabLayouts, tabBarWidth, size.width],
  );

  const goToPage = useCallback(
    (index: number) => {
      setActiveIndex(index);
      pagerRef.current?.scrollTo({x: index * size.width, animated: true});
      ensureTabVisible(index);
    },
    [size.width, ensureTabVisible],
  );

  const openSearch = useCallback(() => {
    setSearchActive(true);
    goToPage(0); // searching filters the Songs page — make sure it's the one shown
  }, [goToPage]);

  const closeSearch = () => {
    setSearchActive(false);
    setQuery('');
    inputRef.current?.blur();
  };

  const onTabLayout = useCallback(
    (index: number) => (e: LayoutChangeEvent) => {
      const {x, width} = e.nativeEvent.layout;
      setTabLayouts(prev => {
        const cur = prev[index];
        if (cur && cur.x === x && cur.width === width) {
          return prev;
        }
        const next = prev.slice();
        next[index] = {x, width};
        return next;
      });
    },
    [],
  );

  const onPagerLayout = useCallback((e: LayoutChangeEvent) => {
    const {width, height} = e.nativeEvent.layout;
    setSize(prev =>
      prev.width === width && prev.height === height ? prev : {width, height},
    );
  }, []);

  const onPagerScroll = useAnimatedScrollHandler({
    onScroll: e => {
      scrollX.value = e.contentOffset.x;
    },
  });

  const onPageSettled = useCallback(
    (e: {nativeEvent: {contentOffset: {x: number}}}) => {
      if (size.width <= 0) {
        return;
      }
      const index = Math.round(e.nativeEvent.contentOffset.x / size.width);
      if (index !== activeIndex) {
        setActiveIndex(index);
        ensureTabVisible(index);
      }
    },
    [size.width, activeIndex, ensureTabVisible],
  );

  const renderItem = useCallback(
    ({item, index}: {item: LibrarySong; index: number}) => (
      <SongRow
        song={item}
        active={item.id === activeId}
        onPress={() => playFromList(songs, index)}
        onMorePress={() => setAddSong(item)}
        action="addToPlaylist"
      />
    ),
    [songs, activeId],
  );

  const refreshPlaylists = useCallback(() => {
    setPlaylistsVersion(v => v + 1);
  }, []);

  const renderSeparator = useCallback(
    () => <View style={styles.songSeparator} />,
    [],
  );

  // Play All / Shuffle — sits above the rows and scrolls with the list. Both act
  // on the songs currently shown (so they respect an active search filter too).
  const ListHeader = useCallback(
    () => (
      <View style={styles.actionsRow}>
        <Pressable
          onPress={() => playFromList(songs, 0)}
          android_ripple={{color: 'rgba(255,255,255,0.18)'}}
          style={({pressed}) => [
            styles.actionBtn,
            styles.playAllBtn,
            pressed && styles.actionPressed,
          ]}
        >
          <Play size={18} color="#FFFFFF" weight="fill" />
          <Text variant="labelLarge" style={styles.playAllLabel}>
            Play all
          </Text>
        </Pressable>
        <Pressable
          onPress={() => playShuffled(songs)}
          android_ripple={{color: palette.hairline}}
          style={({pressed}) => [
            styles.actionBtn,
            styles.shuffleBtn,
            pressed && styles.actionPressed,
          ]}
        >
          <Shuffle size={18} color={palette.deep} weight="bold" />
          <Text variant="labelLarge" style={styles.shuffleLabel}>
            Shuffle
          </Text>
        </Pressable>
      </View>
    ),
    [songs],
  );

  const tabsAnimStyle = useAnimatedStyle(() => ({
    opacity: interpolate(searchOpen.value, [0, 1], [1, 0], Extrapolation.CLAMP),
  }));
  const searchAnimStyle = useAnimatedStyle(() => ({
    opacity: searchOpen.value,
    transform: [
      {
        translateX: interpolate(
          searchOpen.value,
          [0, 1],
          [40, 0],
          Extrapolation.CLAMP,
        ),
      },
    ],
  }));

  // The sliding underline: tracks the pager so it glides + resizes between tabs
  // as you swipe, and lands centered under whichever tab you settle on.
  const indicatorStyle = useAnimatedStyle(() => {
    if (tabLayouts.length < TABS.length || size.width <= 0) {
      return {opacity: 0};
    }
    const progress = scrollX.value / size.width; // 0..(TABS.length-1)
    const input = TABS.map((_, i) => i);
    const xs = tabLayouts.map(l => l.x + INDICATOR_INSET);
    const widths = tabLayouts.map(l =>
      Math.max(0, l.width - INDICATOR_INSET * 2),
    );
    return {
      opacity: 1,
      width: interpolate(progress, input, widths, Extrapolation.CLAMP),
      transform: [
        {translateX: interpolate(progress, input, xs, Extrapolation.CLAMP)},
      ],
    };
  });

  const renderPage = (tab: (typeof TABS)[number]) => {
    if (tab === 'Songs') {
      return loading ? (
        <ListSkeleton count={10} />
      ) : (
        <FlatList
          data={songs}
          keyExtractor={s => s.id}
          renderItem={renderItem}
          ItemSeparatorComponent={renderSeparator}
          ListHeaderComponent={songs.length ? ListHeader : null}
          showsVerticalScrollIndicator={false}
          contentContainerStyle={styles.listPad}
          keyboardShouldPersistTaps="handled"
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={() => load('refresh')}
              tintColor={palette.deep}
              colors={[palette.deep]}
              progressBackgroundColor={palette.surface}
            />
          }
          ListEmptyComponent={
            <Text style={styles.empty}>
              {denied
                ? 'Allow music access to see your library, then pull down to refresh.'
                : query
                ? `No songs match “${query}”.`
                : 'No music found on this device.'}
            </Text>
          }
        />
      );
    }
    if (tab === 'Playlists') {
      return (
        <PlaylistsPage
          songs={allSongs}
          activeId={activeId}
          refreshKey={playlistsVersion}
        />
      );
    }
    return (
      <View style={styles.placeholder}>
        <Text variant="titleMedium" style={styles.placeholderTitle}>
          {tab}
        </Text>
        <Text variant="bodySmall" style={styles.placeholderSub}>
          Coming soon
        </Text>
      </View>
    );
  };

  return (
    <View style={styles.flex}>
      <View style={styles.header}>
        {/* Scrollable tab nav + sliding underline + search trigger */}
        <Animated.View
          style={[styles.tabsRow, tabsAnimStyle]}
          pointerEvents={searchActive ? 'none' : 'auto'}
        >
          <ScrollView
            ref={tabBarRef}
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.tabsContent}
            onLayout={e => setTabBarWidth(e.nativeEvent.layout.width)}
            keyboardShouldPersistTaps="handled"
          >
            {TABS.map((tab, i) => {
              const isActive = i === activeIndex;
              return (
                <Pressable
                  key={tab}
                  onLayout={onTabLayout(i)}
                  onPress={() => goToPage(i)}
                  style={styles.tab}
                >
                  <Text
                    variant="labelLarge"
                    style={[styles.tabText, isActive && styles.tabTextActive]}
                  >
                    {tab}
                  </Text>
                </Pressable>
              );
            })}
            <Animated.View style={[styles.indicator, indicatorStyle]} />
          </ScrollView>
          <Pressable
            onPress={openSearch}
            hitSlop={10}
            style={styles.searchIcon}
          >
            <MagnifyingGlass size={22} color={palette.ink} weight="bold" />
          </Pressable>
        </Animated.View>

        {/* Animated search bar (expands over the tabs) */}
        <Animated.View
          style={[styles.searchOverlay, searchAnimStyle]}
          pointerEvents={searchActive ? 'auto' : 'none'}
        >
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

      {/* Swipeable category pages */}
      <View style={styles.flex} onLayout={onPagerLayout}>
        <Animated.ScrollView
          ref={pagerRef}
          horizontal
          pagingEnabled
          directionalLockEnabled
          showsHorizontalScrollIndicator={false}
          scrollEventThrottle={16}
          onScroll={onPagerScroll}
          onMomentumScrollEnd={onPageSettled}
          keyboardShouldPersistTaps="handled"
        >
          {TABS.map(tab => (
            <View key={tab} style={{width: size.width, height: size.height}}>
              {renderPage(tab)}
            </View>
          ))}
        </Animated.ScrollView>
      </View>

      <PlayerSheet />
      <AddToPlaylistSheet
        visible={addSong !== null}
        song={addSong}
        songs={allSongs}
        onClose={() => setAddSong(null)}
        onChanged={refreshPlaylists}
      />
    </View>
  );
}

const HEADER_PAD_TOP = 8;
const ROW_H = 44;

const styles = StyleSheet.create({
  flex: {flex: 1},
  header: {
    paddingTop: HEADER_PAD_TOP,
    paddingHorizontal: 20,
    paddingBottom: 10,
  },

  // Tabs
  tabsRow: {flexDirection: 'row', alignItems: 'center', height: ROW_H},
  tabsContent: {alignItems: 'center', paddingRight: 8, gap: 8},
  tab: {paddingHorizontal: INDICATOR_INSET, paddingVertical: 8},
  tabText: {color: palette.inkSoft, fontWeight: '600'},
  tabTextActive: {color: palette.deep, fontWeight: '700'},
  // Sliding underline — same deep-green as the glassy selected state used to be.
  indicator: {
    position: 'absolute',
    left: 0,
    bottom: 2,
    height: 3,
    borderRadius: 2,
    backgroundColor: palette.deep,
  },
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

  // Play all / Shuffle
  actionsRow: {
    flexDirection: 'row',
    gap: 12,
    paddingHorizontal: 20,
    paddingTop: 6,
    paddingBottom: 14,
  },
  actionBtn: {
    flex: 1,
    height: 48,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    borderRadius: 14,
  },
  playAllBtn: {
    backgroundColor: palette.deep,
    shadowColor: palette.deep,
    shadowOpacity: 0.28,
    shadowRadius: 8,
    shadowOffset: {width: 0, height: 3},
    elevation: 3,
  },
  shuffleBtn: {
    backgroundColor: palette.surfaceAlt,
    borderWidth: 1,
    borderColor: palette.sage,
  },
  actionPressed: {opacity: 0.85},
  playAllLabel: {color: '#FFFFFF', fontWeight: '700'},
  shuffleLabel: {color: palette.deep, fontWeight: '700'},

  // Content
  listPad: {paddingTop: 6, paddingBottom: 92},
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
    marginTop: 40,
    paddingHorizontal: 20,
  },
  placeholder: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  placeholderTitle: {color: palette.ink, fontWeight: '700'},
  placeholderSub: {color: palette.inkSoft, marginTop: 4},
});
