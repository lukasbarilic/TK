import { createTauriPlatform } from './tauri';

const invoke = vi.hoisted(() => vi.fn());
const listeners = vi.hoisted(() => new Map<string, (e: { payload: unknown }) => void>());

vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn(async (name: string, cb: (e: { payload: unknown }) => void) => {
    listeners.set(name, cb);
    return () => listeners.delete(name);
  }),
}));

beforeEach(() => {
  invoke.mockReset();
  listeners.clear();
});

describe('tauri platforma', () => {
  it('http volá príkaz http_request a mapuje bajty', async () => {
    invoke.mockResolvedValue({ status: 200, headers: { a: 'b' }, bytes: [1, 2, 3] });
    const p = createTauriPlatform();
    const res = await p.http({ method: 'GET', path: '/api/x', accept: 'bytes' });
    expect(invoke).toHaveBeenCalledWith('http_request', {
      request: { method: 'GET', path: '/api/x', accept: 'bytes' },
    });
    expect(res.bytes).toEqual(new Uint8Array([1, 2, 3]));
  });

  it('http prepošle NetError z jadra', async () => {
    invoke.mockRejectedValue({ kind: 'tls', message: 'x' });
    await expect(createTauriPlatform().http({ method: 'GET', path: '/api/me' })).rejects.toEqual({
      kind: 'tls',
      message: 'x',
    });
  });

  it('config a app volajú príkazy', async () => {
    invoke.mockImplementation(async (cmd: string) => (cmd === 'config_get' ? null : 'v'));
    const p = createTauriPlatform();
    expect(await p.config.serverUrl()).toBeNull();
    await p.config.setServerUrl('https://s.example.invalid');
    expect(invoke).toHaveBeenCalledWith('config_set_server_url', { url: 'https://s.example.invalid' });
    await p.config.deviceId();
    expect(invoke).toHaveBeenCalledWith('config_device_id');
    expect(await p.app.version()).toBe('v');
    expect(invoke).toHaveBeenCalledWith('app_version');
  });

  it('live prepošle udalosti a stavy a pri zatvorení odpojí', async () => {
    invoke.mockResolvedValue(undefined);
    const events: unknown[] = [];
    const states: string[] = [];
    const close = createTauriPlatform().live.connect((e) => events.push(e), (s) => states.push(s));
    await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith('live_connect'));
    listeners.get('live://event')!({ payload: { type: 'heartbeat', data: {} } });
    listeners.get('live://state')!({ payload: { state: 'open' } });
    expect(events).toEqual([{ type: 'heartbeat', data: {} }]);
    expect(states).toEqual(['open']);
    close();
    expect(states.at(-1)).toBe('closed');
    await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith('live_disconnect'));
  });

  it('trezor volá príkazy vault_* s camelCase argumentmi', async () => {
    invoke.mockResolvedValue(undefined);
    const { vault } = createTauriPlatform();
    await vault.create('jana', 'h');
    await vault.rewrap('a', 'b');
    await vault.put('ds', 'k', { x: 1 });
    await vault.remove('ds', ['k']);
    await vault.wipe('jana');
    await vault.setSyncState('2026-11-18T10:00:00Z', 7);
    await vault.lock();
    expect(invoke.mock.calls).toEqual([
      ['vault_create', { username: 'jana', password: 'h' }],
      ['vault_rewrap', { oldPassword: 'a', newPassword: 'b' }],
      ['vault_put', { dataset: 'ds', key: 'k', value: { x: 1 }, meta: null }],
      ['vault_remove', { dataset: 'ds', keys: ['k'] }],
      ['vault_wipe', { username: 'jana' }],
      ['vault_set_sync_state', { lastFullSyncAt: '2026-11-18T10:00:00Z', maxAgeDays: 7 }],
      ['vault_lock', undefined],
    ]);
  });

  it('unlock vráti výsledok a chyby jadra sú VaultError', async () => {
    const { vault } = createTauriPlatform();
    invoke.mockResolvedValueOnce('wrong-password');
    expect(await vault.unlock('jana', 'x')).toBe('wrong-password');
    invoke.mockRejectedValueOnce({ kind: 'throttled', message: 'moc pokusov', until: '2026-11-18T10:00:30Z' });
    await expect(vault.unlock('jana', 'x')).rejects.toMatchObject({
      name: 'VaultError',
      kind: 'throttled',
      until: '2026-11-18T10:00:30Z',
    });
    invoke.mockRejectedValueOnce({ kind: 'locked', message: 'zamknuté' });
    await expect(vault.get('ds', 'k')).rejects.toMatchObject({ kind: 'locked' });
  });

  it('info zamknutého trezoru je null', async () => {
    const { vault } = createTauriPlatform();
    invoke.mockRejectedValueOnce({ kind: 'locked', message: 'x' });
    expect(await vault.info()).toBeNull();
    invoke.mockResolvedValueOnce({ username: 'jana', sizeBytes: 5 });
    expect(await vault.info()).toEqual({ username: 'jana', sizeBytes: 5 });
  });
});
