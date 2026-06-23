module.exports = {
  presets: ['module:@react-native/babel-preset'],
  // Reanimated 4 uses react-native-worklets; its babel plugin MUST be the last entry.
  plugins: ['react-native-worklets/plugin'],
};
