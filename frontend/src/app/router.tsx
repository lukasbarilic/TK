import type { ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import { AuthGate, ForbiddenPage, RequirePermission } from '../features/auth';
import { ComingSoon } from './ComingSoon';
import { HomeRedirect } from './HomeRedirect';
import { AppShell } from './layout/AppShell';
import { MENU } from './menu';
import { PlanPlaceholder } from './PlanPlaceholder';

const pageFor = (path: string, labelKey: string): ReactNode =>
  path === '/plan' ? <PlanPlaceholder /> : <ComingSoon titleKey={labelKey} />;

export const routes: RouteObject[] = [
  {
    path: '/',
    element: (
      <AuthGate>
        <AppShell />
      </AuthGate>
    ),
    children: [
      { index: true, element: <HomeRedirect /> },
      ...MENU.map((m) => ({
        path: m.path.slice(1),
        element: <RequirePermission code={m.permission}>{pageFor(m.path, m.labelKey)}</RequirePermission>,
      })),
      { path: '*', element: <ForbiddenPage /> },
    ],
  },
];
