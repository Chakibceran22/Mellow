import {memo} from 'react';
import {Pressable, StyleSheet, View} from 'react-native';
import {Text} from 'react-native-paper';
import {DotsThreeVertical} from 'phosphor-react-native';
import SongCover from './SongCover';
import {palette} from '../theme/theme';
import {formatDuration, type LibrarySong} from '../data/mockSongs';

type Props = {
  song: LibrarySong;
  active: boolean;
  onPress: () => void;
};

function SongRow({song, active, onPress}: Props) {
  return (
    <Pressable
      onPress={onPress}
      android_ripple={{color: palette.hairline}}
      style={({pressed}) => [
        styles.row,
        active && styles.rowActive,
        pressed && styles.rowPressed,
      ]}>
      <SongCover uri={song.artwork} />

      <View style={styles.meta}>
        <Text
          variant="titleMedium"
          numberOfLines={1}
          style={[styles.title, {color: active ? palette.deep : palette.ink}]}>
          {song.title}
        </Text>
        <Text variant="bodySmall" numberOfLines={1} style={styles.sub}>
          {song.artist} · {song.album}
        </Text>
      </View>

      <View style={styles.trailing}>
        <Text variant="labelSmall" style={styles.duration}>
          {formatDuration(song.durationSec)}
        </Text>
        <DotsThreeVertical size={20} color={palette.inkSoft} weight="bold" />
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    paddingRight: 20,
    paddingLeft: 17,
    gap: 14,
    borderLeftWidth: 3,
    borderLeftColor: 'transparent',
  },
  rowActive: {backgroundColor: palette.tint, borderLeftColor: palette.deep},
  rowPressed: {opacity: 0.7},
  meta: {flex: 1},
  title: {fontWeight: '600'},
  sub: {color: palette.inkSoft, marginTop: 2},
  trailing: {
    minWidth: 66,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 8,
  },
  duration: {color: palette.inkSoft, fontWeight: '600'},
});

export default memo(SongRow);
