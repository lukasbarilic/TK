import { ApiError, parseProblemDetail, toApiError } from './errors';

describe('parseProblemDetail', () => {
  it('prečíta všetky polia vrátane confirmable, warnings a fieldViolations', () => {
    const p = parseProblemDetail({
      title: 'Konflikt',
      status: 409,
      detail: 'Lôžko je obsadené',
      code: 'BED_OCCUPIED',
      confirmable: true,
      warnings: ['RELEASES_HELD_BED', 'OTHER'],
      fieldViolations: [{ field: 'from', message: 'povinné' }],
    });
    expect(p).toMatchObject({
      title: 'Konflikt',
      status: 409,
      code: 'BED_OCCUPIED',
      confirmable: true,
      warnings: ['RELEASES_HELD_BED', 'OTHER'],
      fieldViolations: [{ field: 'from', message: 'povinné' }],
    });
  });

  it('ignoruje neplatné polia a netelesá vstupy', () => {
    expect(parseProblemDetail(null)).toEqual({});
    expect(parseProblemDetail('chyba')).toEqual({});
    expect(parseProblemDetail([1])).toEqual({});
    const p = parseProblemDetail({
      warnings: ['A', 3],
      fieldViolations: [{ field: 'x' }, { field: 'y', message: 'm' }, 7],
      confirmable: 'yes',
    });
    expect(p.warnings).toEqual(['A']);
    expect(p.fieldViolations).toEqual([{ field: 'y', message: 'm' }]);
    expect(p.confirmable).toBe(false);
  });
});

describe('ApiError', () => {
  it('má predvolené prázdne hodnoty', () => {
    const e = new ApiError(500);
    expect(e).toBeInstanceOf(Error);
    expect(e.confirmable).toBe(false);
    expect(e.warnings).toEqual([]);
    expect(e.fieldViolations).toEqual([]);
    expect(e.message).toBe('HTTP 500');
  });

  it('toApiError preberie X-Audit-Event-Id a údaje z ProblemDetail', () => {
    const e = toApiError(
      409,
      { title: 'Potvrďte', code: 'WARN', confirmable: true, warnings: ['RELEASES_HELD_BED'] },
      { 'x-audit-event-id': '42' },
    );
    expect(e.status).toBe(409);
    expect(e.code).toBe('WARN');
    expect(e.confirmable).toBe(true);
    expect(e.warnings).toEqual(['RELEASES_HELD_BED']);
    expect(e.auditEventId).toBe('42');
    expect(e.message).toBe('Potvrďte');
  });

  it('toApiError zvládne odpoveď bez tela', () => {
    const e = toApiError(403, undefined);
    expect(e.status).toBe(403);
    expect(e.code).toBeUndefined();
  });
});
