import {useEffect, useRef, useState} from 'react';
import {Pressable, StyleSheet, View} from 'react-native';
import {Text} from 'react-native-paper';
import Animated, {
  Extrapolation,
  interpolate,
  runOnJS,
  useAnimatedReaction,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import {Gesture, GestureDetector} from 'react-native-gesture-handler';
import {Slider} from 'react-native-awesome-slider';
import {CaretDown, Pause, Play, SkipBack, SkipForward} from 'phosphor-react-native';
import TrackPlayer, {
  useActiveMediaItem,
  useIsPlaying,
  useProgress,
} from '@rntp/player';
import SongCover from './SongCover';
import GreenGlassBackground from './GreenGlassBackground';
import {resumePlayback} from '../player/setup';
import {formatDuration} from '../data/mockSongs';
import {palette} from '../theme/theme';

const MINI_H = 62;
// Peek card height in px — just enough for cover + title, seek bar, controls.
// Tweak this one number; ~195 is about the floor before things start to cramp.
const EXPANDED_H = 220;
const DRAG_RANGE = EXPANDED_H - MINI_H;
const SPRING = {damping: 20, stiffness: 180, mass: 0.7};

const WHITE = '#FFFFFF';
const WHITE_SOFT = 'rgba(255,255,255,0.72)';
const WHITE_FAINT = 'rgba(255,255,255,0.28)';

export default function PlayerSheet() {
  const active = useActiveMediaItem();
  const activeId = active?.mediaId ?? null;
  // `useIsPlaying` only re-reads native state on event / AppState change. But
  // swiping away the media notification stops Media3's service WITHOUT either —
  // the app stays foreground — so the hook goes stale and the button freezes.
  // Reconcile against the real native state once a second so it always recovers.
  const hookPlaying = useIsPlaying();
  const [playing, setPlaying] = useState(hookPlaying);
  // ⚠️ interval is in SECONDS (default 1). 0.25 → smooth ~4 updates/sec.
  const {position, duration} = useProgress(0.25);

  useEffect(() => {
    setPlaying(hookPlaying);
  }, [hookPlaying]);

  useEffect(() => {
    const id = setInterval(() => setPlaying(TrackPlayer.isPlaying()), 1000);
    return () => clearInterval(id);
  }, []);

  // 0 = collapsed mini bar, 1 = expanded peek card.
  const expand = useSharedValue(0);
  const [expanded, setExpanded] = useState(false);

  const sliderValue = useSharedValue(0);
  const sliderMin = useSharedValue(0);
  const sliderMax = useSharedValue(1);
  const scrubbing = useRef(false);
  const lastIdRef = useRef(activeId);

  useEffect(() => {
    // Track changed: reset the seek bar now and ignore this poll's position —
    // it's still the *previous* track's value for one cycle (causes a flicker).
    if (lastIdRef.current !== activeId) {
      lastIdRef.current = activeId;
      scrubbing.current = false;
      sliderValue.value = 0;
      sliderMax.value = duration > 0 ? duration : 1;
      return;
    }
    sliderMax.value = duration > 0 ? duration : 1;
    if (!scrubbing.current) {
      sliderValue.value = Math.max(0, position);
    }
  }, [activeId, position, duration, sliderMax, sliderValue]);

  useAnimatedReaction(
    () => expand.value > 0.5,
    (isExp, prev) => {
      if (isExp !== prev) {
        runOnJS(setExpanded)(isExp);
      }
    },
  );

  const pan = Gesture.Pan()
    .activeOffsetY([-10, 10]) // let taps + the horizontal slider through
    .onChange(e => {
      const next = expand.value - e.changeY / DRAG_RANGE;
      expand.value = Math.min(1, Math.max(0, next));
    })
    .onEnd(e => {
      const target =
        e.velocityY < -350 || (expand.value > 0.4 && e.velocityY <= 350)
          ? 1
          : 0;
      expand.value = withSpring(target, SPRING);
    });

  const sheetStyle = useAnimatedStyle(() => ({
    height: interpolate(
      expand.value,
      [0, 1],
      [MINI_H, EXPANDED_H],
      Extrapolation.CLAMP,
    ),
  }));
  const miniStyle = useAnimatedStyle(() => ({
    opacity: interpolate(expand.value, [0, 0.25], [1, 0], Extrapolation.CLAMP),
  }));
  const expandedStyle = useAnimatedStyle(() => ({
    opacity: interpolate(expand.value, [0.4, 1], [0, 1], Extrapolation.CLAMP),
  }));

  // All hooks are above this line — safe to bail out now.
  if (!active) {
    return null;
  }

  const artwork = (active.artworkUrl as string) ?? '';
  const title = active.title ?? 'Unknown';
  const artist = active.artist ?? '';
  const miniPct = duration > 0 ? Math.min(Math.max(position / duration, 0), 1) : 0;

  const closeSheet = () => {
    expand.value = withSpring(0, SPRING);
  };
  const openSheet = () => {
    expand.value = withSpring(1, SPRING);
  };
  // Re-read the REAL native state on press (our `playing` may be a beat stale),
  // and resume via the resilient path so a torn-down session is rebuilt instead
  // of leaving the button doing nothing.
  const togglePlay = () => {
    if (TrackPlayer.isPlaying()) {
      TrackPlayer.pause();
      setPlaying(false);
    } else {
      resumePlayback();
      setPlaying(true);
    }
  };

  return (
    <Animated.View style={[styles.sheet, sheetStyle]}>
      <GestureDetector gesture={pan}>
        <View style={StyleSheet.absoluteFill}>
          <View style={styles.bg} pointerEvents="none">
            <GreenGlassBackground />
          </View>

          <View style={styles.handle} />

          {/* ---------- MINI BAR (tap → full page) ---------- */}
          <Animated.View
            style={[styles.mini, miniStyle]}
            pointerEvents={expanded ? 'none' : 'auto'}>
            <View style={styles.miniTrack}>
              <View style={[styles.miniFill, {width: `${miniPct * 100}%`}]} />
            </View>
            <Pressable style={styles.miniInfo} onPress={openSheet}>
              <SongCover uri={artwork} size={42} />
              <View style={styles.flexText}>
                <Text variant="titleSmall" numberOfLines={1} style={styles.titleText}>
                  {title}
                </Text>
                <Text variant="bodySmall" numberOfLines={1} style={styles.softText}>
                  {artist}
                </Text>
              </View>
            </Pressable>
            <Pressable onPress={togglePlay} hitSlop={10} style={styles.miniBtn}>
              {playing ? (
                <Pause size={24} weight="fill" color={WHITE} />
              ) : (
                <Play size={24} weight="fill" color={WHITE} />
              )}
            </Pressable>
            <Pressable
              onPress={() => TrackPlayer.skipToNext()}
              hitSlop={10}
              style={styles.miniBtn}>
              <SkipForward size={22} weight="fill" color={WHITE_SOFT} />
            </Pressable>
          </Animated.View>

          {/* ---------- PEEK CARD (drag) ---------- */}
          <Animated.View
            style={[styles.expanded, expandedStyle]}
            pointerEvents={expanded ? 'auto' : 'none'}>
            <Pressable style={styles.caret} onPress={closeSheet} hitSlop={12}>
              <CaretDown size={20} weight="bold" color={WHITE_SOFT} />
            </Pressable>

            <View style={styles.expHeader}>
              <SongCover uri={artwork} size={44} />
              <View style={styles.flexText}>
                <Text variant="titleMedium" numberOfLines={1} style={styles.expTitle}>
                  {title}
                </Text>
                <Text variant="bodySmall" numberOfLines={1} style={styles.softText}>
                  {artist}
                </Text>
              </View>
            </View>

            <View style={styles.controls}>
              <View style={styles.sliderWrap}>
                <Slider
                  progress={sliderValue}
                  minimumValue={sliderMin}
                  maximumValue={sliderMax}
                  thumbWidth={14}
                  sliderHeight={4}
                  containerStyle={styles.sliderTrack}
                  bubble={(s: number) => formatDuration(s)}
                  theme={{
                    minimumTrackTintColor: WHITE,
                    maximumTrackTintColor: WHITE_FAINT,
                    bubbleBackgroundColor: palette.deep,
                    bubbleTextColor: WHITE,
                  }}
                  onSlidingStart={() => {
                    scrubbing.current = true;
                  }}
                  onSlidingComplete={(value: number) => {
                    TrackPlayer.seekTo(value);
                    scrubbing.current = false;
                  }}
                />
                <View style={styles.times}>
                  <Text variant="labelSmall" style={styles.softText}>
                    {formatDuration(position)}
                  </Text>
                  <Text variant="labelSmall" style={styles.softText}>
                    {formatDuration(duration)}
                  </Text>
                </View>
              </View>

              <View style={styles.transport}>
                <Pressable hitSlop={12} onPress={() => TrackPlayer.skipToPrevious()}>
                  <SkipBack size={26} weight="fill" color={WHITE} />
                </Pressable>
                <Pressable onPress={togglePlay} style={styles.playFab}>
                  {playing ? (
                    <Pause size={26} weight="fill" color={WHITE} />
                  ) : (
                    <Play size={26} weight="fill" color={WHITE} />
                  )}
                </Pressable>
                <Pressable hitSlop={12} onPress={() => TrackPlayer.skipToNext()}>
                  <SkipForward size={26} weight="fill" color={WHITE} />
                </Pressable>
              </View>
            </View>
          </Animated.View>
        </View>
      </GestureDetector>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  sheet: {
    position: 'absolute',
    left: 10,
    right: 10,
    bottom: 10,
    height: MINI_H,
    borderRadius: 12,
    overflow: 'hidden',
    backgroundColor: palette.deep, // fallback under the blur/gradient
    elevation: 14,
    shadowColor: '#000',
    shadowOpacity: 0.18,
    shadowRadius: 14,
    shadowOffset: {width: 0, height: 4},
  },
  bg: {position: 'absolute', left: 0, right: 0, bottom: 0, height: EXPANDED_H},
  handle: {
    alignSelf: 'center',
    width: 34,
    height: 4,
    borderRadius: 2,
    backgroundColor: WHITE_FAINT,
    marginTop: 6,
  },

  // Mini bar
  mini: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    height: MINI_H,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    gap: 10,
  },
  miniTrack: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: 2,
    backgroundColor: WHITE_FAINT,
  },
  miniFill: {height: 2, backgroundColor: WHITE},
  miniInfo: {flex: 1, flexDirection: 'row', alignItems: 'center', gap: 10},
  miniBtn: {paddingHorizontal: 4},

  // Peek card
  expanded: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    paddingTop: 16,
    paddingHorizontal: 16,
    paddingBottom: 12,
  },
  // Holds the seek bar + transport, evenly spaced so the buttons sit centered
  // in the space below the header (not glued to the bottom).
  controls: {flex: 1, justifyContent: 'space-evenly'},
  caret: {position: 'absolute', top: 10, right: 12, padding: 4},
  expHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginTop: 8,
    paddingRight: 24,
  },
  expTitle: {color: WHITE, fontWeight: '700'},
  sliderWrap: {marginTop: 0},
  sliderTrack: {borderRadius: 6},
  times: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 6,
  },
  transport: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 28,
  },
  playFab: {
    width: 54,
    height: 54,
    borderRadius: 27,
    backgroundColor: 'rgba(255,255,255,0.16)',
    alignItems: 'center',
    justifyContent: 'center',
  },

  // Shared text
  flexText: {flex: 1},
  titleText: {color: WHITE, fontWeight: '600'},
  softText: {color: WHITE_SOFT},
});
