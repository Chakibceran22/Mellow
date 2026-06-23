import type {ReactNode} from 'react';
import {useEffect} from 'react';
import {FlatList, Pressable, StyleSheet, View} from 'react-native';
import {Divider, List, ProgressBar, Surface, Text, useTheme} from 'react-native-paper';
import {MusicNotes, Pause, Play, SkipBack, SkipForward} from 'phosphor-react-native';
import TrackPlayer, {
  useActiveMediaItem,
  useIsPlaying,
  useProgress,
} from '@rntp/player';
import {playFromList, setupPlayer, type Song} from '../player/setup';
import {TEST_SONGS} from '../data/testSongs';

function IconBtn({children, onPress}: {children: ReactNode; onPress: () => void}) {
  return (
    <Pressable
      onPress={onPress}
      hitSlop={12}
      style={({pressed}) => ({opacity: pressed ? 0.5 : 1})}>
      {children}
    </Pressable>
  );
}

function NowPlayingBar() {
  const theme = useTheme();
  const active = useActiveMediaItem();
  const playing = useIsPlaying();
  const {position, duration} = useProgress();

  if (!active) {
    return null;
  }

  const progress = duration > 0 ? position / duration : 0;

  return (
    <Surface style={styles.bar} elevation={3}>
      <ProgressBar progress={progress} style={styles.progress} />
      <View style={styles.barRow}>
        <View style={styles.barInfo}>
          <Text variant="titleMedium" numberOfLines={1}>
            {active.title ?? 'Unknown'}
          </Text>
          <Text variant="bodySmall" numberOfLines={1} style={styles.dim}>
            {active.artist ?? ''}
          </Text>
        </View>

        <View style={styles.controls}>
          <IconBtn onPress={() => TrackPlayer.skipToPrevious()}>
            <SkipBack size={28} weight="fill" color={theme.colors.onSurface} />
          </IconBtn>
          {playing ? (
            <IconBtn onPress={() => TrackPlayer.pause()}>
              <Pause size={36} weight="fill" color={theme.colors.primary} />
            </IconBtn>
          ) : (
            <IconBtn onPress={() => TrackPlayer.play()}>
              <Play size={36} weight="fill" color={theme.colors.primary} />
            </IconBtn>
          )}
          <IconBtn onPress={() => TrackPlayer.skipToNext()}>
            <SkipForward size={28} weight="fill" color={theme.colors.onSurface} />
          </IconBtn>
        </View>
      </View>
    </Surface>
  );
}

export default function TestPlayerScreen() {
  const theme = useTheme();
  const active = useActiveMediaItem();

  // Boot the engine when this screen mounts (no songs loaded here).
  useEffect(() => {
    setupPlayer();
  }, []);

  const renderItem = ({item, index}: {item: Song; index: number}) => {
    const isActive = active?.mediaId === item.id;
    return (
      <List.Item
        title={item.title}
        description={item.artist}
        titleStyle={isActive ? {color: theme.colors.primary} : undefined}
        onPress={() => playFromList(TEST_SONGS, index)}
        left={props => (
          <View {...props} style={styles.leftIcon}>
            <MusicNotes
              size={24}
              weight={isActive ? 'fill' : 'regular'}
              color={isActive ? theme.colors.primary : theme.colors.onSurfaceVariant}
            />
          </View>
        )}
      />
    );
  };

  return (
    <View style={styles.flex}>
      <Text variant="headlineMedium" style={styles.header}>
        Mellow — Test
      </Text>
      <FlatList
        data={TEST_SONGS}
        keyExtractor={s => s.id}
        renderItem={renderItem}
        ItemSeparatorComponent={Divider}
        contentContainerStyle={styles.listPad}
      />
      <NowPlayingBar />
    </View>
  );
}

const styles = StyleSheet.create({
  flex: {flex: 1},
  header: {paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8},
  listPad: {paddingBottom: 24},
  leftIcon: {justifyContent: 'center', paddingLeft: 16},
  dim: {opacity: 0.7},
  bar: {paddingBottom: 12},
  progress: {height: 3},
  barRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingTop: 10,
    gap: 12,
  },
  barInfo: {flex: 1},
  controls: {flexDirection: 'row', alignItems: 'center', gap: 18},
});
