import {ActivityIndicator, Modal, StyleSheet, View} from 'react-native';
import {Text} from 'react-native-paper';
import {palette} from '../theme/theme';

type Props = {
  visible: boolean;
  /** Optional caption under the spinner, e.g. "Creating playlist…". */
  label?: string;
};

/**
 * Blocking loading overlay — a dimmed backdrop with a small green card + spinner.
 * Use it for short, user-initiated async work (creating a playlist, etc.) so the
 * action reads as "in progress" and double-taps can't slip through.
 */
export default function LoadingModal({visible, label}: Props) {
  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={() => {}}>
      <View style={styles.backdrop}>
        <View style={styles.card}>
          <ActivityIndicator size="large" color={palette.deep} />
          {label ? <Text style={styles.label}>{label}</Text> : null}
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(35, 51, 45, 0.35)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  card: {
    minWidth: 150,
    paddingVertical: 22,
    paddingHorizontal: 26,
    borderRadius: 18,
    backgroundColor: palette.surface,
    alignItems: 'center',
    gap: 12,
    shadowColor: palette.ink,
    shadowOpacity: 0.18,
    shadowRadius: 16,
    shadowOffset: {width: 0, height: 6},
    elevation: 6,
  },
  label: {color: palette.inkSoft, fontWeight: '600'},
});
