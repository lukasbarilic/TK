/** Dátové sady trezora (DLD kap. 5.1). `dataset` a `key` sú nešifrované: žiadne osobné údaje, len ID a dátumy. */
export const DS = {
  session: 'session',
  catalog: 'catalog',
  businessDay: 'business-day',
  plan: 'plan',
  reservation: 'reservation',
  movements: 'movements',
  guestList: 'guest-list',
  /** Pomocná sada: ETag a zoznam ID kariet z hromadného volania B03 (kľúč = `<receptionId>`). */
  reservationSync: 'reservation-sync',
  /** Nastavenia používateľa (B06); kľúč = kľúč nastavenia, napr. `plan.defaultView`. */
  preferences: 'preferences',
} as const;

export const SESSION_KEYS = { me: 'me', clientConfig: 'client-config' } as const;

export const CATALOGS = ['centres', 'receptions', 'buildings', 'rooms', 'room-categories', 'payers'] as const;
export type CatalogKey = (typeof CATALOGS)[number];

export const movementsKey = (receptionId: number, date: string): string => `${receptionId}:${date}`;
