import { act, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { connection } from '../../shared/offline';
import { platform } from '../../shared/platform';
import { server } from '../../shared/test/server';
import { renderWithProviders } from '../../shared/test/render';
import { UpdateRuntime } from './UpdateRuntime';

function setup(opts: { version: string; min?: string; update?: { version: string } | null }) {
  const configCalls = vi.fn();
  server.use(
    http.get('/api/client-config', () => {
      configCalls();
      return HttpResponse.json({ minClientVersion: opts.min });
    }),
  );
  vi.spyOn(platform.app, 'version').mockResolvedValue(opts.version);
  const check = vi.spyOn(platform.app, 'checkUpdate').mockResolvedValue(opts.update ?? null);
  const install = vi.spyOn(platform.app, 'installUpdate').mockResolvedValue();
  return { configCalls, check, install };
}

afterEach(() => {
  vi.restoreAllMocks();
  connection.reset('online');
});

describe('UpdateRuntime', () => {
  it('nižšia verzia než minClientVersion → blokujúca obrazovka a inštalácia', async () => {
    const { install } = setup({ version: '0.1.0', min: '0.2.0', update: { version: '0.2.0' } });
    renderWithProviders(<UpdateRuntime />);
    expect(await screen.findByText('Je potrebná aktualizácia')).toBeInTheDocument();
    act(() => screen.getByRole('button', { name: 'Aktualizovať' }).click());
    await waitFor(() => expect(install).toHaveBeenCalled());
  });

  it('dostatočná verzia a nová verzia na serveri → len upozornenie', async () => {
    setup({ version: '0.2.0', min: '0.2.0', update: { version: '0.3.0' } });
    renderWithProviders(<UpdateRuntime />);
    expect(await screen.findByTestId('update-available')).toHaveTextContent('Je k dispozícii nová verzia');
    expect(screen.queryByTestId('update-required')).toBeNull();
  });

  it('offline sa nekontroluje a nič sa nezobrazuje', async () => {
    connection.reset('offline');
    const { configCalls, check } = setup({ version: '0.1.0', min: '0.2.0', update: { version: '0.2.0' } });
    renderWithProviders(<UpdateRuntime />);
    await new Promise((r) => setTimeout(r, 50));
    expect(configCalls).not.toHaveBeenCalled();
    expect(check).not.toHaveBeenCalled();
    expect(screen.queryByTestId('update-required')).toBeNull();
  });
});
