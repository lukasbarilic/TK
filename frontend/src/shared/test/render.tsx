import { MantineProvider } from '@mantine/core';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import '../i18n';
import { theme } from '../ui/theme';

export function renderWithProviders(ui: ReactElement) {
  return render(
    <MantineProvider theme={theme}>
      {ui}
    </MantineProvider>,
  );
}
