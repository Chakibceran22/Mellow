import {memo} from 'react';
import {StyleSheet, View} from 'react-native';
import ListItemSkeleton from './ListItemSkeleton';
import {palette} from '../../theme/theme';

type Props = {
  /** How many placeholder rows to render. */
  count?: number;
};

/**
 * Full loading state for a list screen — a stack of placeholder rows with the
 * same separators and padding as the real <FlatList>. Drop it in while songs
 * (or, later, playlists) are still loading.
 */
function ListSkeleton({count = 10}: Props) {
  return (
    <View style={styles.list}>
      {Array.from({length: count}, (_, i) => (
        <View key={i}>
          {i > 0 ? <View style={styles.separator} /> : null}
          <ListItemSkeleton index={i} />
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  // Matches LibraryScreen's listPad top + the song separator metrics.
  list: {paddingTop: 6},
  separator: {
    height: 1,
    marginLeft: 91,
    marginRight: 20,
    backgroundColor: palette.sage,
    opacity: 0.45,
  },
});

export default memo(ListSkeleton);
