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
| **react-native-track-player** | `^5.0.0` | V5 rewrite on JSI + TurboModules. Media3 on Android, sleep timer, caching, preloading. **Free for personal use** (this app is built for personal use — not commercial). |
| **@op-engineering/op-sqlite** | `^16.2.2` | Fastest SQLite in RN. JSI-based, new-arch native. For playlists + favorites. |
| **react-native-mmkv** | `^4.3.2` | V4 fully rewritten on Nitro Modules. New-arch native. Requires RN ≥ 0.76. For app settings (theme, last-played, prefs). |
| **react-native-paper** | `^5.15.3` | Material You UI components. New-arch compatible. |
| **react-native-vector-icons** | `^10.2.x` | Icons for Paper. |
| **react-native-safe-area-context** | `^5.x` | Safe areas. |
| **react-native-screens** | `^4.x` | Native screen optimization. |
| **@react-navigation/native** | `^7.2.x` | New-arch ready. |
| **@react-navigation/bottom-tabs** | `^7.2.x` | Tabs. |
| **@react-navigation/native-stack** | `^7.2.x` | Stack nav. |
| **react-native-gesture-handler** | `^2.20.x` | Gestures, new-arch ready. |
| **react-native-reanimated** | `^3.16.x` | Animations. New-arch ready. Pairs with RN 0.85's shared animation backend. |
| **@missingcore/audio-metadata** | `^1.3.0` | ID3 tag deep-read (when MediaStore data is insufficient). |
| **moti** | latest | Optional. Declarative animations on top of Reanimated. |

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

### 2.2. Playback — use `react-native-track-player` v5

Personal-use app, so v5 is free. Handles background playback, lock-screen controls, notifications, audio focus, Bluetooth, Android Auto out of the box — no custom Kotlin needed for playback.

**Setup pattern:**
- `TrackPlayer.setupPlayer()` once at app start
- Register a playback service in `index.js` for background ops
- Use the v5 hooks (`useTrackPlayerEvents`, `useProgress`, etc.) in screens
- Capabilities to declare: PLAY, PAUSE, SKIP_TO_NEXT, SKIP_TO_PREVIOUS, STOP, SEEK_TO

**Queue items shape (RNTP v5):**
```ts
type Track = {
  id: string;            // mediastore_id as string
  url: string;           // content:// URI from MediaStore
  title: string;
  artist: string;
  album?: string;
  artwork?: string;      // content:// album art URI
  duration?: number;     // seconds
};
```

Follow the v5 docs at https://rntp.dev — do not copy snippets from v4 tutorials, the API changed.

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

### 6.1. `react-native-track-player` v5 — licensing note
- v5.0.0 (May 2026) introduced paid commercial licensing
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

# 2. Install runtime deps
npm install \
  react-native-track-player \
  @op-engineering/op-sqlite \
  react-native-mmkv \
  react-native-paper \
  react-native-vector-icons \
  react-native-safe-area-context \
  react-native-screens \
  react-native-gesture-handler \
  react-native-reanimated \
  @react-navigation/native \
  @react-navigation/bottom-tabs \
  @react-navigation/native-stack \
  @missingcore/audio-metadata \
  moti

# 3. Follow react-native-track-player v5 install steps (manifest entries, playback service)

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
