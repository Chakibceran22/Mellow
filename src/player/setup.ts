import TrackPlayer, {
  Event,
  PlayerCommand,
  RepeatMode,
  type MediaItem,
} from '@rntp/player';

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
      handling: 'hybrid',
      perCommandHandling: {
        [PlayerCommand.Stop]: 'js',
      },
    });
  } catch (e) {
    console.warn('[player] setCommands', e);
  }

  try {
    TrackPlayer.addEventListener(Event.RemoteStop, dismissPlaybackSession);
  } catch (e) {
    console.warn('[player] remote stop listener', e);
  }

  keepQueueLooping();

  isSetup = true;
}

const toMediaItem = (s: Song): MediaItem => ({
  mediaId: s.id,
  url: s.url,
  title: s.title,
  artist: s.artist,
  artworkUrl: s.artwork,
});

function keepQueueLooping() {
  try {
    TrackPlayer.setRepeatMode(RepeatMode.All);
  } catch (e) {
    console.warn('[player] set repeat mode', e);
  }
}

function setQueueAndPlay(items: MediaItem[], index: number) {
  TrackPlayer.setMediaItems(items, index);
  keepQueueLooping();
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
 * User dismissed the system playback session (notification / lock-screen stop).
 * Clear the queue too, so app UI treats that as "nothing is loaded" instead of
 * keeping a stale mini player around.
 */
export function dismissPlaybackSession() {
  try {
    TrackPlayer.pause();
  } catch (e) {
    console.warn('[player] pause before dismiss', e);
  }

  try {
    TrackPlayer.clear();
  } catch (e) {
    console.warn('[player] clear dismissed session', e);
  }
}
