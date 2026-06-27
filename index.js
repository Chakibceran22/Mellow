/**
 * @format
 */

import {AppRegistry} from 'react-native';
import TrackPlayer from '@rntp/player';
import App from './App';
import {name as appName} from './app.json';
import {playbackBackgroundHandler} from './src/player/service';

// Must be registered before the app component so the Android playback service
// has its headless handler ready — this keeps the controls alive after the
// media notification is dismissed or the app is backgrounded.
TrackPlayer.registerBackgroundEventHandler(() => playbackBackgroundHandler);

AppRegistry.registerComponent(appName, () => App);
