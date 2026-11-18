export interface FieldViolation {
  field: string;
  message: string;
}

/** Telo chybovej odpovede (RFC 9457 ProblemDetail + rozšírenia, HLD-ubytovanie 6.3). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  confirmable?: boolean;
  warnings?: string[];
  fieldViolations?: FieldViolation[];
}

export class ApiError extends Error {
  status: number;
  code?: string;
  title?: string;
  detail?: string;
  confirmable: boolean;
  warnings: string[];
  fieldViolations: FieldViolation[];
  auditEventId?: string;

  constructor(status: number, problem: ProblemDetail = {}, auditEventId?: string) {
    super(problem.title ?? problem.detail ?? `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = problem.code;
    this.title = problem.title;
    this.detail = problem.detail;
    this.confirmable = problem.confirmable === true;
    this.warnings = problem.warnings ?? [];
    this.fieldViolations = problem.fieldViolations ?? [];
    this.auditEventId = auditEventId;
  }
}

const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined);

/** Bezpečne prečíta ProblemDetail z neznámeho JSON-u (neplatné polia ignoruje). */
export function parseProblemDetail(json: unknown): ProblemDetail {
  if (!isRecord(json)) return {};
  const violations = Array.isArray(json.fieldViolations)
    ? json.fieldViolations.flatMap((x): FieldViolation[] =>
        isRecord(x) && typeof x.field === 'string' && typeof x.message === 'string'
          ? [{ field: x.field, message: x.message }]
          : [],
      )
    : undefined;
  return {
    type: str(json.type),
    title: str(json.title),
    status: typeof json.status === 'number' ? json.status : undefined,
    detail: str(json.detail),
    code: str(json.code),
    confirmable: json.confirmable === true,
    warnings: Array.isArray(json.warnings)
      ? json.warnings.filter((w): w is string => typeof w === 'string')
      : undefined,
    fieldViolations: violations,
  };
}

/** Vytvorí ApiError z odpovede servera (status, hlavičky s malými písmenami, JSON telo). */
export function toApiError(
  status: number,
  json: unknown,
  headers: Record<string, string> = {},
): ApiError {
  return new ApiError(status, parseProblemDetail(json), headers['x-audit-event-id']);
}
