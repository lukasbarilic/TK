// Kľúče TanStack Query; dopĺňajú sa s jednotlivými funkciami.
export const queryKeys = {
  me: ['me'] as const,
  receptions: ['receptions'] as const,
  businessDay: (receptionId: number) => ['business-day', receptionId] as const,
  clientConfig: ['client-config'] as const,
  adminUsers: ['admin', 'users'] as const,
  plan: ['plan'] as const,
  preference: (key: string) => ['preference', key] as const,
};
