import { Tooltip } from '@mantine/core';
import { cloneElement, type ReactElement } from 'react';
import { useTranslation } from 'react-i18next';
import { useConnection } from './useConnection';

/**
 * Obalí každé tlačidlo/akciu, ktorá zapisuje na server. Bez spojenia je neaktívne s vysvetlením
 * „Nedostupné bez spojenia so serverom“ (DLD kap. 6).
 */
export function WriteAction({ children }: { children: ReactElement<{ disabled?: boolean }> }) {
  const { t } = useTranslation();
  const { canWrite } = useConnection();
  if (canWrite) return children;
  return (
    <Tooltip label={t('offline.writeDisabled')}>
      <span style={{ display: 'inline-block' }} data-testid="write-disabled">
        {cloneElement(children, { disabled: true })}
      </span>
    </Tooltip>
  );
}
