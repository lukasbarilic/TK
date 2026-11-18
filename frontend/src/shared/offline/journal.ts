import { apiCall } from '../api/client';
import type { components } from '../api/schema';
import { platform } from '../platform';
import type { Connection } from './connection';
import { DS } from './datasets';

/** Záznam denníka čítaní (DLD kap. 7); rovnaký tvar ako `OfflineRead` v B04. */
export type JournalRecord = components['schemas']['OfflineRead'];
export type JournalOperation = 'PLAN_VIEW' | 'RESERVATION_VIEW' | 'GUEST_LOCATION_VIEW' | 'MOVEMENTS_VIEW' | 'GUEST_LOCATION_EXPORT';

/** Čo sa zapíše pri zobrazení údajov z trezora; `clientIds` sa odvodí z dát, ktoré sa zobrazujú. */
export interface JournalSpec<T> {
  operation: JournalOperation;
  dataScope: JournalRecord['dataScope'];
  clientIds: (data: T) => number[];
}

export const DEDUP_WINDOW_MS = 5 * 60_000;
export const BATCH_SIZE = 500;
export const FLUSH_INTERVAL_MS = 60_000;

interface RecordDeps {
  vault: Pick<typeof platform.vault, 'put' | 'info'>;
  deviceId: () => Promise<string>;
  now: () => Date;
}
interface Deps extends Omit<RecordDeps, 'vault'> {
  vault: Pick<typeof platform.vault, 'put' | 'get' | 'list' | 'remove' | 'info'>;
}
const defaultDeps = (): Deps => ({ vault: platform.vault, deviceId: () => platform.config.deviceId(), now: () => new Date() });

/** Posledný zápis pohľadu (operácia + rozsah + klienti) – potláča duplicity v rámci 5 minút. Len v pamäti. */
const lastWritten = new Map<string, number>();
export const resetJournalDedup = (): void => lastWritten.clear();

/**
 * Zapíše zobrazenie osobných údajov z trezora (`OFFLINE` = bez servera, `CACHED` = `304`).
 * Ten istý pohľad s tými istými klientmi sa do 5 minút nezapisuje znova. Chyby trezora sa ignorujú.
 */
export async function recordRead<T>(
  spec: JournalSpec<T>,
  data: T,
  connection: JournalRecord['connection'],
  deps: RecordDeps = defaultDeps(),
): Promise<void> {
  try {
    const clientIds = [...new Set(spec.clientIds(data))].sort((a, b) => a - b);
    if (clientIds.length === 0) return;
    const username = (await deps.vault.info())?.username;
    if (!username) return;
    const now = deps.now();
    const dedupKey = [username, spec.operation, spec.dataScope, connection, clientIds.join(',')].join('|');
    const prev = lastWritten.get(dedupKey);
    if (prev !== undefined && now.getTime() - prev < DEDUP_WINDOW_MS) return;
    lastWritten.set(dedupKey, now.getTime());
    const uid = crypto.randomUUID();
    const record: JournalRecord = {
      uid,
      occurredAt: now.toISOString(),
      username,
      deviceId: await deps.deviceId(),
      operation: spec.operation,
      dataScope: spec.dataScope,
      clientIds,
      connection,
    };
    await deps.vault.put(DS.journal, uid, record, { syncedAt: record.occurredAt });
  } catch {
    // zamknutý trezor: denník sa nezapíše
  }
}

let flushing: Promise<void> | null = null;

/**
 * Odošle denník do `POST /api/audit/offline-reads` v dávkach po max. 500; po `2xx` záznamy zmaže z trezora.
 * Pri chybe (sieť, `5xx`) záznamy ostávajú a odošlú sa pri ďalšom pokuse. Súbežné volania sa spoja.
 */
export function flushJournal(deps: Deps = defaultDeps()): Promise<void> {
  if (flushing) return flushing;
  flushing = (async () => {
    try {
      const keys = await deps.vault.list(DS.journal);
      const entries: { key: string; record: JournalRecord }[] = [];
      for (const key of keys) {
        const e = await deps.vault.get<JournalRecord>(DS.journal, key);
        if (e) entries.push({ key, record: e.value });
      }
      entries.sort((a, b) => a.record.occurredAt.localeCompare(b.record.occurredAt));
      for (let i = 0; i < entries.length; i += BATCH_SIZE) {
        const batch = entries.slice(i, i + BATCH_SIZE);
        await apiCall('post', '/api/audit/offline-reads', { body: { records: batch.map((b) => b.record) } });
        await deps.vault.remove(DS.journal, batch.map((b) => b.key));
      }
    } catch {
      // ponechané v trezore, skúsi sa znova
    }
  })().finally(() => {
    flushing = null;
  });
  return flushing;
}

interface RuntimeOptions {
  connection: Pick<Connection, 'onTransition' | 'getState'>;
  flush?: () => Promise<void>;
  intervalMs?: number;
}

/** Odosiela denník po prechode do `online` a potom každú minútu, kým je `online`. Vracia funkciu na zastavenie. */
export function startJournalRuntime({ connection, flush = flushJournal, intervalMs = FLUSH_INTERVAL_MS }: RuntimeOptions): () => void {
  const run = () => {
    if (connection.getState() === 'online') void flush();
  };
  const unsub = connection.onTransition((to) => {
    if (to === 'online') run();
  });
  const timer = setInterval(run, intervalMs);
  run();
  return () => {
    unsub();
    clearInterval(timer);
  };
}

/** Klienti z karty rezervácie (hostia s `clientId`). */
export const cardClientIds = (d: { guests?: { clientId?: number }[] }): number[] =>
  (d.guests ?? []).flatMap((g) => (typeof g.clientId === 'number' ? [g.clientId] : []));

export const reservationViewSpec: JournalSpec<{ guests?: { clientId?: number }[] }> = {
  operation: 'RESERVATION_VIEW',
  dataScope: 'CONTACT',
  clientIds: cardClientIds,
};
