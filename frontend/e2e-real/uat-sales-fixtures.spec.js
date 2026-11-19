import { test, expect } from '@playwright/test';
import { uatSessionFor, disposeSessions } from './helpers/api.js';
import { UAT_SALES_ROLES } from './helpers/uat-accounts.js';
import {
  TICKET_STATUS,
  cancelDeal,
  createDeal,
  readDeal,
} from './helpers/sales.js';

// SLICE 2 — the first WRITE against deployed UAT, and its cleanup. Two things have to be proven on a shared
// database before creating anything that cannot be cleaned up: 1. Deliberately one deal and one cancel, before
// any journey exists.

test.describe('@uat-sales sales fixtures — create and cancel a deal', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  const sessions = {};
  /** Every deal this file creates, so afterAll can take them back even if a test threw. */
  const created = [];

  test.beforeAll(async () => {
    for (const role of UAT_SALES_ROLES) {
      sessions[role] = await uatSessionFor(role);
    }
  });

  test.afterAll(async () => {
    // Hooks carry their own timeout and test.setTimeout() on a TEST does not extend it. Teardown cancels one deal
    // at a time against a Render `starter` instance; the refusal spec hit the 30s default at 12 deals with every
    // assertion passing. Budget here before this file grows.
    test.setTimeout(180_000);

    // Best-effort, and outside any expect(): teardown must never turn a passing assertion red, and must never mask
    // a real failure with a second one.
    for (const ticketId of created) {
      await cancelDeal(sessions, ticketId);
    }
    await disposeSessions(sessions);
  });

  test('cancel takes a deal back, and it stays cancelled', async () => {
    const deal = await createDeal(sessions, { caseName: 'slice2 cancel' });
    created.push(deal.id);

    await cancelDeal(sessions, deal.id);

    // Re-READ rather than trusting the response body: the question is what the database holds, not what the write
    // echoed back. Note the two cases. `lifecycle` is UPPERCASE (DealLifecycle) and `status` is lowercase
    // (TicketStatus) — on the same row, in the same response.
    const ticket = await readDeal(sessions, 'sales', deal.id);
    expect(ticket.summary.lifecycle, 'cancel must set lifecycle CANCELLED').toBe('CANCELLED');
    expect(ticket.summary.status, 'cancel must set status cancelled (lowercase)').toBe(
      TICKET_STATUS.CANCELLED
    );

    // Cancel is terminal. Asserting it here is what lets afterAll cancel unconditionally without caring whether a
    // test already did — the second call is a no-op, not an error path.
    const again = await readDeal(sessions, 'sales', deal.id);
    expect(again.summary.lifecycle).toBe('CANCELLED');
  });
});
