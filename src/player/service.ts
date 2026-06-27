import TrackPlayer, {Event, type BackgroundEvent} from '@rntp/player';

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
 * Transport commands use native handling (see `setCommands`), so this mostly
 * needs to EXIST; we still forward the basic remote events defensively.
 */
export async function playbackBackgroundHandler(
  event: BackgroundEvent,
): Promise<void> {
  switch (event.type) {
    case Event.RemotePlay:
      TrackPlayer.play();
      break;
    case Event.RemotePause:
    case Event.RemoteStop:
      TrackPlayer.pause();
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
