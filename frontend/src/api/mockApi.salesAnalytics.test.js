import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';
import { isUsable } from '../features/dashboard/insight/insightFormat.js';

// Role gates mirror SalesAnalyticsService but are NOT evidence (SalesAnalyticsSnapshotIntegrationTest is). The data
// arrives through an outside weekly job, so the mock has no upload: it starts with a synthetic snapshot instead.
describe('mockApi.salesAnalytics', () => {
  it('starts with a usable synthetic snapshot for the CEO and the sales manager', async () => {
    for (const role of ['ceo', 'sales_manager']) {
      await api.auth.login({ role });
      const { snapshot } = await api.salesAnalytics.snapshot();
      expect(isUsable(snapshot?.payload)).toBe(true);
      expect(snapshot.asOf).toBe('2026-03-31');
      expect(Number.isNaN(Date.parse(snapshot.uploadedAt))).toBe(false);
    }
  });

  it('serves the weekly metrics to the CEO and the sales manager', async () => {
    for (const role of ['ceo', 'sales_manager']) {
      await api.auth.login({ role });
      const d = await api.salesAnalytics.metrics();
      expect(d.weekStart).toBe('2026-03-30');
      expect(d.metrics.some((m) => m.key === 'pipeline_value_dedup')).toBe(true);
    }
  });

  it('refuses every other role with 403', async () => {
    for (const role of ['sales', 'account', 'hr']) {
      await api.auth.login({ role });
      await expect(api.salesAnalytics.snapshot()).rejects.toMatchObject({ status: 403 });
      await expect(api.salesAnalytics.metrics()).rejects.toMatchObject({ status: 403 });
    }
  });

  it('has no upload', () => {
    expect(api.salesAnalytics.upload).toBeUndefined();
  });
});
