export type VaultErrorKind =
  | 'locked'
  | 'throttled'
  | 'wrong-password'
  | 'missing'
  | 'crypto'
  | 'storage';

/** Chyba trezora. `locked` = trezor nie je odomknutý; `throttled` nesie čas (`until`, ISO), dokedy nejde odomknúť. */
export class VaultError extends Error {
  kind: VaultErrorKind;
  until?: string;

  constructor(kind: VaultErrorKind, message: string, until?: string) {
    super(message);
    this.name = 'VaultError';
    this.kind = kind;
    this.until = until;
  }
}

export const isVaultError = (e: unknown): e is VaultError => e instanceof VaultError;

/** Prevedie chybu z natívneho jadra (`{ kind, message, until? }`) na `VaultError`. */
export function toVaultError(e: unknown): VaultError {
  if (e instanceof VaultError) return e;
  if (typeof e === 'object' && e !== null && 'kind' in e) {
    const o = e as { kind: unknown; message?: unknown; until?: unknown };
    return new VaultError(
      String(o.kind) as VaultErrorKind,
      typeof o.message === 'string' ? o.message : String(o.kind),
      typeof o.until === 'string' ? o.until : undefined,
    );
  }
  return new VaultError('storage', e instanceof Error ? e.message : String(e));
}
