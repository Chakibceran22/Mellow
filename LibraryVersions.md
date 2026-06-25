# Mellow — Library Versions & Stack Decisions

> Locked stack for Mellow (React Native, Android-only first phase).
> Last verified: 2026-06-23.
> Reader: this doc tells the implementing agent what to install, why, and what to be careful about.

---

## 1. Locked versions

| Package | Version | Why this version |
|---|---|---|
| **React Native** | `0.85.x` | Released April 2026. Stable, new-arch is default, old bridge fully gone (removed in 0.82), Hermes V1 default since 0.84, React 19.2 included. Skip 0.86 — too new (June 9 2026), library ecosystem hasn't caught up. |
| **React** | `19.2.x` | Ships with RN 0.85. |
| **Hermes** | bundled (V1) | Default JS engine. Do NOT switch to JSC. |
| **@rntp/player** | `^5.6.0` | ⚠️ **V5 ships as a NEW package `@rntp/player`** — the old `react-native-track-player` is frozen at v4 (Apache-2.0) and has no v5. V5 = JSI rewrite, Media3 on Android, sleep timer, caching, preloading. **Free for personal/educational use** (this app is personal — not commercial). |
| **@op-engineering/op-sqlite** | `^17.0.0` | Fastest SQLite in RN. JSI-based, new-arch native. For playlists + favorites. (Latest is 17.x; 16.x is older.) |
| **react-native-mmkv** | `^4.3.2` | V4 fully rewritten on Nitro Modules. New-arch native. Requires RN ≥ 0.76. For app settings (theme, last-played, prefs). |
| **react-native-paper** | `^5.15.3` | Material You UI components. New-arch compatible. |
| **react-native-vector-icons** | `^10.3.0` | Icons for Paper. ⚠️ The single package is **deprecated** in favor of per-family scoped packages (`@react-native-vector-icons/material-design-icons@^13`). Kept on 10.3.x for now because Paper 5 wires to it out of the box; migrate later. |
| **react-native-safe-area-context** | `^5.8.0` | Safe areas. |
| **react-native-screens** | `^4.25.2` | Native screen optimization. New-arch ready. |
| **@react-navigation/native** | `^7.3.3` | New-arch ready. |
| **@react-navigation/bottom-tabs** | `^7.18.2` | Tabs. |
| **@react-navigation/native-stack** | `^7.17.5` | Stack nav. |
| **react-native-gesture-handler** | `^3.0.2` | Gestures. ⚠️ Now on **v3** (was 2.x) — new-arch focused. |
| **react-native-reanimated** | `^4.5.0` | ⚠️ **Must be v4** for RN 0.85 (Fabric-only; v3 is unmaintained & incompatible). Peer-requires RN `0.83 - 0.86`. |
| **react-native-worklets** | `^0.10.0` | ⚠️ **Required companion of Reanimated 4** (worklets were split out of the library). Reanimated 4.5.x pins `react-native-worklets@0.10.x`. Needs the babel plugin `react-native-worklets/plugin`. |
| **@missingcore/audio-metadata** | `^1.3.0` | ID3 tag deep-read (when MediaStore data is insufficient). |
| **moti** | `^0.30.0` | Optional. Declarative animations on top of Reanimated 4 (peer-accepts any Reanimated). |

### Storage choice rationale
- **op-sqlite** → playlists, favorites, play counts (relational data, needs queries)
- **MMKV** → settings, current track id, last queue (key-value, fast read/write)
- **No AsyncStorage** — slow, deprecated for serious use

---

## 2. Native modules to write (no library — we own this)

### 2.1. `MusicLibrary` TurboModule

Wraps Android's `MediaStore.Audio` content provider. ~80 lines of Kotlin.

**TS spec** (`specs/NativeMusicLibrary.ts`):
```ts
import {TurboModule, TurboModuleRegistry} from 'react-native';

export interface Song {
  id: string;            // MediaStore._ID as string
  title: string;
  artist: string;
  album: string;
  albumId: string;
  durationMs: number;
  filePath: string;      // content:// URI
  trackNumber?: number;
  year?: number;
}

export interface Spec extends TurboModule {
  requestPermission(): Promise<boolean>;
  hasPermission(): Promise<boolean>;
  getAllSongs(): Promise<Song[]>;
  getAlbumArtUri(albumId: string): Promise<string | null>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('MusicLibrary');
```

