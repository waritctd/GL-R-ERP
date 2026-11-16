import { describe, expect, it } from 'vitest';
import { api } from './mockApi.js';

// The mock mirrors the server's response shape with synthetic data; its role gate approximates PeopleInsightsService and is
// NOT evidence (PeopleInsightsIntegrationTest is).
describe('mockApi.peopleInsights', () => {
  it('serves ceo and hr a response with every field the page reads', async () => {
    for (const role of ['ceo', 'hr']) {
      await api.auth.login({ role });
      const data = await api.peopleInsights.get({});
      expect(data.scopes[0]).toEqual({ id: 'all', label: 'ทั้งบริษัท' });
      expect(data.scope.id).toBe('all');
      for (const key of ['people', 'leave', 'attendance', 'quotaUsage', 'leaveTypes', 'teams']) expect(Array.isArray(data[key])).toBe(true);
      expect(data.people.length).toBeGreaterThan(0);
    }
  });

  it('narrows to a team when asked, and refuses everyone else with 403', async () => {
    await api.auth.login({ role: 'ceo' });
    const { scopes } = await api.peopleInsights.get({});
    const team = await api.peopleInsights.get({ scope: scopes[1].id });
    expect(team.scope.id).toBe(scopes[1].id);
    expect(team.teams).toEqual([]);
    const members = new Set(team.people.map((p) => p.employeeId));
    expect(team.quotaUsage.every((r) => members.has(r.employeeId))).toBe(true);
    await api.auth.login({ role: 'sales' });
    await expect(api.peopleInsights.get({})).rejects.toMatchObject({ status: 403 });
  });
});
