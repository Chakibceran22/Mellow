import {PermissionsAndroid, Platform} from 'react-native';
import MusicLibrary, {type Song as NativeSong} from '../../specs/NativeMusicLibrary';
import {MOCK_SONGS, type LibrarySong} from '../data/mockSongs';

export type LibraryResult = {
  songs: LibrarySong[];
  /** True when these are real on-device tracks; false when we fell back to mocks. */
  fromDevice: boolean;
  /** Set when the device library couldn't be read (so the UI can explain why). */
  reason?: 'permission-denied' | 'unsupported';
};

/** Whether the real TurboModule is present in this binary (Android, rebuilt). */
const hasNativeLibrary = Platform.OS === 'android' && MusicLibrary != null;

/**
 * Ask for the audio-read permission appropriate to the OS version. Android 13+
 * (API 33) uses the scoped READ_MEDIA_AUDIO; older versions use storage read.
 */
async function ensureAudioPermission(): Promise<boolean> {
  const permission =
    Number(Platform.Version) >= 33
      ? PermissionsAndroid.PERMISSIONS.READ_MEDIA_AUDIO
      : PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE;

  if (await PermissionsAndroid.check(permission)) {
    return true;
  }
  const result = await PermissionsAndroid.request(permission);
  return result === PermissionsAndroid.RESULTS.GRANTED;
}

function toLibrarySong(s: NativeSong): LibrarySong {
  return {
    id: s.id,
    title: s.title || 'Unknown title',
    artist: s.artist || 'Unknown artist',
    album: s.album || 'Unknown album',
    durationSec: Math.round((s.durationMs || 0) / 1000),
    url: s.url,
    artwork: s.artwork || '',
  };
}

/**
 * Load the library. On a rebuilt Android binary this asks for permission and
 * reads MediaStore; everywhere else (iOS, or before the native rebuild) it
 * returns the mock catalogue so the UI stays usable during development.
 */
export async function fetchLibrarySongs(): Promise<LibraryResult> {
  if (!hasNativeLibrary || MusicLibrary == null) {
    return {songs: MOCK_SONGS, fromDevice: false, reason: 'unsupported'};
  }

  if (!(await ensureAudioPermission())) {
    return {songs: [], fromDevice: false, reason: 'permission-denied'};
  }

  const native = await MusicLibrary.getSongs();
  return {songs: native.map(toLibrarySong), fromDevice: true};
}
