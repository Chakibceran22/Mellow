/**
 * Mock library data for UI development. Stands in for what the MusicLibrary
 * TurboModule will return from MediaStore later.
 *
 * - `url`     → audio stream (SoundHelix for now; content:// from MediaStore later)
 * - `artwork` → cover image (picsum placeholder for now; album art URI later)
 */
export type LibrarySong = {
  id: string;
  title: string;
  artist: string;
  album: string;
  durationSec: number;
  url: string;
  artwork: string;
};

const AUDIO = (n: number) =>
  `https://www.soundhelix.com/examples/mp3/SoundHelix-Song-${n}.mp3`;

/** One stable cover per album, so songs from the same album share art (realistic). */
const ALBUM_ART: Record<string, string> = {
  'Quiet Hours': 'https://picsum.photos/seed/mellow-quiet/300/300',
  Driftwood: 'https://picsum.photos/seed/mellow-drift/300/300',
  'Low Light': 'https://picsum.photos/seed/mellow-lowlight/300/300',
  Afterglow: 'https://picsum.photos/seed/mellow-afterglow/300/300',
};

type RawSong = Omit<LibrarySong, 'url' | 'artwork'> & {audio: number};

const RAW: RawSong[] = [
  {id: '1', title: 'Slow Tide', artist: 'Halcyon Bloom', album: 'Quiet Hours', durationSec: 214, audio: 1},
  {id: '2', title: 'Paper Moons', artist: 'Lake & Linden', album: 'Driftwood', durationSec: 187, audio: 2},
  {id: '3', title: 'Soft Static', artist: 'Marrow', album: 'Low Light', durationSec: 241, audio: 3},
  {id: '4', title: 'Cedar Smoke', artist: 'June Aviary', album: 'Quiet Hours', durationSec: 263, audio: 4},
  {id: '5', title: 'Glass Garden', artist: 'Halcyon Bloom', album: 'Driftwood', durationSec: 198, audio: 5},
  {id: '6', title: 'Velvet Hours', artist: 'Pale Coast', album: 'Afterglow', durationSec: 225, audio: 6},
  {id: '7', title: 'Morning Pour', artist: 'Lake & Linden', album: 'Low Light', durationSec: 176, audio: 7},
  {id: '8', title: 'Dust & Gold', artist: 'Marrow', album: 'Afterglow', durationSec: 252, audio: 8},
  {id: '9', title: 'Lantern', artist: 'June Aviary', album: 'Driftwood', durationSec: 209, audio: 9},
  {id: '10', title: 'Wading', artist: 'Pale Coast', album: 'Quiet Hours', durationSec: 233, audio: 10},
  {id: '11', title: 'Thread of Blue', artist: 'Halcyon Bloom', album: 'Afterglow', durationSec: 191, audio: 11},
  {id: '12', title: 'Olive Rain', artist: 'Marrow', album: 'Low Light', durationSec: 278, audio: 12},
  {id: '13', title: 'Quiet Type', artist: 'Lake & Linden', album: 'Driftwood', durationSec: 204, audio: 13},
  {id: '14', title: 'Holding Light', artist: 'June Aviary', album: 'Quiet Hours', durationSec: 246, audio: 14},
  {id: '15', title: 'Far Shore', artist: 'Pale Coast', album: 'Afterglow', durationSec: 219, audio: 15},
  {id: '16', title: 'Sea Glass', artist: 'Halcyon Bloom', album: 'Low Light', durationSec: 182, audio: 16},
  {id: '17', title: 'Evening Fold', artist: 'Marrow', album: 'Quiet Hours', durationSec: 257, audio: 1},
  {id: '18', title: 'Soft Landing', artist: 'June Aviary', album: 'Afterglow', durationSec: 230, audio: 2},
];

export const MOCK_SONGS: LibrarySong[] = RAW.map(({audio, ...s}) => ({
  ...s,
  url: AUDIO(audio),
  artwork: ALBUM_ART[s.album],
}));

/** Seconds → `m:ss`. Clamps junk values (negative / NaN during track init) to 0. */
export const formatDuration = (sec: number): string => {
  const safe = Number.isFinite(sec) && sec > 0 ? sec : 0;
  const m = Math.floor(safe / 60);
  const s = Math.floor(safe % 60);
  return `${m}:${s.toString().padStart(2, '0')}`;
};
