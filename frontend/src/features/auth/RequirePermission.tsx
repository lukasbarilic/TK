import type { ReactNode } from 'react';
import { ForbiddenPage } from './ForbiddenPage';
import { usePermission } from './hooks';

export function RequirePermission({ code, children }: { code: string; children: ReactNode }) {
  return usePermission(code) ? <>{children}</> : <ForbiddenPage />;
}
