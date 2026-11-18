import { createTheme, type MantineColorsTuple } from '@mantine/core';

// Farby podľa docs/frontend/ui/README.md; základ 1E5FA8 je na indexe 7, tmavšia 154A85 na 9.
const brand: MantineColorsTuple = [
  '#EEF5FC', '#DCEBFA', '#CFE3F7', '#A9C9EC', '#7FAAD9',
  '#5B8FC9', '#2F70B8', '#1E5FA8', '#194F8D', '#154A85',
];

export const theme = createTheme({
  primaryColor: 'brand',
  primaryShade: 7,
  colors: { brand },
  fontFamily: 'Inter, system-ui, sans-serif',
  headings: { fontFamily: 'Inter, system-ui, sans-serif', fontWeight: '600' },
  defaultRadius: 'md',
  black: '#13243A',
  other: {
    appBackground: '#EEF5FC',
    sidebarBackground: '#DCEBFA',
    border: '#C9DCEF',
    textSecondary: '#4A5D75',
  },
});
