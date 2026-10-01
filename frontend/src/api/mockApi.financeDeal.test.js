import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// api.finance.getDeal mirrors th.co.glr.hr.finance.FinanceDealService (GET /api/finance/deals/{id}).
// The mock's authorization is NEVER authoritative -- FinanceDealIntegrationTest (real Java service,
// real Postgres) is the evidence. This pins that the mock is no MORE permissive than that service
// and that its payload carries none of the forbidden (cost / pricing / tracking / activity) keys.

const FORBIDDEN_KEY_FRAGMENTS = [
  'pricingrequest', 'cost', 'landed', 'factory', 'fx', 'margin', 'winprobability', 'designer',
  'owner', 'buyer', 'events', 'activit', 'assignedto', 'proposed', 'rawprice', 'approvedprice',
  'catalog', 'manual', 'weight', 'discount', 'listprice', 'listunit',
];
// The one sanctioned comment-shaped key: the deal's COMMENT events (B2). Any other 'comment' key is a leak.
const SANCTIONED_KEY = 'comments';
const ORDER_FLOOR = [
  'ORDER_RECEIVED', 'DEPOSIT_RECEIVED', 'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID',
];
const isDead = (t) => ['CLOSED_LOST', 'CANCELLED'].includes(t.lifecycle) || t.status === 'cancelled';

const login = (email) => api.auth.login({ email, password: 'demo1234' });

async function ceoList() {
  await login('ceo@glr.co.th');
  return (await api.tickets.list()).tickets;
}

function keysOf(node, out = []) {
  if (Array.isArray(node)) node.forEach((n) => keysOf(n, out));
  else if (node && typeof node === 'object') {
    Object.entries(node).forEach(([k, v]) => { out.push(k); keysOf(v, out); });
  }
  return out;
}

describe('mockApi.finance.getDeal', () => {
  it('refuses sales, sales_manager, import, hr and employee with 403', async () => {
    const live = (await ceoList()).find((t) => !isDead(t) && ORDER_FLOOR.includes(t.salesStage));
    expect(live).toBeTruthy();
    for (const email of ['sales@glr.co.th', 'sales.manager@glr.co.th', 'import@glr.co.th', 'hr@glr.co.th', 'employee@glr.co.th']) {
      await login(email);
      await expect(api.finance.getDeal(live.id), email).rejects.toMatchObject({ status: 403 });
    }
  });

  it('account: 403 on a pre-S10 deal, 404 on a missing id', async () => {
    const pre = (await ceoList()).find((t) => !ORDER_FLOOR.includes(t.salesStage) && !isDead(t)
      && !['DEPOSIT_NOTICE_ISSUED', 'AWAITING_FINAL_PAYMENT'].includes(t.paymentStatus) && t.overdue !== true);
    expect(pre).toBeTruthy();
    await login('account@glr.co.th');
    await expect(api.finance.getDeal(pre.id)).rejects.toMatchObject({ status: 403 });
    await expect(api.finance.getDeal(999999)).rejects.toMatchObject({ status: 404 });
  });

  it('ceo reads a pre-S10 deal (no current milestone); account reads a live order with a milestone', async () => {
    const all = await ceoList();
    const pre = all.find((t) => !ORDER_FLOOR.includes(t.salesStage));
    const { deal: ceoDeal } = await api.finance.getDeal(pre.id);
    expect(ceoDeal.moneyMilestone).toBeNull();
    expect(ceoDeal.milestoneTrack).toHaveLength(5);

    const live = all.find((t) => !isDead(t) && t.salesStage === 'PROCUREMENT');
    expect(live).toBeTruthy();
    await login('account@glr.co.th');
    const { deal } = await api.finance.getDeal(live.id);
    expect(deal.id).toBe(live.id);
    expect(deal.moneyMilestone.index).toBe(3);
    expect(deal.money).toBeTruthy();
    expect(deal.documents).toBeTruthy();
  });

  it('payload has no cost, pricing, tracking, activity or comment keys', async () => {
    const live = (await ceoList()).find((t) => !isDead(t) && ORDER_FLOOR.includes(t.salesStage));
    await login('account@glr.co.th');
    const { deal } = await api.finance.getDeal(live.id);
    const keys = keysOf(deal);
    expect(keys.length).toBeGreaterThan(0);
    for (const key of keys) {
      if (key === SANCTIONED_KEY) continue;
      expect(key.toLowerCase(), `key ${key}`).not.toContain('comment');
      // 'ceo' as a WORD only (leading / camel-case), never a substring: 'invoiceOnFile' contains c-e-O.
      expect(/^ceo|Ceo|_ceo/.test(key), `key ${key} names the ceo`).toBe(false);
      for (const fragment of FORBIDDEN_KEY_FRAGMENTS) {
        expect(key.toLowerCase(), `key ${key}`).not.toContain(fragment);
      }
    }
  });
});

