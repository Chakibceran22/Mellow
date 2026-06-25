/**
 * Mellow — Songs library screen (UI dev phase).
 * Single page, mocked data; playback wired, navigation not yet.
 *
 * @format
 */

import {StatusBar} from 'react-native';
import {GestureHandlerRootView} from 'react-native-gesture-handler';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {PaperProvider} from 'react-native-paper';
import LibraryScreen from './src/screens/LibraryScreen';
import {paperTheme, palette} from './src/theme/theme';

function App() {
  return (
    <GestureHandlerRootView style={{flex: 1}}>
      <SafeAreaProvider>
        <PaperProvider theme={paperTheme}>
          <StatusBar barStyle="dark-content" backgroundColor={palette.mint} />
          <SafeAreaView style={{flex: 1, backgroundColor: palette.mint}}>
            <LibraryScreen />
          </SafeAreaView>
        </PaperProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

export default App;
