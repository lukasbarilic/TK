import { Navigate } from 'react-router-dom';
import { ForbiddenPage, useMe } from '../features/auth';
import { homePath } from './menu';

export function HomeRedirect() {
  const path = homePath(useMe().data);
  return path ? <Navigate to={path} replace /> : <ForbiddenPage />;
}
