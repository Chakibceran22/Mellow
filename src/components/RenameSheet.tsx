import {useEffect, useRef, useState} from 'react';
import {Pressable, StyleSheet, TextInput, View} from 'react-native';
import {Text} from 'react-native-paper';
import BottomSheet from './BottomSheet';
import {palette} from '../theme/theme';

type Props = {
  visible: boolean;
  title?: string;
  /** Text the field starts with each time it opens. */
  initialValue: string;
  placeholder?: string;
  saveLabel?: string;
  onSave: (value: string) => void;
  onClose: () => void;
  onDismiss?: () => void;
};

/**
 * Single-field "rename" sheet built on {@link BottomSheet}. Reused anywhere we
 * need a quick text edit (song title, playlist name, …). Save is blocked while
 * the field is empty or unchanged.
 */
export default function RenameSheet({
  visible,
  title = 'Rename',
  initialValue,
  placeholder = 'Name',
  saveLabel = 'Save',
  onSave,
  onClose,
  onDismiss,
}: Props) {
  const [value, setValue] = useState(initialValue);
  const inputRef = useRef<TextInput>(null);

  // Seed with the current value + focus each time it opens.
  useEffect(() => {
    if (visible) {
      setValue(initialValue);
      const t = setTimeout(() => inputRef.current?.focus(), 160);
      return () => clearTimeout(t);
    }
  }, [visible, initialValue]);

  const trimmed = value.trim();
  const canSave = trimmed.length > 0 && trimmed !== initialValue.trim();

  const submit = () => {
    if (canSave) {
      onSave(trimmed);
    }
  };

  return (
    <BottomSheet visible={visible} onClose={onClose} onDismiss={onDismiss}>
      <View style={styles.body}>
        <Text variant="titleMedium" style={styles.title}>
          {title}
        </Text>

        <TextInput
          ref={inputRef}
          value={value}
          onChangeText={setValue}
          placeholder={placeholder}
          placeholderTextColor={palette.inkSoft}
          style={styles.input}
          returnKeyType="done"
          onSubmitEditing={submit}
          maxLength={120}
          autoCorrect={false}
          selectTextOnFocus
        />

        <View style={styles.actions}>
          <Pressable
            onPress={onClose}
            android_ripple={{color: palette.hairline}}
            style={({pressed}) => [
              styles.btn,
              styles.cancelBtn,
              pressed && styles.pressed,
            ]}>
            <Text style={styles.cancelLabel}>Cancel</Text>
          </Pressable>
          <Pressable
            onPress={submit}
            disabled={!canSave}
            android_ripple={{color: 'rgba(255,255,255,0.18)'}}
            style={({pressed}) => [
              styles.btn,
              styles.saveBtn,
              !canSave && styles.saveBtnDisabled,
              pressed && styles.pressed,
            ]}>
            <Text style={styles.saveLabel}>{saveLabel}</Text>
          </Pressable>
        </View>
      </View>
    </BottomSheet>
  );
}

const styles = StyleSheet.create({
  body: {paddingTop: 4, paddingBottom: 4, gap: 14},
  title: {color: palette.ink, fontWeight: '700'},
  input: {
    backgroundColor: palette.surfaceAlt,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: palette.ink,
    fontSize: 16,
  },
  actions: {flexDirection: 'row', gap: 10},
  btn: {
    flex: 1,
    height: 48,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pressed: {opacity: 0.85},
  cancelBtn: {backgroundColor: palette.surfaceAlt},
  cancelLabel: {color: palette.inkSoft, fontWeight: '700'},
  saveBtn: {backgroundColor: palette.deep},
  saveBtnDisabled: {opacity: 0.4},
  saveLabel: {color: '#FFFFFF', fontWeight: '700'},
});
