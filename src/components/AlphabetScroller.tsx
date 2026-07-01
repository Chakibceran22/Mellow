import {useEffect, useRef, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {Gesture, GestureDetector} from 'react-native-gesture-handler';
import Animated, {
  Extrapolation,
  interpolate,
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import GreenGlassBackground from './GreenGlassBackground';
import {palette} from '../theme/theme';

/** The full index shown top→bottom: symbols/digits bucket first, then A–Z. */
export const SCROLLER_LETTERS = [
  '#', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
  'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
];

/** Which index bucket a title belongs to — its uppercased first letter, or '#'. */
export function sectionKey(title: string): string {
  const c = (title?.trim()?.[0] ?? '#').toUpperCase();
  return c >= 'A' && c <= 'Z' ? c : '#';
}

// Touch column — sits in the ~20px empty gutter to the RIGHT of the + buttons,
// so at rest it never covers a row's action. Scrubbing is vertical, so a slim
// column is enough to grab.
const GUTTER_W = 22;
// Width of the rail that blooms out while scrubbing.
const RAIL_W = 24;
// Per-letter cell height is clamped into this range, then the rail is sized to fit.
const MIN_CELL = 13;
const MAX_CELL = 19;
// Space kept clear at the very top / above the mini-player at the bottom.
const TOP_INSET = 10;
const BOTTOM_INSET = 100;

type Props = {
  /** Displayed letters, top→bottom (usually {@link SCROLLER_LETTERS}). */
  letters: string[];
  /** targets[i] = list row index to jump to when letters[i] is picked. */
  targets: number[];
  /** Section of the top-most visible row — drives the idle highlight. */
  activeLetter?: string;
  /** Height of the list area, used to size the rail to the screen. */
  availableHeight: number;
  /** Jump the list to this row index. */
  onScrubIndex: (index: number) => void;
};

/**
 * A–Z fast-scroll rail for the Songs list. At rest it is just a small grip nub
 * tucked in the right gutter (past the row + buttons, so it never covers them).
 * Press or drag the right edge and the full A–Z rail BLOOMS in — glass panel,
 * white letters, and a large preview bubble — then collapses back to the nub on
 * release. Nothing appears on plain scrolling.
 *
 * Gestures use react-native-gesture-handler so the vertical drag isn't stolen by
 * the list's native scroll.
 */
export default function AlphabetScroller({
  letters,
  targets,
  activeLetter,
  availableHeight,
  onScrubIndex,
}: Props) {
  const [active, setActive] = useState(false);
  const [scrubIdx, setScrubIdx] = useState(-1);

  // Bloom animation (0 = collapsed nub, 1 = full rail).
  const bloom = useSharedValue(0);
  useEffect(() => {
    bloom.value = withTiming(active ? 1 : 0, {duration: 180});
  }, [active, bloom]);

  // Size each letter cell to fill the available height, clamped for legibility.
  const usable = Math.max(0, (availableHeight || 0) - TOP_INSET - BOTTOM_INSET);
  const cell = Math.min(
    MAX_CELL,
    Math.max(MIN_CELL, usable > 0 ? usable / letters.length : MIN_CELL),
  );
  const railH = cell * letters.length;
  const top = Math.max(TOP_INSET, TOP_INSET + (usable - railH) / 2);

  // Screen geometry + latest props, read from inside the gesture callbacks.
  const geom = useRef({top: 0, height: 0});
  const containerRef = useRef<View>(null);
  const data = useRef({letters, targets, onScrubIndex, lastIdx: -1});
  data.current.letters = letters;
  data.current.targets = targets;
  data.current.onScrubIndex = onScrubIndex;

  const measure = () =>
    containerRef.current?.measureInWindow((_x, y, _w, h) => {
      geom.current = {top: y, height: h};
    });

  const handleAt = (absY: number) => {
    const {top: gTop, height} = geom.current;
    const {letters: ls, targets: tg, onScrubIndex: jump, lastIdx} = data.current;
    if (height <= 0 || ls.length === 0) {
      return;
    }
    const cellH = height / ls.length;
    let i = Math.floor((absY - gTop) / cellH);
    if (i < 0) {
      i = 0;
    } else if (i >= ls.length) {
      i = ls.length - 1;
    }
    if (i === lastIdx) {
      return; // same letter — nothing new to do
    }
    data.current.lastIdx = i;
    setScrubIdx(i);
    jump(tg[i]);
  };

  const onStart = (absY: number) => {
    data.current.lastIdx = -1;
    setActive(true);
    measure();
    handleAt(absY);
  };
  const onEnd = () => {
    setActive(false);
    setScrubIdx(-1);
    data.current.lastIdx = -1;
  };

  // gesture-handler Pan: onBegin covers a plain tap, onUpdate covers the drag.
  const pan = Gesture.Pan()
    .minDistance(0)
    .onBegin(e => runOnJS(onStart)(e.absoluteY))
    .onUpdate(e => runOnJS(handleAt)(e.absoluteY))
    .onFinalize(() => runOnJS(onEnd)());

  // Nub visible only at rest; the full rail fades/slides in while scrubbing.
  const nubStyle = useAnimatedStyle(() => ({
    opacity: interpolate(bloom.value, [0, 1], [1, 0], Extrapolation.CLAMP),
  }));
  const railStyle = useAnimatedStyle(() => ({
    opacity: bloom.value,
    transform: [
      {translateX: interpolate(bloom.value, [0, 1], [12, 0], Extrapolation.CLAMP)},
    ],
  }));
  const bubbleStyle = useAnimatedStyle(() => ({
    opacity: bloom.value,
    transform: [{scale: 0.8 + bloom.value * 0.2}],
  }));

  const current = scrubIdx >= 0 ? letters[scrubIdx] : undefined;

  return (
    <GestureDetector gesture={pan}>
      <View
        ref={containerRef}
        onLayout={measure}
        pointerEvents="box-only"
        style={[styles.container, {top, height: railH}]}>
        {/* Resting affordance — a tiny grip nub, entirely inside the gutter. */}
        <Animated.View style={[styles.nub, nubStyle]} pointerEvents="none" />

        {/* Full rail — blooms in only while scrubbing. */}
        <Animated.View
          style={[styles.rail, railStyle]}
          pointerEvents="none">
          <View style={styles.railGlass}>
            <GreenGlassBackground />
          </View>
          {letters.map((l, i) => {
            const isScrub = i === scrubIdx;
            const isIdle = l === activeLetter && scrubIdx < 0;
            return (
              <Text
                key={l}
                style={[
                  styles.letter,
                  {height: cell, lineHeight: cell},
                  isIdle && styles.letterIdleActive,
                  isScrub && styles.letterScrub,
                ]}>
                {l}
              </Text>
            );
          })}
        </Animated.View>

        {/* Large floating preview bubble to the left of the rail */}
        {current ? (
          <Animated.View
            style={[styles.bubble, bubbleStyle]}
            pointerEvents="none">
            <GreenGlassBackground />
            <Text style={styles.bubbleText}>{current}</Text>
          </Animated.View>
        ) : null}
      </View>
    </GestureDetector>
  );
}

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    right: 0,
    width: GUTTER_W,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 20,
  },
  nub: {
    width: 3,
    height: 38,
    borderRadius: 1.5,
    backgroundColor: palette.deep,
    opacity: 0.35,
  },
  rail: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    right: 0,
    width: RAIL_W,
    alignItems: 'center',
    justifyContent: 'center',
  },
  railGlass: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    borderRadius: RAIL_W / 2,
    overflow: 'hidden',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.4)',
  },
  letter: {
    width: RAIL_W,
    textAlign: 'center',
    fontSize: 10.5,
    fontWeight: '700',
    color: 'rgba(255,255,255,0.85)',
  },
  letterIdleActive: {color: '#FFFFFF', fontWeight: '800'},
  letterScrub: {color: '#FFFFFF', fontSize: 13, fontWeight: '900'},
  bubble: {
    position: 'absolute',
    right: GUTTER_W + 6,
    top: '50%',
    marginTop: -27,
    width: 54,
    height: 54,
    borderRadius: 27,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: palette.ink,
    shadowOpacity: 0.28,
    shadowRadius: 12,
    shadowOffset: {width: 0, height: 4},
    elevation: 9,
  },
  bubbleText: {
    color: '#FFFFFF',
    fontSize: 26,
    fontWeight: '800',
  },
});
