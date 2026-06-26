import {memo} from 'react';
import {StyleSheet, View} from 'react-native';
import SkeletonBone from './SkeletonBone';

// A few width variations so the placeholder list looks organic instead of a
// grid of identical bars. Picked by row index, cycling through the set.
const TITLE_WIDTHS = ['68%', '52%', '74%', '60%', '46%'] as const;
const SUB_WIDTHS = ['40%', '32%', '46%', '36%'] as const;

type Props = {
  /** Row position — used to vary bone widths and stagger the pulse. */
  index?: number;
};

/**
 * One placeholder row, laid out to mirror a real <SongRow> (cover · title +
 * subtitle · trailing meta). Generic on purpose: songs use it now, playlists
 * (same cover + two-line shape) will reuse it later.
 */
function ListItemSkeleton({index = 0}: Props) {
  const titleWidth = TITLE_WIDTHS[index % TITLE_WIDTHS.length];
  const subWidth = SUB_WIDTHS[index % SUB_WIDTHS.length];
  const delay = (index % 4) * 90;

  return (
    <View style={styles.row}>
      {/* Cover — matches SongCover (54px, ~0.24 radius). */}
      <SkeletonBone width={54} height={54} radius={13} delay={delay} />

      <View style={styles.meta}>
        <SkeletonBone width={titleWidth} height={14} delay={delay} />
        <SkeletonBone
          width={subWidth}
          height={11}
          delay={delay}
          style={styles.sub}
        />
      </View>

      {/* Trailing — stands in for the duration label. */}
      <SkeletonBone width={28} height={11} delay={delay} />
    </View>
  );
}

const styles = StyleSheet.create({
  // Mirrors SongRow's row metrics so content swaps in without a layout jump.
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
  meta: {flex: 1},
  sub: {marginTop: 8},
});

export default memo(ListItemSkeleton);
