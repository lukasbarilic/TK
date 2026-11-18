import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

/**
 * Základné odpovede, ktoré si pýta každá obrazovka s plánom ubytovania (úvodná obrazovka recepcie):
 * žiadne uložené nastavenia a prázdny plán (`/api/client-config` zámerne chýba: synchronizácia sa potom v testoch nerozbehne). Testy ich prepisujú cez `server.use`.
 */
export const server = setupServer(
  http.get('/api/me/preferences/:key', () => HttpResponse.json({}, { status: 404 })),
  http.get('/api/accommodation-plan', () => HttpResponse.json({ rooms: [] })),
);
