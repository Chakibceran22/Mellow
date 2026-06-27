import {ReactNode, useCallback, useEffect, useRef, useState} from 'react';
import {
  BackHandler,
  Keyboard,
  Platform,
  Pressable,
  StyleSheet,
  View,
} from 'react-native';
import {Gesture, GestureDetector} from 'react-native-gesture-handler';
import Animated, {
  Easing,
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import {Portal} from 'react-native-paper';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {palette} from '../theme/theme';

const OPEN_MS = 280;
const CLOSE_MS = 220;
const EASE_OUT = Easing.out(Easing.cubic);
const EASE_IN = Easing.in(Easing.cubic);

type Props = {
  visible: boolean;
  onClose: () => void;
  /** Fires after the close animation finishes and the sheet is unmounted. */
  onDismiss?: () => void;
  children: ReactNode;
  /** Drag-to-dismiss + tap-outside (default true). Turn off for blocking sheets. */
  dismissable?: boolean;
};

/**
 * Global bottom sheet — slides up from the bottom like Spotify, draggable on
 * the sheet surface while preserving inner button taps.
 *
 * ⚠️ Deliberately NOT a React Native `Modal` (on Android a Modal renders outside
 * the root view, so gesture-handler never sees the drag). It portals to the app
 * root so sheets opened from nested pages still sit above the mini player.
 *
 * 🔑 The close animation is started DIRECTLY on the UI thread (gesture worklet /
 * a shared-value write) and `onClose` is only called when it finishes — it is
 * NOT driven by a `visible` → effect → re-render round-trip. That round-trip was
 * the source of the "slides partway, stalls, then jumps to done" lag: a heavy
 * re-render (e.g. rebuilding the song list on delete) blocked the JS thread
 * right when the animation should have started. See reanimated #2300 / #6247.
 */
export default function BottomSheet({
  visible,
  onClose,
  onDismiss,
  children,
  dismissable = true,
}: Props) {
  const insets = useSafeAreaInsets();
  const [mounted, setMounted] = useState(visible);

  const translateY = useSharedValue(0); // px below the resting (open) position
  const sheetH = useSharedValue(0); // measured height — animation travels exactly this
  const appeared = useSharedValue(0); // 0 = hidden/not-entered, 1 = visible
  const closing = useSharedValue(0); // guard so we never start two exits
  const kb = useSharedValue(0); // keyboard height — lifts input sheets

  const visibleRef = useRef(visible);
  visibleRef.current = visible;

  // Runs on JS once the exit animation settles.
  const finishClose = useCallback(() => {
    closing.value = 0;
    appeared.value = 0;
    setMounted(false);
    // Only notify the parent if WE started the close (drag / backdrop / back);
    // if the parent already flipped `visible`, it doesn't need telling again.
    if (visibleRef.current) {
      onClose();
    }
    onDismiss?.();
  }, [appeared, closing, onClose, onDismiss]);

  // Start the exit on the UI thread. Callable from JS (backdrop/back) — writing
  // the shared value kicks the animation immediately, no re-render needed.
  const animateClose = useCallback(() => {
    if (closing.value) {
      return;
    }
    closing.value = 1;
    translateY.value = withTiming(
      sheetH.value,
      {duration: CLOSE_MS, easing: EASE_IN},
      finished => {
        if (finished) {
          runOnJS(finishClose)();
        }
      },
    );
  }, [closing, finishClose, sheetH, translateY]);

  // Mount on open; animate out if the parent closes us externally.
  useEffect(() => {
    if (visible) {
      setMounted(true);
    } else if (mounted) {
      animateClose();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible]);

  // Entrance on (re)open. First-ever open has no measured height yet, so onLayout
  // handles that one; this handles every reopen.
  useEffect(() => {
    if (mounted && sheetH.value > 0 && appeared.value === 0) {
      closing.value = 0;
      translateY.value = sheetH.value;
      appeared.value = 1;
      translateY.value = withTiming(0, {duration: OPEN_MS, easing: EASE_OUT});
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mounted]);

  // Android hardware back closes the sheet (smoothly) instead of the screen.
  useEffect(() => {
    if (!mounted) {
      return;
    }
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      animateClose();
      return true;
    });
    return () => sub.remove();
  }, [mounted, animateClose]);

  // Track the keyboard so input sheets float above it.
  useEffect(() => {
    if (!mounted) {
      return;
    }
    const showEvt = Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow';
    const hideEvt = Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide';
    const showSub = Keyboard.addListener(showEvt, e => {
      kb.value = withTiming(e.endCoordinates.height, {duration: 220});
    });
    const hideSub = Keyboard.addListener(hideEvt, () => {
      kb.value = withTiming(0, {duration: 220});
    });
    return () => {
      showSub.remove();
      hideSub.remove();
    };
  }, [mounted, kb]);

  const onLayout = (height: number) => {
    if (height <= 0) {
      return;
    }
    const firstMeasure = sheetH.value === 0;
    sheetH.value = height;
    if (firstMeasure && appeared.value === 0 && mounted) {
      closing.value = 0;
      translateY.value = height;
      appeared.value = 1;
      translateY.value = withTiming(0, {duration: OPEN_MS, easing: EASE_OUT});
    }
  };

  const pan = Gesture.Pan()
    .enabled(dismissable)
    .cancelsTouchesInView(false)
    .onChange(e => {
      translateY.value = Math.max(0, translateY.value + e.changeY);
    })
    .onEnd(e => {
      // Dismiss / settle decided + animated entirely on the UI thread.
      if (translateY.value > sheetH.value * 0.28 || e.velocityY > 650) {
        if (closing.value === 0) {
          closing.value = 1;
          translateY.value = withTiming(
            sheetH.value,
            {duration: CLOSE_MS, easing: EASE_IN},
            finished => {
              if (finished) {
                runOnJS(finishClose)();
              }
            },
          );
        }
      } else {
        translateY.value = withTiming(0, {duration: 180, easing: EASE_OUT});
      }
    });

  const backdropStyle = useAnimatedStyle(() => {
    const h = sheetH.value;
    const closed = h > 0 ? Math.min(Math.max(translateY.value / h, 0), 1) : 1;
    return {opacity: (1 - closed) * appeared.value};
  });

  const sheetStyle = useAnimatedStyle(() => ({
    opacity: appeared.value,
    transform: [{translateY: translateY.value - kb.value}],
  }));

  if (!mounted) {
    return null;
  }

  return (
    <Portal>
      <View style={styles.overlay}>
        <Animated.View style={[StyleSheet.absoluteFill, styles.backdrop, backdropStyle]}>
          <Pressable
            style={styles.flex}
            onPress={dismissable ? animateClose : undefined}
          />
        </Animated.View>

        <Animated.View
          style={[styles.sheet, sheetStyle]}
          onLayout={e => onLayout(e.nativeEvent.layout.height)}>
          <GestureDetector gesture={pan}>
            <View style={[styles.sheetSurface, {paddingBottom: insets.bottom + 12}]}>
              <View style={styles.grabArea}>
                <View style={styles.handle} />
              </View>
              {children}
            </View>
          </GestureDetector>
        </Animated.View>
      </View>
    </Portal>
  );
}

const styles = StyleSheet.create({
  flex: {flex: 1},
  overlay: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    justifyContent: 'flex-end',
    zIndex: 1000,
    elevation: 1000,
  },
  backdrop: {backgroundColor: 'rgba(35, 51, 45, 0.45)'},
  sheet: {
    maxHeight: '88%',
    borderTopLeftRadius: 22,
    borderTopRightRadius: 22,
    shadowColor: palette.ink,
    shadowOpacity: 0.18,
    shadowRadius: 18,
    shadowOffset: {width: 0, height: -4},
    elevation: 16,
  },
  sheetSurface: {
    maxHeight: '100%',
    backgroundColor: palette.surface,
    borderTopLeftRadius: 22,
    borderTopRightRadius: 22,
    overflow: 'hidden',
    paddingHorizontal: 16,
    paddingTop: 6,
  },
  grabArea: {alignItems: 'center', paddingVertical: 10},
  handle: {
    width: 42,
    height: 4,
    borderRadius: 2,
    backgroundColor: palette.hairline,
  },
});
