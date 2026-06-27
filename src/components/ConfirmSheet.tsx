import {useEffect, useState} from 'react';
import {Pressable, StyleSheet, View} from 'react-native';
import {Text} from 'react-native-paper';
import BottomSheet from './BottomSheet';
import {palette} from '../theme/theme';

type Props = {
  visible: boolean;
  title: string;
  message?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Paints the confirm button red — use for deletes. */
  destructive?: boolean;
  onConfirm: () => void;
  onClose: () => void;
  onDismiss?: () => void;
};

/**
 * Generic confirmation sheet built on {@link BottomSheet}. Reused for deletes
 * and any other "are you sure?" moment. The caller's `onConfirm` is responsible
 * for closing (so it can run async work first, then dismiss).
 */
export default function ConfirmSheet({
  visible,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  destructive,
  onConfirm,
  onClose,
  onDismiss,
}: Props) {
  // Retain the text through the slide-out — the caller usually derives title /
  // message from the item being acted on and clears it the moment it closes.
  const [shown, setShown] = useState({title, message});
  useEffect(() => {
    if (visible) {
      setShown({title, message});
    }
  }, [visible, title, message]);

  return (
    <BottomSheet visible={visible} onClose={onClose} onDismiss={onDismiss}>
      <View style={styles.body}>
        <Text variant="titleMedium" style={styles.title}>
          {shown.title}
        </Text>
        {shown.message ? (
          <Text variant="bodyMedium" style={styles.message}>
            {shown.message}
          </Text>
        ) : null}

        <View style={styles.actions}>
          <View style={styles.btnClip}>
            <Pressable
              onPress={onClose}
              android_ripple={{
                color: palette.hairline,
                borderless: false,
                foreground: true,
              }}
              style={({pressed}) => [
                styles.btn,
                styles.cancelBtn,
                pressed && styles.pressed,
              ]}>
              <Text style={styles.cancelLabel}>{cancelLabel}</Text>
            </Pressable>
          </View>
          <View style={styles.btnClip}>
            <Pressable
              onPress={onConfirm}
              android_ripple={{
                color: 'rgba(255,255,255,0.18)',
                borderless: false,
                foreground: true,
              }}
              style={({pressed}) => [
                styles.btn,
                destructive ? styles.destructiveBtn : styles.confirmBtn,
                pressed && styles.pressed,
              ]}>
              <Text style={styles.confirmLabel}>{confirmLabel}</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </BottomSheet>
  );
}

const styles = StyleSheet.create({
  body: {paddingTop: 4, paddingBottom: 4, gap: 10},
  title: {color: palette.ink, fontWeight: '700'},
  message: {color: palette.inkSoft, lineHeight: 20},
  actions: {flexDirection: 'row', gap: 10, marginTop: 8},
  btnClip: {
    flex: 1,
    borderRadius: 14,
    overflow: 'hidden',
  },
  btn: {
    height: 48,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pressed: {opacity: 0.85},
  cancelBtn: {backgroundColor: palette.surfaceAlt},
  cancelLabel: {color: palette.inkSoft, fontWeight: '700'},
  confirmBtn: {backgroundColor: palette.deep},
  destructiveBtn: {backgroundColor: palette.clay},
  confirmLabel: {color: '#FFFFFF', fontWeight: '700'},
});
