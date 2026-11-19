import { Anchor, Breadcrumbs, Button, Group, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate } from 'react-router-dom';

export interface Crumb {
  label: string;
  to?: string;
}

/**
 * Hlavička obrazovky mimo hlavného menu: tlačidlo Späť a drobčeky.
 * Späť ide v histórii; ak história nie je (priamy vstup), ide na `fallback` (nadradená obrazovka).
 */
export function PageHeader({
  title,
  crumbs,
  fallback,
  confirmLeave,
}: {
  title: string;
  crumbs: Crumb[];
  fallback: string;
  /** Rozpísaný formulár: pred odchodom (Späť, drobčeky) sa zavolá s akciou, ktorú treba po potvrdení vykonať. */
  confirmLeave?: (proceed: () => void) => void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const guard = (go: () => void) => (confirmLeave ? confirmLeave(go) : go());
  const back = () => guard(() => void (location.key !== 'default' ? navigate(-1) : navigate(fallback, { replace: true })));
  return (
    <Group mb="sm" gap="md" align="center" data-testid="page-header">
      <Button variant="default" size="compact-md" onClick={back}>
        ← {t('shared.back')}
      </Button>
      <div>
        <Breadcrumbs aria-label={t('shared.breadcrumbs')}>
          {crumbs.map((c, i) =>
            c.to ? (
              <Anchor
                key={i}
                component={Link}
                to={c.to}
                size="sm"
                onClick={
                  confirmLeave
                    ? (e: React.MouseEvent) => {
                        e.preventDefault();
                        const to = c.to as string;
                        guard(() => void navigate(to));
                      }
                    : undefined
                }
              >
                {c.label}
              </Anchor>
            ) : (
              <span key={i} style={{ fontSize: 14 }} aria-current="page">
                {c.label}
              </span>
            ),
          )}
        </Breadcrumbs>
        <Title order={2}>{title}</Title>
      </div>
    </Group>
  );
}
