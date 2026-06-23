/**
 * Mellow — test harness for the Paper UI + Phosphor icons + @rntp/player.
 * Nothing is loaded at startup; tapping a song plays it and queues the rest.
 *
 * @format
 */

import {StatusBar} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {MD3DarkTheme, PaperProvider} from 'react-native-paper';
import TestPlayerScreen from './src/screens/TestPlayerScreen';

const theme = {
  ...MD3DarkTheme,
  colors: {
    ...MD3DarkTheme.colors,
    primary: '#89BEA4',
    secondary: '#649288',
    background: '#0f1110',
  },
};

function App() {
  return (
    <SafeAreaProvider>
      <PaperProvider theme={theme}>
        <StatusBar barStyle="light-content" backgroundColor={theme.colors.background} />
        <SafeAreaView style={{flex: 1, backgroundColor: theme.colors.background}}>
          <TestPlayerScreen />
        </SafeAreaView>
      </PaperProvider>
    </SafeAreaProvider>
  );
}

export default App;
