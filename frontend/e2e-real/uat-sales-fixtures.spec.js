import { test, expect } from '@playwright/test';
import { uatSessionFor, disposeSessions } from './helpers/api.js';
import { UAT_SALES_ROLES } from './helpers/uat-accounts.js';
import {
  TICKET_STATUS,
  cancelDeal,
  createDeal,
  readDeal,
  runId,
  stageHistory,
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

  test('a new deal starts at LEAD_APPROACH with NO stage history', async () => {
    const deal = await createDeal(sessions, { caseName: 'slice2 create' });
    created.push(deal.id);

    const ticket = await readDeal(sessions, 'sales', deal.id);

    expect(ticket.summary.salesStage, 'a new deal must start at the first stage').toBe(
      'LEAD_APPROACH'
    );
    expect(ticket.summary.lifecycle, 'a new deal is ACTIVE').toBe('ACTIVE');

    // The baseline that makes every later route assertion meaningful.
    const history = await stageHistory(sessions, 'sales', deal.id);
    expect(
      history,
      'a freshly created deal must have emitted no STAGE_CHANGED event — this is the baseline ' +
        "Case C's \"starts at S8\" is measured against"
    ).toEqual([]);
  });

  test('the deal carries the run tag, so a failed run leaves findable rows', async () => {
    const deal = await createDeal(sessions, { caseName: 'slice2 tagging' });
    created.push(deal.id);

    const ticket = await readDeal(sessions, 'sales', deal.id);

    // sales.ticket.code is server-generated (tickets.nextTicketCode()), so the title is the only handle a human
    // has for "what did that run leave behind". If this ever stops holding, the documented manual sweep (WHERE
    // title LIKE 'E2E-%') stops finding anything.
    expect(ticket.summary.title, 'the run id must lead the title').toContain(runId());
    expect(ticket.summary.title).toMatch(/^E2E-\d{8}T\d{6}Z-[0-9a-f]{4} /);
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
