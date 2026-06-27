import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

/**
 * One track as returned by the native MediaStore query. Kept flat + primitive
 * because TurboModule codegen only marshals plain objects (no nested classes).
 *
 * - `url`     → `content://` uri for the audio file (feed straight to the player)
 * - `artwork` → `content://` album-art uri, or '' when the device has none
 */
export type Song = {
  id: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  url: string;
  artwork: string;
};

export interface Spec extends TurboModule {
  /**
   * Read every music track off the device via MediaStore, sorted by title.
   * The caller is responsible for holding the audio read permission first;
   * without it MediaStore returns an empty set (resolves to `[]`).
   */
  getSongs(): Promise<Song[]>;
}

// `get` (not `getEnforcing`) so importing this on a platform/binary where the
// module isn't built (iOS, or before a native rebuild) yields `null` instead of
// throwing at import time. Callers must null-check.
export default TurboModuleRegistry.get<Spec>('MusicLibrary');
