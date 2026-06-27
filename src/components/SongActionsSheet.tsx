import {ReactNode} from 'react';
import {Pressable, StyleSheet, View} from 'react-native';
import {Text} from 'react-native-paper';
import {MusicNotesPlus, PencilSimple, Trash} from 'phosphor-react-native';
import BottomSheet from './BottomSheet';
import {type LibrarySong} from '../data/mockSongs';
import {palette} from '../theme/theme';

type Props = {
  visible: boolean;
  song: LibrarySong | null;
  onClose: () => void;
  onAddToPlaylist: (song: LibrarySong) => void;
  onRename: (song: LibrarySong) => void;
  onDelete: (song: LibrarySong) => void;
  onDismiss?: () => void;
};

type ActionRowProps = {
  label: string;
  destructive?: boolean;
  icon: ReactNode;
  onPress: () => void;
};

function ActionRow({label, destructive, icon, onPress}: ActionRowProps) {
  return (
    <View style={styles.actionClip}>
      <Pressable
        accessibilityRole="button"
        onPress={onPress}
        android_ripple={{
          color: palette.hairline,
          borderless: false,
          foreground: true,
        }}
        style={({pressed}) => [styles.actionRow, pressed && styles.pressed]}>
        <View style={styles.iconWrap}>{icon}</View>
        <Text
          variant="titleSmall"
          style={[styles.actionLabel, destructive && styles.destructiveLabel]}>
          {label}
        </Text>
      </Pressable>
    </View>
  );
}

/**
 * Context menu for a single song — just the action buttons (no song header, so
 * nothing blanks while the sheet animates out). Each action closes the sheet
 * first, then hands the song back to the caller (LibraryScreen), which opens the
 * matching sub-sheet (add-to-playlist / rename / delete confirm).
 */
export default function SongActionsSheet({
  visible,
  song,
  onClose,
  onAddToPlaylist,
  onRename,
  onDelete,
  onDismiss,
}: Props) {
  const run = (action: (song: LibrarySong) => void) => {
    if (!song) {
      return;
    }
    onClose();
    action(song);
  };

  return (
    <BottomSheet visible={visible} onClose={onClose} onDismiss={onDismiss}>
      <View style={styles.actions}>
        <ActionRow
          label="Add to playlist"
          icon={<MusicNotesPlus size={22} color={palette.deep} weight="bold" />}
          onPress={() => run(onAddToPlaylist)}
        />
        <ActionRow
          label="Change name"
          icon={<PencilSimple size={22} color={palette.deep} weight="bold" />}
          onPress={() => run(onRename)}
        />
        <ActionRow
          label="Delete song"
          destructive
          icon={<Trash size={22} color={palette.clay} weight="bold" />}
          onPress={() => run(onDelete)}
        />
      </View>
    </BottomSheet>
  );
}

const styles = StyleSheet.create({
  actions: {paddingTop: 2, paddingBottom: 4},
  // Simple list rows with bare icons — no tinted circle behind them.
  actionClip: {
    borderRadius: 14,
    overflow: 'hidden',
  },
  actionRow: {
    minHeight: 54,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
    borderRadius: 14,
    paddingHorizontal: 6,
  },
  iconWrap: {
    width: 26,
    alignItems: 'center',
    justifyContent: 'center',
  },
  actionLabel: {color: palette.ink, fontWeight: '700'},
  destructiveLabel: {color: palette.clay},
  pressed: {opacity: 0.6},
});
