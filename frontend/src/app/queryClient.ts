import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError } from '../shared/api/errors';
import { queryKeys } from '../shared/api/queryKeys';
import { markSessionExpired } from '../features/auth';

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: { skipSessionExpired?: boolean };
  }
}

const isUnauthorized = (e: unknown) => e instanceof ApiError && e.status === 401;

/** 401 z ktorejkoľvek požiadavky = relácia vypršala (okrem prihlasovania a úvodného GET /api/me). */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        const startupMe = query.queryKey[0] === queryKeys.me[0] && query.state.data == null;
        if (isUnauthorized(error) && !startupMe) markSessionExpired();
      },
    }),
    mutationCache: new MutationCache({
      onError: (error, _vars, _ctx, mutation) => {
        if (isUnauthorized(error) && !mutation.meta?.skipSessionExpired) markSessionExpired();
      },
    }),
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
}
