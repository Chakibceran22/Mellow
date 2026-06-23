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
 * Safe to call on screen mount; guarded against the "already set up" throw.
 */
export function setupPlayer() {
  if (isSetup) {
    return;
  }

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

  TrackPlayer.setCommands({
    capabilities: [
      PlayerCommand.PlayPause,
      PlayerCommand.Next,
      PlayerCommand.Previous,
      PlayerCommand.Seek,
      PlayerCommand.Stop,
    ],
  });

  isSetup = true;
}

const toMediaItem = (s: Song): MediaItem => ({
  mediaId: s.id,
  url: s.url,
  title: s.title,
  artist: s.artist,
  artworkUrl: s.artwork,
});

/**
 * Play the tapped song and queue everything after it.
 *
 * `setMediaItems(items, startIndex)` makes the WHOLE list the queue and starts
 * at the tapped index — so the song plays and the following ones are queued as
 * "next". Single call, no retry: the user tapping always happens long after the
 * native controller has connected.
 */
export function playFromList(songs: Song[], index: number) {
  TrackPlayer.setMediaItems(songs.map(toMediaItem), index);
  TrackPlayer.play();
}
