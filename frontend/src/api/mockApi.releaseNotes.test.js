import { describe, expect, it } from 'vitest';
import { api } from './mockApi.js';

// Mirrors ReleaseNoticeService (backend ReleaseNoticeIntegrationTest). The mock's authz is NOT evidence about the
// Java service — the CEO/HR gate and the targeting are proven only by the real-DB integration test. This file pins
// the mock's SHAPE, that publish-with-email is idempotent, and that the mock never sends mail at all: "emailing"
// only records who would have been mailed, as an immediately "sent" outcome. The mock does not model a failed or
// lost send — failure, retry and the crash window are proven only against the Java service.

const request = {
  releaseId: '2026-11-16',
  title: 'อัปเดตระบบขาย',
  date: '2026-11-16',
  changes: [
    { title: 'งานของฉัน', roles: ['sales', 'sales_manager', 'ceo'], why: 'เห็นงานวันนี้ในหน้าเดียว' },
    { title: 'รับเงิน', roles: ['account'], why: null },
  ],
};

async function expectStatus(promise, status) {
  await expect(promise).rejects.toMatchObject({ status });
}

async function releaseNotesFor(role, releaseId = '2026-11-16') {
  await api.auth.login({ role });
  const { notifications } = await api.notifications.list();
  return notifications.filter((n) => n.type === 'RELEASE_NOTE' && n.link === `/whats-new/${releaseId}`);
}

describe('mockApi.releaseNotes', () => {
  it('refuses every role outside ceo / hr', async () => {
    for (const role of ['sales', 'sales_manager', 'import', 'account', 'employee']) {
      await api.auth.login({ role });
      await expectStatus(api.releaseNotes.publishWithEmail(request), 403);
      await expectStatus(api.releaseNotes.email(request), 403);
      await expectStatus(api.releaseNotes.email({ ...request, resendUnconfirmed: true }), 403);
      await expectStatus(api.releaseNotes.audience(request), 403);
    }
  });

  it('publish-with-email notifies and mails the targets once, never the untargeted, and is idempotent', async () => {
    await api.auth.login({ role: 'hr' });
    const before = await api.releaseNotes.audience(request);
    expect(before.targeted).toBeGreaterThan(0);
    expect(before.pendingNotification).toBe(before.targeted);
    expect(Object.keys(before.email).sort()).toEqual([
      'failed', 'failedNames', 'notYetEmailed', 'sending', 'sent', 'unconfirmed', 'unconfirmedNames',
      'withoutEmail', 'withoutEmailNames',
    ]);

    const first = await api.releaseNotes.publishWithEmail(request);
    expect(first).toEqual({
      targeted: before.targeted, inserted: before.targeted, alreadyNotified: 0,
      email: { targeted: before.targeted, queued: before.pendingEmail, alreadyEmailed: 0, withoutEmail: before.withoutEmail },
      emailError: null,
    });
    const second = await api.releaseNotes.publishWithEmail(request);
    expect(second.inserted).toBe(0);
    expect(second.email.queued).toBe(0);
    expect(second.email.alreadyEmailed).toBe(before.pendingEmail);

    const after = await api.releaseNotes.audience(request);
    expect(after.email.sent).toBe(before.pendingEmail);
    expect(after.email.withoutEmailNames).toHaveLength(after.email.withoutEmail);

    const sales = await releaseNotesFor('sales');
    expect(sales).toHaveLength(1);
    expect(sales[0]).toMatchObject({ read: false, link: '/whats-new/2026-11-16' });
    expect(await releaseNotesFor('employee')).toHaveLength(0);
    expect(await releaseNotesFor('import')).toHaveLength(0);
  });

  it('the email action alone does not publish, and does not re-mail', async () => {
    await api.auth.login({ role: 'ceo' });
    const next = { ...request, releaseId: '2026-12-01', date: '2026-12-01' };
    const audience = await api.releaseNotes.audience(next);
    expect(audience.alreadyEmailed).toBe(0);

    const first = await api.releaseNotes.email(next);
    expect(first.queued).toBe(audience.pendingEmail);
    const second = await api.releaseNotes.email(next);
    expect(second.queued).toBe(0);
    expect(second.alreadyEmailed).toBe(first.queued);
    expect((await api.releaseNotes.audience(next)).alreadyNotified).toBe(0);
  });

  it('refuses a malformed request, an unknown role, a long why or a bad date with 400', async () => {
    await api.auth.login({ role: 'hr' });
    await expectStatus(api.releaseNotes.publishWithEmail({ ...request, releaseId: '../x' }), 400);
    await expectStatus(api.releaseNotes.publishWithEmail({ ...request, changes: [{ title: 'x', roles: ['admin'] }] }), 400);
    await expectStatus(api.releaseNotes.publishWithEmail({ ...request, changes: [] }), 400);
    await expectStatus(api.releaseNotes.publishWithEmail({
      ...request, changes: [{ title: 'x', roles: ['sales'], why: 'ก'.repeat(301) }],
    }), 400);
    await expectStatus(api.releaseNotes.publishWithEmail({ ...request, date: '17/11/2026' }), 400);
  });
});