**Kotlin implementation responsibilities:**
- Check + request `READ_MEDIA_AUDIO` (API ≥ 33) / `READ_EXTERNAL_STORAGE` (API ≤ 32)
- Query `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`
- Return list of songs as `WritableArray`
- For album art: return `content://media/external/audio/albumart/<albumId>`

### 2.2. Playback — use `@rntp/player` v5 (NOT `react-native-track-player`)

⚠️ **Package rename:** Track Player v5 is published as **`@rntp/player`**. The legacy `react-native-track-player` package is stuck on v4. Install `@rntp/player` and import from it: `import TrackPlayer from '@rntp/player'`.

Personal-use app, so v5 is free. Handles background playback, lock-screen controls, notifications, audio focus, Bluetooth, Android Auto out of the box — no custom Kotlin needed for playback.

**Setup pattern:**
- `TrackPlayer.setupPlayer()` once at app start
- Register a playback service in `index.js` for background ops
- Use the v5 hooks (`useTrackPlayerEvents`, `useProgress`, etc.) in screens
- Capabilities to declare: PLAY, PAUSE, SKIP_TO_NEXT, SKIP_TO_PREVIOUS, STOP, SEEK_TO

**MediaItem shape (RNTP v5 — fields RENAMED from v4):**
```ts
type MediaItem = {
  mediaId?: string;      // v5 name (was `id`). MediaStore._ID as string.
  url: string | number;  // content:// URI, file path, http(s), or require()
  title?: string;
  artist?: string;
  albumTitle?: string;   // v5 name (was `album`)
  artworkUrl?: string;   // v5 name (was `artwork`). content:// album art URI
  duration?: number;     // seconds (UI hint only)
  extras?: Record<string, unknown>; // app payload (since 5.1.0)
};
```

**v5 API differences from v4 (verified against installed types):**
- `setupPlayer()` is **synchronous** (returns void) — but see the race gotcha below.
- No `registerPlaybackService` / `updateOptions`. Instead:
  - `setCommands({ capabilities: PlayerCommand[], handling })` configures lock-screen/
    notification controls. `handling` defaults to `'native'` → controls work with **no JS
    running** (background, locked, Android Auto). No JS playback service needed.
  - `registerBackgroundEventHandler()` only needed if `handling: 'js' | 'hybrid'`.
- Queue API: `setMediaItems()` / `addMediaItem()` (was `add()`); `useActiveMediaItem()`,
  `useIsPlaying()` (returns **boolean**), `useProgress()` hooks.

> ⚠️ **CRITICAL race condition (cost us a debug session).** `setupPlayer()` returns
> synchronously, but the native Media3 `MediaController` connects **asynchronously**
> (`MediaController.Builder(...).buildAsync()`). The native bridge does
> `controller?.setMediaItems(...)` — a **silent no-op while the controller is still null**.
> So calling `setMediaItems()` in the same tick as `setupPlayer()` is dropped and the queue
> stays empty (UI shows "no track", nothing plays). **Fix:** either load the queue from a
> later user action (as the official example does), or retry until `getQueue().length > 0`.
> Our implementation: [src/player/setup.ts](src/player/setup.ts) `loadTestTrack()`.

**Android notification icon gotcha:** `android.notification.smallIcon` is resolved via
`getIdentifier(name, "drawable", ...)` — it must exist in `res/drawable/` (NOT `mipmap/`).
A launcher icon won't be found. We added `res/drawable/ic_notification.xml`.

**Notification controls = `setCommands` capabilities.** The native side strips ALL transport
commands and re-adds only those you pass. Mapping that bit us: `PlayerCommand.Seek` →
`COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` = the **scrub/seek bar**. Omit it and the notification has
no progress bar. `SkipForward`/`SkipBackward` add the ±15s buttons. Also: a media notification
only persists while **actively playing**; once paused it's dismissible and Samsung One UI
auto-removes it (OS behavior, not the lib).

