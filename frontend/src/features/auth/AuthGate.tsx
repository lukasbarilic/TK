import { Center, Loader } from '@mantine/core';
import type { ReactNode } from 'react';
import { ChangePasswordPage } from './ChangePasswordPage';
import { useConnection } from '../../shared/offline';
import { useMe } from './hooks';
import { LockScreen } from './LockScreen';
import { LoginPage } from './LoginPage';
import { SessionExpiredDialog } from './SessionExpiredDialog';

/** Pustí ďalej len prihláseného používateľa, ktorý nemusí meniť heslo. */
export function AuthGate({ children }: { children: ReactNode }) {
  const me = useMe();
  const { state } = useConnection();
  if (!me.data) {
    return me.isPending ? (
      <Center mih="100vh"><Loader /></Center>
    ) : (
      <LoginPage />
    );
  }
  if (me.data.mustChangePassword) return <ChangePasswordPage />;
  if (state === 'locked') return <LockScreen username={me.data.username ?? ''} />;
  return (
    <>
      {children}
      <SessionExpiredDialog />
    </>
  );
}
