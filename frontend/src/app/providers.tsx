import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '../shared/i18n';
import { theme } from '../shared/ui/theme';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

export function Providers({ children }: { children: ReactNode }) {
  return (
    <MantineProvider theme={theme}>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </MantineProvider>
  );
}
