import type { TFunction } from 'i18next';
import { ApiError } from './errors';

/** Slovenský text chyby (DLD kap. 8): kód z `errors.<CODE>`, inak 403 → oprávnenie, inak `title`, inak všeobecná chyba. */
export function errorText(t: TFunction, e: unknown): string {
  if (!(e instanceof ApiError)) return t('errors.generic');
  if (e.code) {
    const text = t(`errors.${e.code}`, { defaultValue: '' });
    if (text) return text;
  }
  if (e.status === 403) return t('errors.forbidden');
  return e.title ?? t('errors.generic');
}
