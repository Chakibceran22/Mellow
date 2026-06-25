import {Image, StyleSheet, View} from 'react-native';
import Svg, {Defs, LinearGradient, Rect, Stop} from 'react-native-svg';
import {palette} from '../theme/theme';

type Props = {
  /** Album art URI — stretched + blurred to fill the parent. */
  uri: string;
  /** Unique gradient id (avoids SVG id collisions when two are mounted). */
  gradientId: string;
  blurRadius?: number;
};

/**
 * The glossy "matcha" backdrop shared by the player sheet and the full
 * now-playing page: the cover art blurred to fill, with a green→deep→dark
 * gradient over it for the cozy tint + text contrast. Fills its parent.
 */
export default function GlossyBackground({
  uri,
  gradientId,
  blurRadius = 36,
}: Props) {
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      {uri ? (
        <Image
          source={{uri}}
          style={StyleSheet.absoluteFill}
          blurRadius={blurRadius}
          resizeMode="cover"
        />
      ) : null}
      <Svg style={StyleSheet.absoluteFill} width="100%" height="100%">
        <Defs>
          <LinearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0" stopColor={palette.green} stopOpacity={0.5} />
            <Stop offset="0.55" stopColor={palette.deep} stopOpacity={0.6} />
            <Stop offset="1" stopColor="#1E2D28" stopOpacity={0.82} />
          </LinearGradient>
        </Defs>
        <Rect width="100%" height="100%" fill={`url(#${gradientId})`} />
      </Svg>
    </View>
  );
}
