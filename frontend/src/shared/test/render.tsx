import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import '../i18n';
import { theme } from '../ui/theme';

export function renderWithProviders(
  ui: ReactElement,
  client: QueryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  return render(
    <MantineProvider theme={theme} env="test">
      <QueryClientProvider client={client}>{ui}</QueryClientProvider>
    </MantineProvider>,
  );
}
