import {Image, StyleSheet, View} from 'react-native';
import {palette} from '../theme/theme';

type Props = {
  /** Cover image URI. */
  uri: string;
  size?: number;
};

/**
 * Album cover image with rounded corners. The palette-tinted background shows
 * through while the remote image loads (and if it ever fails).
 */
export default function SongCover({uri, size = 54}: Props) {
  const radius = size * 0.24;
  return (
    <View
      style={[
        styles.wrap,
        {width: size, height: size, borderRadius: radius},
      ]}>
      {/* Only mount the Image for a real URI — an empty string warns ("source.uri
          should not be an empty string") and the tinted wrap is the placeholder. */}
      {uri ? (
        <Image
          source={{uri}}
          style={{width: size, height: size, borderRadius: radius}}
          resizeMode="cover"
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {backgroundColor: palette.surfaceAlt, overflow: 'hidden'},
});
