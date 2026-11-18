import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { connection } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { platform } from '../../shared/platform';
import { server } from '../../shared/test/server';
import { useLogoutAndWipe } from './hooks';

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>
);

async function seedJournal() {
  await platform.vault.create('recepcna.demo', 'h');
  await platform.vault.put(
    DS.journal,
    'u1',
    { uid: 'u1', occurredAt: '2026-11-18T10:00:00Z', username: 'recepcna.demo', deviceId: 'd', operation: 'PLAN_VIEW', dataScope: 'NAME', clientIds: [1], connection: 'OFFLINE' },
    { syncedAt: '2026-11-18T10:00:00Z' },
  );
}

describe('useLogoutAndWipe', () => {
  afterEach(() => connection.reset('online'));

  it('pred vymazaním odošle denník, ak je spojenie', async () => {
    await seedJournal();
    connection.reset('online');
    const calls: string[] = [];
    server.use(
      http.post('/api/audit/offline-reads', () => (calls.push('journal'), HttpResponse.json({ accepted: 1 }))),
      http.post('/api/auth/logout', () => (calls.push('logout'), new HttpResponse(null, { status: 204 }))),
    );
    const { result } = renderHook(() => useLogoutAndWipe(), { wrapper });
    await act(async () => result.current.mutate('recepcna.demo'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(calls[0]).toBe('journal');
    expect(await platform.vault.exists('recepcna.demo')).toBe(false);
  });

  it('bez spojenia denník neodosiela a vymaže len trezor', async () => {
    await seedJournal();
    connection.reset('offline');
    let posted = false;
    server.use(http.post('/api/audit/offline-reads', () => ((posted = true), HttpResponse.json({}))));
    const { result } = renderHook(() => useLogoutAndWipe(), { wrapper });
    await act(async () => result.current.mutate('recepcna.demo'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(posted).toBe(false);
  });
});
