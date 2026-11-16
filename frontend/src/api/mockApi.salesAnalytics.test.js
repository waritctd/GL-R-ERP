import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mirrors SalesAnalyticsService#upload: asOf is the document's `as_of` string, else null (the real
// authz/validation is proven only by SalesAnalyticsSnapshotIntegrationTest).
const doc = (extra = {}) => ({ months: ['ม.ค. 69'], monthly_total: [1], monthly: [{ total: 1, reps: [] }], reps: [], ...extra });

describe('mockApi.salesAnalytics.upload', () => {
  it('derives asOf from the document\'s as_of string, and null when it is missing or not a string', async () => {
    await api.auth.login({ role: 'ceo' });
    await api.salesAnalytics.upload(doc({ as_of: '2026-03-31' }));
    expect((await api.salesAnalytics.snapshot()).snapshot.asOf).toBe('2026-03-31');

    await api.salesAnalytics.upload(doc());
    expect((await api.salesAnalytics.snapshot()).snapshot.asOf).toBeNull();

    await api.salesAnalytics.upload(doc({ as_of: 5 }));
    expect((await api.salesAnalytics.snapshot()).snapshot.asOf).toBeNull();
  });
});