The library auto-merges its `<service>` + `FOREGROUND_SERVICE*`/`WAKE_LOCK` permissions and
bundles Media3 1.9.2. The app only needs to add `POST_NOTIFICATIONS` (Android 13+).

---

## 3. Android configuration

### 3.1. `AndroidManifest.xml` permissions
```xml
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO"
                 android:minSdkVersion="33" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
                 android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 3.2. Playback service
RNTP v5 registers its own foreground service — no manual `<service>` block needed beyond what its install instructions add to the manifest. Follow the v5 install guide.

### 3.3. Gradle versions
- `minSdkVersion` 24 (Android 7.0)
- `compileSdkVersion` 36 (Android 16)
- `targetSdkVersion` 35 (Android 15) — bump to 36 once ecosystem stabilizes
- `kotlinVersion` 2.0+
- `gradle` 8.10+
- `NDK` 27 (RN 0.85 expects this)

### 3.4. Media3 dependency
Not needed directly — RNTP v5 bundles its own Media3 dependency internally. No extra Gradle config required.

---

## 4. Project layout (suggested)

```
mellow/
├── android/
│   ├── app/src/main/java/com/mellow/
│   │   ├── library/
│   │   │   ├── MusicLibraryModule.kt
│   │   │   └── MusicLibraryPackage.kt
│   │   └── MainApplication.kt
│   └── ...
├── specs/                            # codegen specs
│   └── NativeMusicLibrary.ts
├── src/
│   ├── db/
│   │   ├── schema.ts                 # op-sqlite migrations
│   │   ├── playlists.ts              # repository
│   │   └── favorites.ts
│   ├── native/
│   │   └── MusicLibrary.ts           # JS wrapper around TurboModule
│   ├── player/
│   │   ├── service.ts                # RNTP playback service registration
│   │   └── controls.ts               # convenience wrapper around TrackPlayer
│   ├── screens/
│   │   ├── LibraryScreen.tsx
│   │   ├── NowPlayingScreen.tsx
│   │   ├── PlaylistsScreen.tsx
│   │   └── PlaylistDetailScreen.tsx
│   ├── components/
│   ├── hooks/
│   ├── theme/                        # Paper theme (Material You + brand colors)
│   └── App.tsx
└── package.json
```

---

## 5. Database schema (op-sqlite)

```sql
CREATE TABLE playlists (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  cover_uri   TEXT
);

CREATE TABLE playlist_tracks (
  playlist_id      INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
  mediastore_id    INTEGER NOT NULL,
  position         INTEGER NOT NULL,
  added_at         INTEGER NOT NULL,
  PRIMARY KEY (playlist_id, position)
);
CREATE INDEX idx_playlist_tracks_lookup ON playlist_tracks(playlist_id, position);

CREATE TABLE track_metadata (
  mediastore_id    INTEGER PRIMARY KEY,
  is_favorite      INTEGER DEFAULT 0,
  play_count       INTEGER DEFAULT 0,
  last_played_at   INTEGER,
  rating           INTEGER
);
```

**Source-of-truth rule:** never duplicate song metadata (title/artist/album/duration) into SQLite. MediaStore owns that. SQLite only holds app-specific overlay data.

---

## 6. Important warnings

### 6.1. `@rntp/player` v5 — licensing note
- v5.0.0 (May 2026, published as the new `@rntp/player` package) introduced paid commercial licensing
- **Mellow is a personal-use app** → free license applies, no payment needed
- ⚠️ If this app is ever distributed commercially (Play Store with revenue/ads, B2B, etc.), the license model changes — re-evaluate then

### 6.2. Don't use these
- ❌ `react-native-get-music-files` — unmaintained, breaks on new arch
- ❌ `react-native-sqlite-storage` — old bridge, slow
- ❌ `AsyncStorage` for anything beyond tiny prefs
- ❌ `react-native-sound` — no background playback features
- ❌ `expo-*` packages — we're bare workflow

### 6.3. Android-only for now
- iOS code paths intentionally not implemented in this phase
- Plan: native Swift app for iOS later (separate codebase)
- TurboModule TS specs include iOS interfaces too, but Kotlin/Swift implementations are Android-only
- Do NOT add iOS stub implementations — let codegen fail at iOS build time if someone tries

### 6.4. Permissions UX
- Android 13+ users must grant `READ_MEDIA_AUDIO` on first launch
- Build a permission-request screen that explains why (music access)
- Handle "denied permanently" case: deep-link to app settings via `Linking.openSettings()`

---

## 7. Bootstrap commands (for the implementing agent)

```bash
# 1. Init project (no Expo)
npx @react-native-community/cli init Mellow --version 0.85
cd Mellow

