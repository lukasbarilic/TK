import { ApiError } from '../../shared/api/errors';

/** Chyby polí z `fieldViolations` podľa názvu poľa (prvá správa na pole). */
export function violationMap(e: unknown): Record<string, string> {
  const out: Record<string, string> = {};
  if (e instanceof ApiError) for (const v of e.fieldViolations) out[v.field] ??= v.message;
  return out;
}
