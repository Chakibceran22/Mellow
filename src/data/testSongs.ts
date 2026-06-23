import type {Song} from '../player/setup';

const mp3 = (n: number) =>
  `https://www.soundhelix.com/examples/mp3/SoundHelix-Song-${n}.mp3`;

/** Stand-in for what MusicLibrary.getAllSongs() will return from MediaStore. */
export const TEST_SONGS: Song[] = [
  {id: '1', title: 'SoundHelix Song 1', artist: 'T. Schürger', url: mp3(1)},
  {id: '2', title: 'SoundHelix Song 2', artist: 'T. Schürger', url: mp3(2)},
  {id: '3', title: 'SoundHelix Song 3', artist: 'T. Schürger', url: mp3(3)},
  {id: '4', title: 'SoundHelix Song 4', artist: 'T. Schürger', url: mp3(4)},
  {id: '5', title: 'SoundHelix Song 5', artist: 'T. Schürger', url: mp3(5)},
  {id: '6', title: 'SoundHelix Song 6', artist: 'T. Schürger', url: mp3(6)},
  {id: '7', title: 'SoundHelix Song 7', artist: 'T. Schürger', url: mp3(7)},
  {id: '8', title: 'SoundHelix Song 8', artist: 'T. Schürger', url: mp3(8)},
];