# 2. Install runtime deps (versions pinned per §1)
npm install \
  @rntp/player@^5.6.0 \
  @op-engineering/op-sqlite@^17.0.0 \
  react-native-mmkv@^4.3.2 \
  react-native-paper@^5.15.3 \
  react-native-vector-icons@^10.3.0 \
  react-native-safe-area-context@^5.8.0 \
  react-native-screens@^4.25.2 \
  react-native-gesture-handler@^3.0.2 \
  react-native-reanimated@^4.5.0 \
  react-native-worklets@^0.10.0 \
  @react-navigation/native@^7.3.3 \
  @react-navigation/bottom-tabs@^7.18.2 \
  @react-navigation/native-stack@^7.17.5 \
  @missingcore/audio-metadata@^1.3.0 \
  moti@^0.30.0

# 2b. Add the Reanimated 4 / worklets babel plugin to babel.config.js:
#     plugins: ['react-native-worklets/plugin']   // MUST be last in the plugins array

# 3. Follow @rntp/player v5 install steps (manifest entries, playback service)

# 4. Run codegen (after writing TS specs)
cd android && ./gradlew generateCodegenArtifactsFromSchema

# 5. Run on Android
npm run android
```

---

## 8. Reference URLs (current as of 2026-06-23)

- React Native versions: https://reactnative.dev/versions
- React Native Track Player: https://www.rntp.dev/ (licensing: https://rntp.dev/pricing)
- op-sqlite: https://github.com/OP-Engineering/op-sqlite
- react-native-mmkv: https://github.com/mrousavy/react-native-mmkv
- react-native-paper: https://reactnativepaper.com/
- Media3 / ExoPlayer: https://developer.android.com/media/media3
- MediaStore.Audio: https://developer.android.com/reference/android/provider/MediaStore.Audio.Media

---

## 9. Hand-off checklist for next agent

Before writing code, verify:
- [ ] Node 20.x or later installed
- [ ] Android Studio with SDK 36 + NDK 27 installed
- [ ] `JAVA_HOME` points to JDK 17
- [ ] User confirms: Android-only first phase ✓
- [ ] User confirms: RNTP v5 (personal-use license) for playback ✓
- [ ] User confirms: SQLite for playlists, MMKV for settings ✓
- [ ] Logo assets from `/home/chakib/Downloads/Mellow_Logos/` are copied into `android/app/src/main/res/` as adaptive icon resources

Build order:
1. Scaffold project with `npx @react-native-community/cli init`
2. Install deps, configure new arch (should be default in 0.85)
3. Write `MusicLibrary` TurboModule (TS spec + Kotlin)
4. Write a tiny test screen that calls `getAllSongs()` and renders the list with Paper
5. Set up op-sqlite + schema + playlists repository
6. Install RNTP v5, register playback service, configure capabilities
7. Wire up NowPlayingScreen with RNTP hooks (useProgress, useTrackPlayerEvents)
8. Build full UI (Library, Playlists, PlaylistDetail, NowPlaying, Search)
9. Theme with Paper Material You + Mellow brand colors (`#E6F1E0`, `#B1D3BA`, `#649288`, `#89BEA4`)






next prompte :
small notices fo the play and shuffle first they shouldnt be pill shaped it should be a row above it yes like above the list and that would be better and for the title above it should have the songs and playlists that's it , and the thing is that i should be able to scroll between the songs and playlists like the tabs should scroll between them like instagram does you see with a smooth annnimation too