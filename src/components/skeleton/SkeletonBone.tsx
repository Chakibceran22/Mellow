import {memo, useEffect} from 'react';
import type {DimensionValue, StyleProp, ViewStyle} from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useSharedValue,
  withDelay,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';
import {palette} from '../../theme/theme';

const MIN_OPACITY = 0.45;
const MAX_OPACITY = 0.85;

type Props = {
  /** Bone width — a number (px) or percentage string like '60%'. */
  width: DimensionValue;
  /** Bone height in px. */
  height: number;
  /** Corner radius. Defaults to a soft 6. */
  radius?: number;
  /** Stagger the pulse so rows of bones breathe in a gentle wave. */
  delay?: number;
  style?: StyleProp<ViewStyle>;
};

/**
 * The single building block of every skeleton — one rounded placeholder that
 * gently "breathes" (a calm opacity pulse, in keeping with the Mellow feel).
 * Driven by reanimated (already used across the app) so it stays on the UI
 * thread and pulls in no extra animation deps. Compose these to mirror any
 * real piece of content.
 */
function SkeletonBone({width, height, radius = 6, delay = 0, style}: Props) {
  const opacity = useSharedValue(MIN_OPACITY);

  useEffect(() => {
    opacity.value = withDelay(
      delay,
      withRepeat(
        withTiming(MAX_OPACITY, {
          duration: 850,
          easing: Easing.inOut(Easing.ease),
        }),
        -1, // forever
        true, // reverse each cycle → smooth in-and-out breathing
      ),
    );
  }, [opacity, delay]);

  const animStyle = useAnimatedStyle(() => ({opacity: opacity.value}));

  return (
    <Animated.View
      style={[
        {width, height, borderRadius: radius, backgroundColor: palette.sage},
        animStyle,
        style,
      ]}
    />
  );
}

export default memo(SkeletonBone);
