import TrackPlayer, {Event, type BackgroundEvent} from '@rntp/player';
import {dismissPlaybackSession} from './setup';

/**
 * Android headless background handler.
 *
 * Registered in index.js (before AppRegistry.registerComponent) via
 * `TrackPlayer.registerBackgroundEventHandler`. This wires the
 * `TrackPlayerServiceBridge` headless task so the Media3 playback service stays
 * valid when the app is backgrounded or the media notification is acted on —
 * without it, those service-side events have nowhere to go and the player can
 * be torn down (the "controls freeze after dismissing the notification" bug).
 *
 * Most transport commands use native handling (see `setCommands`). Stop is
 * routed through JS so dismissing the system session can clear app UI too.
 */
export async function playbackBackgroundHandler(
  event: BackgroundEvent,
): Promise<void> {
  switch (event.type) {
    case Event.RemotePlay:
      TrackPlayer.play();
      break;
    case Event.RemotePause:
      TrackPlayer.pause();
      break;
    case Event.RemoteStop:
      dismissPlaybackSession();
      break;
    case Event.RemoteNext:
      TrackPlayer.skipToNext();
      break;
    case Event.RemotePrevious:
      TrackPlayer.skipToPrevious();
      break;
    default:
      break;
  }
}
