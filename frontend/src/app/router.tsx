import type { ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import { AuthGate, ForbiddenPage, RequirePermission } from '../features/auth';
import { ComingSoon } from './ComingSoon';
import { HomeRedirect } from './HomeRedirect';
import { AppShell } from './layout/AppShell';
import { MENU } from './menu';
import { BusinessDayRoute } from './BusinessDayRoute';
import { GuestListRoute } from './GuestListRoute';
import { MovementsRoute } from './MovementsRoute';
import { PlanRoute } from './PlanRoute';
import { NewReservationRoute } from './NewReservationRoute';
import { ReservationDetailRoute } from './ReservationRoutes';
import { ReservationsRoute } from './ReservationsRoute';
import { UsersRoute } from './UsersRoute';
import { AuditRoute } from './AuditRoute';

const pageFor = (path: string, labelKey: string): ReactNode =>
  path === '/plan' ? <PlanRoute /> : path === '/reservations' ? <ReservationsRoute /> : path === '/movements' ? <MovementsRoute /> : path === '/business-day' ? <BusinessDayRoute /> : path === '/guests' ? <GuestListRoute /> : path === '/admin/users' ? <UsersRoute /> : path === '/audit' ? <AuditRoute /> : <ComingSoon titleKey={labelKey} />;

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
      {
        path: 'reservations/new',
        element: (
          <RequirePermission code="RESERVATION_EDIT">
            <NewReservationRoute />
          </RequirePermission>
        ),
      },
      {
        path: 'reservations/:id',
        element: (
          <RequirePermission code="RESERVATION_VIEW">
            <ReservationDetailRoute />
          </RequirePermission>
        ),
      },
      { path: '*', element: <ForbiddenPage /> },
    ],
  },
];
