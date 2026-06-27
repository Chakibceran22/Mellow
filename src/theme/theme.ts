import {MD3LightTheme} from 'react-native-paper';

/**
 * Mellow brand palette — pastel greens. This is the ONLY source of color in the
 * app. Nothing should hard-code a hex outside this file; pull from `palette`.
 */
export const palette = {
  mint: '#E6F1E0', // lightest — app background
  sage: '#B1D3BA', // light green
  green: '#89BEA4', // medium green
  deep: '#649288', // deep green — primary accent

  // Neutrals derived from the brand hue (shades of the same green), not arbitrary
  // greys — keeps the whole UI homogeneous.
  ink: '#23332D', // primary text
  inkSoft: '#5C6F66', // secondary text / icons
  hairline: '#DDE9DC', // very soft separators / ripples
  surface: '#FFFFFF', // cards / rows
  surfaceAlt: '#F1F7EF', // search field / chips
  tint: '#D9EAE0', // soft green wash for the active/selected row

  // Destructive actions — a muted terracotta that still belongs to the pastel
  // set (no harsh pure red).
  clay: '#C26B5E',

  // Player sheet — a translucent green panel with dark text (cozy, not white).
  glass: 'rgba(137, 190, 164, 0.95)', // == green @ 95% opacity
  onGreenSoft: 'rgba(35, 51, 45, 0.62)', // muted dark text/icons on the green sheet
  onGreenFaint: 'rgba(35, 51, 45, 0.15)', // hairlines/tracks on the green sheet
};

/** Diagonal gradient pairs for generated cover art — all drawn from the palette. */
export const coverGradients: [string, string][] = [
  [palette.green, palette.deep],
  [palette.sage, palette.green],
  [palette.deep, palette.green],
  [palette.sage, palette.deep],
];

/** Material You (MD3) light theme wired to the Mellow palette. */
export const paperTheme = {
  ...MD3LightTheme,
  roundness: 4,
  colors: {
    ...MD3LightTheme.colors,
    primary: palette.deep,
    onPrimary: '#FFFFFF',
    secondary: palette.green,
    onSecondary: '#FFFFFF',
    background: palette.mint,
    onBackground: palette.ink,
    surface: palette.surface,
    onSurface: palette.ink,
    surfaceVariant: palette.surfaceAlt,
    onSurfaceVariant: palette.inkSoft,
    outlineVariant: palette.hairline,
    elevation: {
      ...MD3LightTheme.colors.elevation,
      level0: 'transparent',
      level1: palette.surface,
      level2: palette.surface,
      level3: palette.surface,
    },
  },
};
