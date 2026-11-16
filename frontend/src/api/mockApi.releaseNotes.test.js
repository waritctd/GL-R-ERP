import { describe, expect, it } from 'vitest';
import { api } from './mockApi.js';

// Mirrors ReleaseNoticeService (backend ReleaseNoticeIntegrationTest). The mock's authz is NOT evidence about the
// Java service — the CEO/HR gate and the targeting are proven only by the real-DB integration test. This file pins
// the mock's SHAPE, that publishing is idempotent, that publishing never "emails", and that the mock never sends
// mail at all (email only records who would have been mailed).

const request = {
  releaseId: '2026-11-16',
  title: 'อัปเดตระบบขาย',
  changes: [
    { title: 'งานของฉัน', roles: ['sales', 'sales_manager', 'ceo'] },
    { title: 'รับเงิน', roles: ['account'] },
  ],
};

async function expectStatus(promise, status) {
  await expect(promise).rejects.toMatchObject({ status });
}

async function releaseNotesFor(role) {
  await api.auth.login({ role });
  const { notifications } = await api.notifications.list();
  return notifications.filter((n) => n.type === 'RELEASE_NOTE' && n.link === '/whats-new/2026-11-16');
}

describe('mockApi.releaseNotes', () => {
  it('refuses every role outside ceo / hr', async () => {
    for (const role of ['sales', 'sales_manager', 'import', 'account', 'employee']) {
      await api.auth.login({ role });
      await expectStatus(api.releaseNotes.publish(request), 403);
      await expectStatus(api.releaseNotes.email(request), 403);
      await expectStatus(api.releaseNotes.audience(request), 403);
    }
  });

  it('publish notifies targeted users once, never the untargeted, and is idempotent', async () => {
    await api.auth.login({ role: 'hr' });
    const before = await api.releaseNotes.audience(request);
    expect(before.targeted).toBeGreaterThan(0);
    expect(before.pendingNotification).toBe(before.targeted);

    const first = await api.releaseNotes.publish(request);
    expect(first).toEqual({ targeted: before.targeted, inserted: before.targeted, alreadyNotified: 0 });
    const second = await api.releaseNotes.publish(request);
    expect(second).toEqual({ targeted: before.targeted, inserted: 0, alreadyNotified: before.targeted });

    const sales = await releaseNotesFor('sales');
    expect(sales).toHaveLength(1);
    expect(sales[0]).toMatchObject({ read: false, link: '/whats-new/2026-11-16' });
    expect(await releaseNotesFor('employee')).toHaveLength(0);
    expect(await releaseNotesFor('import')).toHaveLength(0);
  });

  it('email is separate from publish and idempotent', async () => {
    await api.auth.login({ role: 'ceo' });
    const next = { ...request, releaseId: '2026-12-01' };
    const audience = await api.releaseNotes.audience(next);
    expect(audience.alreadyEmailed).toBe(0);

    const first = await api.releaseNotes.email(next);
    expect(first.sent).toBe(audience.pendingEmail);
    const second = await api.releaseNotes.email(next);
    expect(second.sent).toBe(0);
    expect(second.alreadyEmailed).toBe(first.sent);
    // Emailing published nothing to the bell.
    expect((await api.releaseNotes.audience(next)).alreadyNotified).toBe(0);
  });

  it('refuses a malformed request or an unknown role with 400', async () => {
    await api.auth.login({ role: 'hr' });
    await expectStatus(api.releaseNotes.publish({ ...request, releaseId: '../x' }), 400);
    await expectStatus(api.releaseNotes.publish({ ...request, changes: [{ title: 'x', roles: ['admin'] }] }), 400);
    await expectStatus(api.releaseNotes.publish({ ...request, changes: [] }), 400);
  });
});
