import { Paper, UnstyledButton } from '@mantine/core';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { StatusAction } from '../../shared/status';

/** Akcie stavu karty majú predponu `status:`; ostatné sú zmeny ubytovania. */
export type MenuAction = 'moveFrom' | 'swap' | 'cancel' | 'newReservation' | `status:${StatusAction}`;

interface Props {
  x: number;
  y: number;
  /** Akcie, ktoré sa pre daný pásik ponúkajú; prvá je hlavná (Enter ju vykoná). */
  actions: MenuAction[];
  onPick: (a: MenuAction) => void;
  onClose: () => void;
}

const label = (t: (k: string) => string, a: MenuAction) => (a.startsWith('status:') ? t(`status.actions.${a.slice(7)}`) : t(`plan.menu.${a}`));

/**
 * Kontextové menu pásika pobytu (DLD kap. 9.3): akcie stavu (hlavná akcia dňa prvá), Presťahovať od…, Vymeniť s…,
 * Zrušiť ubytovanie. Otvorí sa pravým tlačidlom myši alebo klávesom Menu; prvá položka má fokus, Enter ju vykoná,
 * šípky menia položku, Escape zatvára.
 */
export function BarMenu({ x, y, actions, onPick, onClose }: Props) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  useEffect(() => {
    ref.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
  }, []);
  const move = (e: React.KeyboardEvent, step: number) => {
    const items = [...(ref.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])];
    const i = items.indexOf(document.activeElement as HTMLElement);
    e.preventDefault();
    items[(i + step + items.length) % items.length]?.focus();
  };
  return (
    <>
      <div style={{ position: 'fixed', inset: 0, zIndex: 300 }} onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose(); }} />
      <Paper
        ref={ref}
        role="menu"
        withBorder
        shadow="md"
        radius="md"
        p={4}
        data-testid="bar-menu"
        style={{ position: 'fixed', left: x, top: y, zIndex: 301, minWidth: 190 }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') move(e, 1);
          if (e.key === 'ArrowUp') move(e, -1);
        }}
      >
        {actions.map((a) => (
          <UnstyledButton key={a} role="menuitem" w="100%" px="sm" py={6} style={{ display: 'block', fontSize: 14 }} onClick={() => onPick(a)}>
            {label(t, a)}
          </UnstyledButton>
        ))}
      </Paper>
    </>
  );
}
