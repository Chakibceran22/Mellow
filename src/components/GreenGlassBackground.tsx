import {StyleSheet, View} from 'react-native';
import Svg, {
  Defs,
  LinearGradient,
  RadialGradient,
  Rect,
  Stop,
} from 'react-native-svg';
import {palette} from '../theme/theme';

/**
 * Soft "matcha glass" backdrop for the player sheet — a layered green mesh
 * (base gradient + two radial glows) that reads as a natural, blurry green
 * panel. No album art. Fills its parent.
 */
export default function GreenGlassBackground() {
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      <Svg style={StyleSheet.absoluteFill} width="100%" height="100%">
        <Defs>
          <LinearGradient id="greenBase" x1="0" y1="0" x2="0.25" y2="1">
            <Stop offset="0" stopColor={palette.green} />
            <Stop offset="0.55" stopColor={palette.deep} />
            <Stop offset="1" stopColor="#4E776C" />
          </LinearGradient>
          {/* light sheen, top-right */}
          <RadialGradient id="greenSheen" cx="82%" cy="4%" r="78%">
            <Stop offset="0" stopColor={palette.sage} stopOpacity={0.5} />
            <Stop offset="1" stopColor={palette.sage} stopOpacity={0} />
          </RadialGradient>
          {/* soft shade, bottom-left */}
          <RadialGradient id="greenShade" cx="6%" cy="98%" r="82%">
            <Stop offset="0" stopColor="#33564C" stopOpacity={0.55} />
            <Stop offset="1" stopColor="#33564C" stopOpacity={0} />
          </RadialGradient>
        </Defs>
        <Rect width="100%" height="100%" fill="url(#greenBase)" />
        <Rect width="100%" height="100%" fill="url(#greenSheen)" />
        <Rect width="100%" height="100%" fill="url(#greenShade)" />
      </Svg>
    </View>
  );
}