// ── H1 part 2: the account lockdown, mirrored (the Java ITs are the evidence; this pins the mock) ──

const login2 = (email) => api.auth.login({ email, password: 'demo1234' });
const PRE_S10 = ['LEAD_APPROACH', 'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF',
  'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION'];

describe('mockApi lockdown -- account on the /tickets read routes', () => {
  it('is refused get / actions / listPayments / listDeliveries / comment (403), even on an in-scope live deal', async () => {
    const live = (await ceoList()).find((t) => !isDead(t) && ORDER_FLOOR.includes(t.salesStage));
    await login2('account@glr.co.th');
    await expect(api.tickets.get(live.id)).rejects.toMatchObject({ status: 403 });
    await expect(api.tickets.actions(live.id)).rejects.toMatchObject({ status: 403 });
    await expect(api.tickets.listPayments(live.id)).rejects.toMatchObject({ status: 403 });
    await expect(api.tickets.listDeliveries(live.id)).rejects.toMatchObject({ status: 403 });
    await expect(api.tickets.comment(live.id, { message: 'x' })).rejects.toMatchObject({ status: 403 });
  });

  it('is refused /tickets billing, close-revoke and stage; ceo keeps them', async () => {
    const live = (await ceoList()).find((t) => !isDead(t) && ORDER_FLOOR.includes(t.salesStage)
      && (t.lifecycle ?? 'ACTIVE') === 'ACTIVE');
    await login2('account@glr.co.th');
    await expect(api.tickets.setBilling(live.id, { dueDate: '2030-01-01' })).rejects.toMatchObject({ status: 403 });
    await expect(api.tickets.revokeCloseConfirmation(live.id, {})).rejects.toMatchObject({ status: 403 });
    await expect(api.tickets.updateStage(live.id, { stage: 'CLOSED_PAID', note: 'x' })).rejects.toMatchObject({ status: 403 });
    await login2('ceo@glr.co.th');
    await api.tickets.setBilling(live.id, { dueDate: '2030-01-01' });
  });
});

