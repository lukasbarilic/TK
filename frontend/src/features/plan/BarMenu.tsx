import { Paper, UnstyledButton } from '@mantine/core';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

export type MenuAction = 'moveFrom' | 'swap' | 'cancel';

interface Props {
  x: number;
  y: number;
  /** Akcie, ktoré sa pre daný pásik ponúkajú. */
  actions: MenuAction[];
  onPick: (a: MenuAction) => void;
  onClose: () => void;
}

/** Kontextové menu pásika pobytu (DLD kap. 9.3): Presťahovať od…, Vymeniť s…, Zrušiť ubytovanie. */
export function BarMenu({ x, y, actions, onPick, onClose }: Props) {
  const { t } = useTranslation();
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <>
      <div style={{ position: 'fixed', inset: 0, zIndex: 300 }} onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose(); }} />
      <Paper role="menu" withBorder shadow="md" radius="md" p={4} data-testid="bar-menu" style={{ position: 'fixed', left: x, top: y, zIndex: 301, minWidth: 190 }}>
        {actions.map((a) => (
          <UnstyledButton key={a} role="menuitem" w="100%" px="sm" py={6} style={{ display: 'block', fontSize: 14 }} onClick={() => onPick(a)}>
            {t(`plan.menu.${a}`)}
          </UnstyledButton>
        ))}
      </Paper>
    </>
  );
}
