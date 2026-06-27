import {useEffect, useState} from 'react';
import {AppState} from 'react-native';
import TrackPlayer, {
  Event,
  type MediaItem,
  useActiveMediaItem,
} from '@rntp/player';

function readCurrentMediaItem(): MediaItem | null {
  try {
    return TrackPlayer.getQueue().length > 0
      ? TrackPlayer.getActiveMediaItem()
      : null;
  } catch (e) {
    console.warn('[player] read current media item', e);
    return null;
  }
}

/**
 * Like RNTP's useActiveMediaItem, but treats an empty queue as no visible
 * player. Android clear() emits QueueChanged, so listening for it keeps the
 * mini player in sync when the notification stop/dismiss action clears queue.
 */
export function useCurrentMediaItem(): MediaItem | null {
  const active = useActiveMediaItem();
  const [item, setItem] = useState<MediaItem | null>(() =>
    readCurrentMediaItem(),
  );

  useEffect(() => {
    try {
      setItem(TrackPlayer.getQueue().length > 0 ? active : null);
    } catch (e) {
      console.warn('[player] sync active media item', e);
      setItem(null);
    }
  }, [active]);

  useEffect(() => {
    const refresh = () => setItem(readCurrentMediaItem());
    const queueSub = TrackPlayer.addEventListener(Event.QueueChanged, refresh);
    const stopSub = TrackPlayer.addEventListener(Event.RemoteStop, () => {
      setItem(null);
    });
    const appStateSub = AppState.addEventListener('change', status => {
      if (status === 'active') {
        refresh();
      }
    });

    return () => {
      queueSub.remove();
      stopSub.remove();
      appStateSub.remove();
    };
  }, []);

  return item;
}
