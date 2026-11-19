import { describe, expect, it } from 'vitest';
import { quotationChainSteps, quotationChainView } from './quotationChain.js';

// The quotation page's document chain card: WHICH quotation shows the chain (quotationChainView) and what each of the
// four steps says (quotationChainSteps). Both read only the quotation and the server's chain summary.

const q = (overrides = {}) => ({
  id: 5, number: 'QT-2026-0005-1', ticketId: 18, docStatus: 'APPROVED', origin: 'DEAL_DIRECT',
  depositPercent: 30, approvedAt: '2026-11-17T03:00:00Z', ...overrides,
});

function chain(overrides = {}) {
  const base = {
    ticketId: 18, dealCode: 'DL-2026-0026', dealStatus: 'draft', lifecycle: 'ACTIVE',
    order: { confirmed: false, confirmable: true, confirmedFromQuotationId: null, confirmedFromQuotationNumber: null, confirmedAt: null },
    deposit: { policy: 'REQUIRED', required: true, percent: null, state: 'NOT_YET', blockedReason: null, noticeId: null, issuedNoticeId: null, docNumber: null, issueDate: null, draftExists: false, received: false },
    remaining: { state: 'WAITING', blockedReason: 'ORDER_NOT_CONFIRMED', canCreate: false, invoiceId: null, docNumber: null, docDate: null, draftExists: false },
    viewer: { mayConfirmOrder: true, mayWriteDepositNotice: true, mayWriteRemainingInvoice: true },
  };
  return {
    ...base, ...overrides,
    order: { ...base.order, ...overrides.order },
    deposit: { ...base.deposit, ...overrides.deposit },
    remaining: { ...base.remaining, ...overrides.remaining },
    viewer: { ...base.viewer, ...overrides.viewer },
  };
}
const confirmedHere = (overrides = {}) => chain({
  dealStatus: 'quotation_issued', ...overrides,
  order: { confirmed: true, confirmable: false, confirmedFromQuotationId: 5, confirmedFromQuotationNumber: 'QT-2026-0005-1', confirmedAt: '2026-11-17T04:00:00Z', ...overrides.order },
  deposit: { state: 'TO_CREATE', percent: 30, ...overrides.deposit },
  remaining: { state: 'WAITING', blockedReason: 'DEPOSIT_NOT_RECEIVED', ...overrides.remaining },
});
const DEAL = '/tickets/18';

describe('quotationChainView — which quotation carries the chain, and the one line for the rest', () => {
  it('an APPROVED direct quotation on a deal that can still take the order shows the chain', () => {
    expect(quotationChainView(q(), chain(), { dealHref: DEAL })).toEqual({ mode: 'chain' });
  });

  it('the quotation the order was confirmed from shows the chain', () => {
    expect(quotationChainView(q(), confirmedHere(), { dealHref: DEAL })).toEqual({ mode: 'chain' });
  });

  it.each([['DRAFT'], ['PENDING_APPROVAL']])('a %s quotation shows nothing (no chain yet)', (docStatus) => {
    expect(quotationChainView(q({ docStatus }), chain(), { dealHref: DEAL }).mode).toBe('none');
  });

  it('confirmed from a DIFFERENT quotation: one line naming it, linking to it', () => {
    const view = quotationChainView(q(), confirmedHere({ order: { confirmedFromQuotationId: 9, confirmedFromQuotationNumber: 'QT-2026-0009-1' } }), { dealHref: DEAL });
    expect(view.mode).toBe('notice');
    expect(view.reason).toBe('OTHER_QUOTATION');
    expect(view.text).toContain('QT-2026-0009-1');
    expect(view.link).toEqual({ to: '/quotations/9', label: 'เปิด QT-2026-0009-1' });
  });
});

const stateOf = (steps) => steps.map((s) => s.state);

describe('quotationChainSteps — the four steps, the ONE current step, and who a waiting step waits on', () => {
  it('not yet confirmed, the viewer may confirm: step 2 is current, 3 and 4 locked', () => {
    const steps = quotationChainSteps(q(), chain());
    expect(stateOf(steps)).toEqual(['done', 'current', 'todo', 'todo']);
    expect(steps[0]).toMatchObject({ detail: 'QT-2026-0005-1' });
    expect(steps[0].action).toBeUndefined(); // the quotation's download lives in the page header only
    expect(steps[1].action).toBe('confirm');
    expect(steps[2].locked).toBe('ยืนยันคำสั่งซื้อก่อน');
    expect(steps[3].locked).toBe('ยืนยันคำสั่งซื้อก่อน');
  });

  it('not yet confirmed, a viewer who may not confirm: step 2 waits on sales, no action', () => {
    const steps = quotationChainSteps(q(), chain({ viewer: { mayConfirmOrder: false } }));
    expect(stateOf(steps)).toEqual(['done', 'waiting', 'todo', 'todo']);
    expect(steps.some((s) => s.action)).toBe(false);
    expect(steps[1].waitingOn).toBe('ฝ่ายขายเจ้าของงาน');
  });

  it('notice issued, deposit not received: step 3 done with its number; step 4 WAITS on ฝ่ายบัญชี (not the viewer\'s turn)', () => {
    const steps = quotationChainSteps(q(), confirmedHere({
      deposit: { state: 'ISSUED', docNumber: 'GLRD69005', issueDate: '2026-11-17', issuedNoticeId: 31 },
    }));
    expect(stateOf(steps)).toEqual(['done', 'done', 'done', 'waiting']);
    expect(steps[2]).toMatchObject({ detail: 'GLRD69005', download: 'notice' });
    expect(steps[3].waitingOn).toBe('ฝ่ายบัญชี');
    expect(steps.some((s) => s.action)).toBe(false);
  });

  it('there is never more than ONE current step (= one primary button), for any summary in this file', () => {
    const summaries = [chain(), confirmedHere(), confirmedHere({ deposit: { state: 'DRAFT' } }),
      confirmedHere({ deposit: { state: 'PAID' }, remaining: { state: 'TO_CREATE', blockedReason: null, canCreate: true } }),
      confirmedHere({ deposit: { state: 'PAID' }, remaining: { state: 'DRAFT', blockedReason: null } })];
    for (const s of summaries) {
      expect(quotationChainSteps(q(), s).filter((step) => step.state === 'current').length).toBeLessThanOrEqual(1);
    }
  });

  it('a quotation with no deposit percent set is a 30% quotation: step 3 is NOT skipped before the confirm', () => {
    const steps = quotationChainSteps(q({ depositPercent: null }), chain());
    expect(steps[2].state).not.toBe('skipped');
    expect(steps[2].locked).toBe('ยืนยันคำสั่งซื้อก่อน');
  });

  it('a 0% quotation shows step 3 as ไม่รับมัดจำ, skipped, even before the order is confirmed', () => {
    const steps = quotationChainSteps(q({ depositPercent: 0 }), chain());
    expect(steps[2]).toMatchObject({ state: 'skipped', detail: 'ไม่รับมัดจำ' });
  });
});