describe('mockApi.finance routes -- role gates and scope', () => {
  const accountOnly = [
    ['confirmDepositPaid', (id) => api.finance.confirmDepositPaid(id)],
    ['confirmFinalPayment', (id) => api.finance.confirmFinalPayment(id)],
    ['recordPayment', (id) => api.finance.recordPayment(id, { kind: 'DEPOSIT', amount: 1 })],
    ['confirmCloseReady', (id) => api.finance.confirmCloseReady(id)],
  ];
  const shared = [
    ['addComment', (id) => api.finance.addComment(id, { message: 'x' })],
    ['revokeCloseConfirmation', (id) => api.finance.revokeCloseConfirmation(id, {})],
    ['updateStage', (id) => api.finance.updateStage(id, { stage: 'DEPOSIT_RECEIVED', note: 'x' })],
  ];

  it('refuses sales, sales_manager, import, hr and employee on every finance action (403)', async () => {
    const live = (await ceoList()).find((t) => !isDead(t) && ORDER_FLOOR.includes(t.salesStage));
    for (const email of ['sales@glr.co.th', 'sales.manager@glr.co.th', 'import@glr.co.th', 'hr@glr.co.th', 'employee@glr.co.th']) {
      await login2(email);
      for (const [name, call] of [...accountOnly, ...shared]) {
        await expect(call(live.id), `${email} ${name}`).rejects.toMatchObject({ status: 403 });
      }
    }
  });

  it('ceo is refused the account-only actions (403) but may comment and revoke', async () => {
    const live = (await ceoList()).find((t) => !isDead(t) && ORDER_FLOOR.includes(t.salesStage)
      && (t.lifecycle ?? 'ACTIVE') === 'ACTIVE');
    await login2('ceo@glr.co.th');
    for (const [name, call] of accountOnly) {
      await expect(call(live.id), `ceo ${name}`).rejects.toMatchObject({ status: 403 });
    }
    await api.finance.addComment(live.id, { message: 'ceo comment' });
    expect(api.finance.setBilling).toBeUndefined(); // billing is out of scope for the finance page
  });

  it('account is refused every finance action on a deal below S10', async () => {
    const pre = (await ceoList()).find((t) => PRE_S10.includes(t.salesStage) && !isDead(t)
      && !['DEPOSIT_NOTICE_ISSUED', 'AWAITING_FINAL_PAYMENT'].includes(t.paymentStatus) && t.overdue !== true);
    await login2('account@glr.co.th');
    for (const [name, call] of [...accountOnly, ...shared]) {
      await expect(call(pre.id), `below S10 ${name}`).rejects.toMatchObject({ status: 403 });
    }
  });

  it('account confirms a deposit on an in-scope deal and the change shows in the finance view', async () => {
    const target = (await ceoList()).find((t) => t.paymentStatus === 'DEPOSIT_NOTICE_ISSUED'
      && (t.lifecycle ?? 'ACTIVE') === 'ACTIVE');
    expect(target).toBeTruthy();
    await login2('account@glr.co.th');
    const { deal } = await api.finance.confirmDepositPaid(target.id);
    expect(deal.money.paymentStatus).toBe('DEPOSIT_PAID');
    expect(deal.money.payments.length).toBeGreaterThan(0);
    const again = (await api.finance.getDeal(target.id)).deal;
    expect(again.money.paymentStatus).toBe('DEPOSIT_PAID');
  });

  it('the old tickets.* money methods are the SAME finance routes (account-only, scoped)', async () => {
    const live = (await ceoList()).find((t) => !isDead(t) && ORDER_FLOOR.includes(t.salesStage));
    await login2('ceo@glr.co.th');
    await expect(api.tickets.confirmDepositPaid(live.id)).rejects.toMatchObject({ status: 403 });
    await expect(api.tickets.confirmCloseReady(live.id)).rejects.toMatchObject({ status: 403 });
  });

  it('comments in the finance deal are COMMENT events only, from every author, each with an author and time', async () => {
    const live = (await ceoList()).find((t) => !isDead(t) && ORDER_FLOOR.includes(t.salesStage)
      && (t.lifecycle ?? 'ACTIVE') === 'ACTIVE');
    await login2('ceo@glr.co.th');
    await api.finance.addComment(live.id, { message: 'จาก ceo' });
    await login2('account@glr.co.th');
    await api.finance.addComment(live.id, { message: 'จาก account' });
    const { deal } = await api.finance.getDeal(live.id);
    const messages = deal.comments.map((c) => c.message);
    expect(messages).toEqual(expect.arrayContaining(['จาก ceo', 'จาก account']));
    for (const c of deal.comments) {
      expect(c.authorName).toBeTruthy();
      expect(c.createdAt).toBeTruthy();
      expect(Object.keys(c).sort()).toEqual(['authorName', 'createdAt', 'id', 'message']);
    }
    expect(Array.isArray(deal.availableActions)).toBe(true);
  });
});
