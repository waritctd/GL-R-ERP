import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// The mock mirrors the backend with the feature OFF (default config: no owner code): the gate
// refuses everyone, and stop (no gate server-side) just answers with the current user.
describe('mockApi.viewAs (feature off)', () => {
  it('candidates and start are 403 and never swap the session', async () => {
    const { user } = await api.auth.login({ role: 'hr' });

    await expect(api.viewAs.candidates()).rejects.toMatchObject({ status: 403, message: 'ไม่มีสิทธิ์เข้าถึงรายการนี้' });
    await expect(api.viewAs.start(2)).rejects.toMatchObject({ status: 403 });

    const me = await api.auth.me();
    expect(me.user.id).toBe(user.id);
    expect(me.viewAs).toBeNull();
    expect(me.canViewAs).toBe(false);
  });

  it('stop resolves with the current user', async () => {
    const { user } = await api.auth.login({ role: 'hr' });
    const res = await api.viewAs.stop();
    expect(res.user.id).toBe(user.id);
    expect(res.viewAs).toBeNull();
  });
});
