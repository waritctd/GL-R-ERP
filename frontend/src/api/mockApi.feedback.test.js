import { describe, expect, it } from 'vitest';
import { api } from './mockApi.js';

// Pins the mock's shape only; FeedbackServiceIntegrationTest is the evidence for the Java rules.
const input = { kind: 'BUG', area: 'LEAVE', details: 'รายละเอียดที่ยาวพอสมควร' };

describe('mockApi.feedback', () => {
  it('allows 10 submissions an hour per reporter; the 11th is 429 with the Thai message', async () => {
    await api.auth.login({ role: 'account' });
    for (let i = 0; i < 10; i += 1) await api.feedback.submit(input);

    await expect(api.feedback.submit(input)).rejects.toMatchObject({
      status: 429, message: expect.stringContaining('10 เรื่องต่อชั่วโมง'),
    });
    // Another reporter is unaffected.
    await api.auth.login({ role: 'import' });
    await expect(api.feedback.submit(input)).resolves.toMatchObject({ status: 'PENDING' });
  });

  it('writes the reporter bell with the full Thai month, as the server does', async () => {
    await api.auth.login({ role: 'ceo' });
    const pending = await api.feedback.submit(input);
    await api.feedback.decide(pending.id, { decision: 'ACCEPT', eta: '2026-12-05' });
    await api.auth.login({ role: 'ceo' });
    const { notifications } = await api.notifications.list();
    expect(notifications.find((n) => n.type === 'FEEDBACK_DECIDED').message).toBe('กำหนดเสร็จ 5 ธันวาคม 2569');
  });
});
