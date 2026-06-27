import TrackPlayer, {PlayerCommand, type MediaItem} from '@rntp/player';

/**
 * App-level song shape. Later this is what the MusicLibrary TurboModule returns
 * (from MediaStore). For now it's our test data.
 */
export type Song = {
  id: string;
  title: string;
  artist: string;
  url: string; // http(s) for the test; content:// later
  artwork?: string;
};

let isSetup = false;

/**
 * Boot the audio ENGINE once. This loads NO songs — it just initialises the
 * native player + media session so it's ready by the time the user taps a song.
 *
 * Wrapped in try/catch: when the app is reopened (or Fast-Refreshes) while the
 * native player is still alive, `setupPlayer()` throws "already set up". That's
 * fine — the engine is ready, so we swallow it and carry on.
 */
export function setupPlayer() {
  if (isSetup) {
    return;
  }

  try {
    TrackPlayer.setupPlayer({
      contentType: 'music',
      handleAudioBecomingNoisy: true,
      android: {
        wakeMode: 'network', // streaming test; use 'local' for on-device files
        taskRemovedBehavior: 'continue',
        notification: {
          channelId: 'mellow_playback',
          channelName: 'Playback',
          smallIcon: 'ic_notification',
        },
      },
    });
  } catch (e) {
    console.warn('[player] setupPlayer (already initialised?)', e);
  }

  try {
    TrackPlayer.setCommands({
      capabilities: [
        PlayerCommand.PlayPause,
        PlayerCommand.Next,
        PlayerCommand.Previous,
        PlayerCommand.Seek,
        PlayerCommand.Stop,
      ],
    });
  } catch (e) {
    console.warn('[player] setCommands', e);
  }

  isSetup = true;
}

const toMediaItem = (s: Song): MediaItem => ({
  mediaId: s.id,
  url: s.url,
  title: s.title,
  artist: s.artist,
  artworkUrl: s.artwork,
});

// Snapshot of the last queue we set, so we can rebuild it if Android tears the
// native player down (see `resumePlayback`). Kept in JS memory — survives a
// notification dismiss while the app is open, which is the case that bites us.
let lastQueue: MediaItem[] = [];
let lastIndex = 0;

function setQueueAndPlay(items: MediaItem[], index: number) {
  lastQueue = items;
  lastIndex = index;
  TrackPlayer.setMediaItems(items, index);
  TrackPlayer.play();
}

/**
 * Play the tapped song and queue everything after it.
 *
 * `setMediaItems(items, startIndex)` makes the WHOLE list the queue and starts
 * at the tapped index — so the song plays and the following ones are queued as
 * "next". Single call, no retry: the user tapping always happens long after the
 * native controller has connected.
 */
export function playFromList(songs: Song[], index: number) {
  if (songs.length === 0) {
    return;
  }
  setQueueAndPlay(songs.map(toMediaItem), index);
}

/**
 * Queue the list in a random order and start from the top. We shuffle the array
 * ourselves (Fisher–Yates) rather than rely on a player "shuffle mode", so the
 * visible queue order and what actually plays stay in sync.
 */
export function playShuffled(songs: Song[]) {
  if (songs.length === 0) {
    return;
  }
  const shuffled = [...songs];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  setQueueAndPlay(shuffled.map(toMediaItem), 0);
}

/**
 * Resilient resume. Swiping away the Android media notification stops Media3's
 * service and can leave the native queue empty, which freezes the in-app
 * controls (RNTP issue #1226). If the queue is gone but we still remember the
 * last one, rebuild it before playing — so "play" always brings the music back.
 * When the queue is intact this is just `play()` and resumes at the exact spot.
 */
export function resumePlayback() {
  let queue: MediaItem[] = [];
  try {
    queue = TrackPlayer.getQueue();
  } catch (e) {
    console.warn('[player] getQueue', e);
  }

  if (queue.length === 0 && lastQueue.length > 0) {
    try {
      TrackPlayer.setMediaItems(lastQueue, lastIndex);
    } catch (e) {
      console.warn('[player] revive queue', e);
    }
  }

  try {
    TrackPlayer.play();
  } catch (e) {
    console.warn('[player] play', e);
  }
}
