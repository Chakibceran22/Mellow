import {useEffect, useRef, useState} from 'react';
import {
  Modal,
  Pressable,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import {Text} from 'react-native-paper';
import {palette} from '../theme/theme';

type Props = {
  visible: boolean;
  onCancel: () => void;
  onCreate: (name: string) => void;
};

/**
 * Centered card to name a new playlist. Songs aren't part of creation — the
 * user just types a name and taps Create. Empty / whitespace names are blocked.
 */
export default function CreatePlaylistModal({
  visible,
  onCancel,
  onCreate,
}: Props) {
  const [name, setName] = useState('');
  const inputRef = useRef<TextInput>(null);
  const canCreate = name.trim().length > 0;

  // Reset + focus each time it opens.
  useEffect(() => {
    if (visible) {
      setName('');
      const t = setTimeout(() => inputRef.current?.focus(), 120);
      return () => clearTimeout(t);
    }
  }, [visible]);

  const submit = () => {
    if (canCreate) {
      onCreate(name.trim());
    }
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={onCancel}>
      {/* Tap outside the card to dismiss */}
      <Pressable style={styles.backdrop} onPress={onCancel}>
        <Pressable style={styles.card} onPress={() => {}}>
          <Text variant="titleMedium" style={styles.title}>
            New playlist
          </Text>

          <TextInput
            ref={inputRef}
            value={name}
            onChangeText={setName}
            placeholder="Playlist name"
            placeholderTextColor={palette.inkSoft}
            style={styles.input}
            returnKeyType="done"
            onSubmitEditing={submit}
            maxLength={60}
            autoCorrect={false}
          />

          <View style={styles.actions}>
            <Pressable
              onPress={onCancel}
              style={({pressed}) => [
                styles.btn,
                styles.cancelBtn,
                pressed && styles.pressed,
              ]}>
              <Text style={styles.cancelLabel}>Cancel</Text>
            </Pressable>
            <Pressable
              onPress={submit}
              disabled={!canCreate}
              style={({pressed}) => [
                styles.btn,
                styles.createBtn,
                !canCreate && styles.createBtnDisabled,
                pressed && styles.pressed,
              ]}>
              <Text style={styles.createLabel}>Create</Text>
            </Pressable>
          </View>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(35, 51, 45, 0.35)',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 32,
  },
  card: {
    width: '100%',
    maxWidth: 360,
    padding: 22,
    borderRadius: 20,
    backgroundColor: palette.surface,
    gap: 16,
    shadowColor: palette.ink,
    shadowOpacity: 0.18,
    shadowRadius: 18,
    shadowOffset: {width: 0, height: 8},
    elevation: 8,
  },
  title: {color: palette.ink, fontWeight: '700'},
  input: {
    backgroundColor: palette.surfaceAlt,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: palette.ink,
    fontSize: 16,
  },
  actions: {flexDirection: 'row', justifyContent: 'flex-end', gap: 10},
  btn: {
    paddingHorizontal: 18,
    height: 44,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cancelBtn: {backgroundColor: palette.surfaceAlt},
  cancelLabel: {color: palette.inkSoft, fontWeight: '700'},
  createBtn: {backgroundColor: palette.deep},
  createBtnDisabled: {opacity: 0.4},
  createLabel: {color: '#FFFFFF', fontWeight: '700'},
  pressed: {opacity: 0.85},
});
