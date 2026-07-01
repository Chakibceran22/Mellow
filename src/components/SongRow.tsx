import {memo} from 'react';
import {Pressable, StyleSheet, View} from 'react-native';
import {Text} from 'react-native-paper';
import {Check, DotsThreeVertical, Plus} from 'phosphor-react-native';
import SongCover from './SongCover';
import {palette} from '../theme/theme';
import {formatDuration, type LibrarySong} from '../data/mockSongs';

type Props = {
  song: LibrarySong;
  active: boolean;
  onPress: () => void;
  onMorePress: () => void;
  action?: 'more' | 'addToPlaylist';
  /** Long-press the row — used to enter batch-selection mode. */
  onLongPress?: () => void;
  /** When true the row shows a selection checkbox instead of its trailing action. */
  selectionMode?: boolean;
  /** Whether this row is currently picked (only meaningful in selection mode). */
  selected?: boolean;
};

function SongRow({
  song,
  active,
  onPress,
  onMorePress,
  action = 'more',
  onLongPress,
  selectionMode = false,
  selected = false,
}: Props) {
  const addingToPlaylist = action === 'addToPlaylist';

  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      delayLongPress={250}
      android_ripple={{color: palette.hairline}}
      style={({pressed}) => [
        styles.row,
        active && styles.rowActive,
        selectionMode && selected && styles.rowSelected,
        pressed && styles.rowPressed,
      ]}
    >
      <SongCover uri={song.artwork} />

      <View style={styles.meta}>
        <Text
          variant="titleMedium"
          numberOfLines={1}
          style={[styles.title, {color: active ? palette.deep : palette.ink}]}
        >
          {song.title}
        </Text>
        <Text variant="bodySmall" numberOfLines={1} style={styles.sub}>
          {song.artist} · {song.album}
        </Text>
      </View>

      {selectionMode ? (
        <View style={styles.trailing}>
          <View style={[styles.check, selected && styles.checkOn]}>
            {selected ? <Check size={15} color="#FFFFFF" weight="bold" /> : null}
          </View>
        </View>
      ) : (
        <View style={styles.trailing}>
          <Text variant="labelSmall" style={styles.duration}>
            {formatDuration(song.durationSec)}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={
              addingToPlaylist
                ? `Add ${song.title} to playlist`
                : `More options for ${song.title}`
            }
            hitSlop={8}
            onPress={e => {
              e.stopPropagation();
              onMorePress();
            }}
            android_ripple={{
              color: palette.hairline,
              borderless: true,
              radius: 18,
            }}
            style={({pressed}) => [
              styles.moreBtn,
              pressed && styles.rowPressed,
            ]}
          >
            {addingToPlaylist ? (
              <Plus size={20} color={palette.inkSoft} weight="bold" />
            ) : (
              <DotsThreeVertical size={20} color={palette.inkSoft} weight="bold" />
            )}
          </Pressable>
        </View>
      )}
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
  rowSelected: {backgroundColor: palette.tint},
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
  moreBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
  },
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
});

export default memo(SongRow);
