import { apiCall } from '../../shared/api/client';
import type { components } from '../../shared/api/schema';
import type { MoveCmd } from './moves';

type Plan = components['schemas']['Plan'];

/** Odpoveď zmeny: dotknuté izby (aktualizované pásiky) a `X-Audit-Event-Id` vlastnej udalosti. */
export interface ChangeResult {
  plan: Plan;
  auditEventId?: number;
}

const result = (res: { data: Plan; headers: Record<string, string> }): ChangeResult => {
  const raw = res.headers['x-audit-event-id'];
  const id = Number(raw);
  return { plan: res.data, auditEventId: raw && Number.isFinite(id) ? id : undefined };
};

export const moveStay = async (cmd: MoveCmd, confirm: string[]): Promise<ChangeResult> =>
  result(
    await apiCall('post', '/api/stays/{id}/move', {
      path: { id: cmd.stayId },
      body: { targetBedId: cmd.targetBedId, fromDate: cmd.fromDate, confirm: confirm.length ? confirm : undefined },
    }),
  );

export const swapStays = async (stayIdA: number, stayIdB: number, confirm: string[]): Promise<ChangeResult> =>
  result(await apiCall('post', '/api/stays/swap', { body: { stayIdA, stayIdB, confirm: confirm.length ? confirm : undefined } }));

export const moveBatch = async (cmds: MoveCmd[], confirm: string[]): Promise<ChangeResult> =>
  result(
    await apiCall('post', '/api/stays/moves:batch', {
      query: confirm.length ? { confirm } : {},
      body: cmds.map((c) => ({ stayId: c.stayId, targetBedId: c.targetBedId, fromDate: c.fromDate })),
    }),
  );

/** Zmena konca pobytu; `PATCH` vyžaduje `version`, ktorú pásik v pláne nemá, preto sa načíta z pobytov karty. */
export async function resizeStay(reservationId: number, stayId: number, to: string, confirm: string[]): Promise<ChangeResult> {
  const stays = (await apiCall('get', '/api/reservations/{id}/stays', { path: { id: reservationId } })).data;
  const version = stays.find((s) => s.id === stayId)?.version;
  if (version === undefined) throw new Error(`Pobyt ${stayId} sa na karte nenašiel`);
  return result(
    await apiCall('patch', '/api/stays/{id}', {
      path: { id: stayId },
      body: { to, version, confirm: confirm.length ? confirm : undefined },
    }),
  );
}

/** Zrušenie budúceho ubytovania; odpoveď je 204 bez tela, dotknuté izby preto dopĺňa volajúci. */
export async function cancelStay(stayId: number): Promise<{ auditEventId?: number }> {
  const res = await apiCall('delete', '/api/stays/{id}', { path: { id: stayId } });
  const raw = res.headers['x-audit-event-id'];
  const id = Number(raw);
  return { auditEventId: raw && Number.isFinite(id) ? id : undefined };
}
