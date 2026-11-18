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

  it('trezor zatiaľ nie je implementovaný (F04)', () => {
    expect(() => createTauriPlatform().vault.lock()).toThrow('nie je implementovaná');
  });
});
