import { describe, expect, it } from 'vitest';
import { API_ROUTES } from './routes.js';

// Slice 6c: GET /api/tickets/{id}/import-requests/coverage takes the rep's unsaved factory picks as repeatable
// `itemFactory=<ticketItemId>:<factoryId>` pairs (ImportRequestController#coverage), so the confirm's per-factory
// preview places those lines where createDrafts will. No picks -> the bare route, exactly as before.
describe('API_ROUTES.storedImportRequests.coverage', () => {
  it('is the bare route with no picks', () => {
    expect(API_ROUTES.storedImportRequests.coverage(5)).toBe('/api/tickets/5/import-requests/coverage');
    expect(API_ROUTES.storedImportRequests.coverage(5, [])).toBe('/api/tickets/5/import-requests/coverage');
  });

  it('sends each pick as one itemFactory pair, in order', () => {
    const url = API_ROUTES.storedImportRequests.coverage(5, [
      { ticketItemId: 3, factoryId: 7 }, { ticketItemId: 4, factoryId: 8 },
    ]);
    const [path, query] = url.split('?');
    expect(path).toBe('/api/tickets/5/import-requests/coverage');
    expect(new URLSearchParams(query).getAll('itemFactory')).toEqual(['3:7', '4:8']);
  });
});
