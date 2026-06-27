/**
 * @format
 */

import {AppRegistry} from 'react-native';
import TrackPlayer from '@rntp/player';
import App from './App';
import {name as appName} from './app.json';
import {playbackBackgroundHandler} from './src/player/service';

// Must be registered before the app component so Android notification /
// lock-screen actions still reach JS while the app UI is backgrounded.
TrackPlayer.registerBackgroundEventHandler(() => playbackBackgroundHandler);

AppRegistry.registerComponent(appName, () => App);
