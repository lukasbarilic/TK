import { Navigate, type RouteObject } from 'react-router-dom';
import { AppShell } from './layout/AppShell';
import { PlanPlaceholder } from './PlanPlaceholder';

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/plan" replace /> },
      { path: 'plan', element: <PlanPlaceholder /> },
    ],
  },
];
