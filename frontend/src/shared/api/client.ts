import { platform } from '../platform';
import type { HttpResponse, Platform } from '../platform/types';
import { isNetError, toApiError } from './errors';
import type { paths } from './schema';

type HttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete';

type Operation<P extends keyof paths, M extends HttpMethod> = paths[P] extends Record<M, infer O>
  ? Exclude<O, undefined | never>
  : never;

type Content<T> = T extends { content: infer C } ? C[keyof C] : undefined;

/** Telo úspešnej odpovede (2xx) danej operácie. */
export type ResponseOf<P extends keyof paths, M extends HttpMethod> =
  Operation<P, M> extends { responses: infer R }
    ? Content<R[Extract<keyof R, 200 | 201 | 202>]>
    : never;

type BodyOf<O> = O extends { requestBody?: infer B }
  ? [Exclude<B, undefined>] extends [never]
    ? undefined
    : Exclude<B, undefined> extends { content: { 'application/json': infer J } }
      ? J
      : undefined
  : undefined;

type ParamsOf<O, K extends 'path' | 'query'> = O extends { parameters: infer P }
  ? K extends keyof P
    ? Exclude<P[K], undefined | never>
    : never
  : never;

type Optional<K extends string, V> = [V] extends [never] ? { [k in K]?: undefined } : { [k in K]: V };

export type CallOptions<P extends keyof paths, M extends HttpMethod> = Optional<
  'path',
  ParamsOf<Operation<P, M>, 'path'>
> &
  Optional<'query', ParamsOf<Operation<P, M>, 'query'>> &
  ([BodyOf<Operation<P, M>>] extends [undefined]
    ? { body?: undefined }
    : { body: BodyOf<Operation<P, M>> }) & {
    headers?: Record<string, string>;
    timeoutMs?: number;
  };

export interface ApiResult<T> {
  status: number;
  data: T;
  headers: Record<string, string>;
}

/** Pozorovateľ výsledkov požiadaviek (stav spojenia, DLD kap. 6): odpoveď servera = dostupný, sieťová chyba = výpadok. */
export interface ApiObserver {
  onResponse(): void;
  onNetworkError(): void;
}
let observer: ApiObserver | null = null;
export function setApiObserver(o: ApiObserver | null): void {
  observer = o;
}

function fillPath(template: string, params: Record<string, unknown> | undefined): string {
  return template.replace(/\{(\w+)\}/g, (_, name: string) => {
    const v = params?.[name];
    if (v === undefined) throw new Error(`Chýba parameter cesty: ${name}`);
    return encodeURIComponent(String(v));
  });
}

/**
 * Typované volanie servera nad `Platform.http`. Odpoveď mimo 2xx a 304 sa vyhodí ako `ApiError`;
 * sieťové chyby prechádzajú ako `NetError` z platformy.
 */
export async function apiCall<P extends keyof paths & string, M extends HttpMethod>(
  method: M,
  path: P,
  options?: CallOptions<P, M>,
  impl: Pick<Platform, 'http'> = platform,
): Promise<ApiResult<ResponseOf<P, M>>> {
  const o = (options ?? {}) as {
    path?: Record<string, unknown>;
    query?: Record<string, string | number | boolean | string[] | undefined>;
    body?: unknown;
    headers?: Record<string, string>;
    timeoutMs?: number;
  };
  let res: HttpResponse;
  try {
    res = await impl.http({
      method: method.toUpperCase() as 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
      path: fillPath(path, o.path),
      query: o.query,
      body: o.body,
      headers: o.headers,
      timeoutMs: o.timeoutMs,
    });
  } catch (e) {
    if (isNetError(e) && e.kind !== 'config') observer?.onNetworkError();
    throw e;
  }
  // 502–504 = server (za proxy) nedostupný, nie odpoveď aplikácie
  if (res.status >= 502 && res.status <= 504) observer?.onNetworkError();
  else observer?.onResponse();
  if ((res.status < 200 || res.status >= 300) && res.status !== 304) {
    throw toApiError(res.status, res.json, res.headers);
  }
  return { status: res.status, data: res.json as ResponseOf<P, M>, headers: res.headers };
}
