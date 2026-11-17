import { describe, expect, it } from 'vitest';
import { quotationChainSteps, quotationChainView } from './quotationChain.js';
import { chainNextStep } from '../documentChain/documentChainText.js';

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

  it('a PRICING_REQUEST-origin quotation shows nothing (that route confirms on the request page)', () => {
    expect(quotationChainView(q({ origin: 'PRICING_REQUEST' }), chain(), { dealHref: DEAL }).mode).toBe('none');
  });

  it('no summary (loading, or a viewer the document gate refuses) shows nothing', () => {
    expect(quotationChainView(q(), null, { dealHref: DEAL }).mode).toBe('none');
  });

  it('confirmed from a DIFFERENT quotation: one line naming it, linking to it', () => {
    const view = quotationChainView(q(), confirmedHere({ order: { confirmedFromQuotationId: 9, confirmedFromQuotationNumber: 'QT-2026-0009-1' } }), { dealHref: DEAL });
    expect(view.mode).toBe('notice');
    expect(view.reason).toBe('OTHER_QUOTATION');
    expect(view.text).toContain('QT-2026-0009-1');
    expect(view.link).toEqual({ to: '/quotations/9', label: 'เปิด QT-2026-0009-1' });
  });

  it('a SUPERSEDED revision: one line, linking to the quotation that carries the chain when there is one', () => {
    const superseded = quotationChainView(q({ docStatus: 'SUPERSEDED' }), confirmedHere({ order: { confirmedFromQuotationId: 9, confirmedFromQuotationNumber: 'QT-2026-0005-2' } }), { dealHref: DEAL });
    expect(superseded).toMatchObject({ mode: 'notice', reason: 'SUPERSEDED', link: { to: '/quotations/9' } });
    const noChainYet = quotationChainView(q({ docStatus: 'SUPERSEDED' }), chain(), { dealHref: DEAL });
    expect(noChainYet).toMatchObject({ mode: 'notice', reason: 'SUPERSEDED', link: { to: DEAL, label: 'เปิดงาน' } });
  });

  it('a CANCELLED quotation: one line, linking to the deal', () => {
    expect(quotationChainView(q({ docStatus: 'CANCELLED' }), chain(), { dealHref: DEAL }))
      .toMatchObject({ mode: 'notice', reason: 'CANCELLED', link: { to: DEAL, label: 'เปิดงาน' } });
  });

  it.each([
    ['CLOSED_LOST', 'draft', 'DEAL_LOST'],
    ['CANCELLED', 'cancelled', 'DEAL_CANCELLED'],
    ['ON_HOLD', 'draft', 'DEAL_PAUSED'],
    ['DORMANT', 'draft', 'DEAL_PAUSED'],
  ])('a deal at lifecycle %s: one line, never a chain with buttons', (lifecycle, dealStatus, reason) => {
    const view = quotationChainView(q(), chain({ lifecycle, dealStatus, order: { confirmable: false } }), { dealHref: DEAL });
    expect(view).toMatchObject({ mode: 'notice', reason, link: { to: DEAL, label: 'เปิดงาน' } });
  });

  it('the same for a quotation the order WAS confirmed from, once the deal is lost or paused', () => {
    expect(quotationChainView(q(), confirmedHere({ lifecycle: 'CLOSED_LOST' }), { dealHref: DEAL }).reason).toBe('DEAL_LOST');
    expect(quotationChainView(q(), confirmedHere({ lifecycle: 'ON_HOLD' }), { dealHref: DEAL }).reason).toBe('DEAL_PAUSED');
  });

  it('confirmed some other way (no quotation named, or not one this viewer may see): one line to the deal', () => {
    const view = quotationChainView(q(), confirmedHere({ order: { confirmedFromQuotationId: null, confirmedFromQuotationNumber: null } }), { dealHref: DEAL });
    expect(view).toMatchObject({ mode: 'notice', reason: 'CONFIRMED_ELSEWHERE', link: { to: DEAL } });
  });

  it('a viewer who cannot open the deal gets the line with no link', () => {
    const view = quotationChainView(q({ docStatus: 'CANCELLED' }), chain(), { dealHref: null });
    expect(view.link).toBeNull();
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

  // Fix round 1, F-5: a BLANK percent is the default 30% (DealQuotationRenderAdapter.effectiveDepositPercent), not 0%.
  it('a quotation with no deposit percent set is a 30% quotation: step 3 is NOT skipped before the confirm', () => {
    const steps = quotationChainSteps(q({ depositPercent: null }), chain());
    expect(steps[2].state).not.toBe('skipped');
    expect(steps[2].locked).toBe('ยืนยันคำสั่งซื้อก่อน');
  });

  it('a 0% quotation shows step 3 as ไม่รับมัดจำ, skipped, even before the order is confirmed', () => {
    const steps = quotationChainSteps(q({ depositPercent: 0 }), chain());
    expect(steps[2]).toMatchObject({ state: 'skipped', detail: 'ไม่รับมัดจำ' });
  });

  it('confirmed, no notice yet, the owner: step 3 is current with the create action', () => {
    const steps = quotationChainSteps(q(), confirmedHere());
    expect(stateOf(steps)).toEqual(['done', 'done', 'current', 'todo']);
    expect(steps[1].meta).toMatch(/^ยืนยันแล้ว · /);
    expect(steps[2]).toMatchObject({ action: 'deposit', actionLabel: 'สร้างใบแจ้งยอดเงินรับมัดจำ' });
    expect(steps[3].locked).toBe('ออกได้หลังฝ่ายบัญชียืนยันรับมัดจำ');
  });

  it('a draft notice: the action issues the existing draft', () => {
    const steps = quotationChainSteps(q(), confirmedHere({ deposit: { state: 'DRAFT', draftExists: true, noticeId: 31 } }));
    expect(steps[2]).toMatchObject({ state: 'current', action: 'deposit', actionLabel: 'ออกใบแจ้งยอดเงินรับมัดจำ' });
  });

  it('confirmed, no notice, a viewer who may not write it (manager): step 3 waits on sales', () => {
    const steps = quotationChainSteps(q(), confirmedHere({ viewer: { mayWriteDepositNotice: false } }));
    expect(steps[2]).toMatchObject({ state: 'waiting', waitingOn: 'ฝ่ายขายเจ้าของงาน' });
    expect(steps.some((s) => s.action)).toBe(false);
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

  it('deposit received, goods not ready: step 4 waits with the true reason', () => {
    const steps = quotationChainSteps(q(), confirmedHere({
      deposit: { state: 'PAID', received: true, docNumber: 'GLRD69005', issuedNoticeId: 31 },
      remaining: { state: 'TO_CREATE', blockedReason: 'GOODS_NOT_READY' },
    }));
    expect(steps[2].meta).toContain('รับเงินแล้ว');
    expect(steps[3]).toMatchObject({ state: 'waiting', locked: 'ออกได้เมื่อสินค้าถึงขั้นรับ/ส่งมอบแล้ว' });
  });

  it('the invoice can be made: step 4 current with the invoice action', () => {
    const steps = quotationChainSteps(q(), confirmedHere({
      deposit: { state: 'PAID', received: true, docNumber: 'GLRD69005', issuedNoticeId: 31 },
      remaining: { state: 'TO_CREATE', blockedReason: null, canCreate: true },
    }));
    expect(steps[3]).toMatchObject({ state: 'current', action: 'remaining', actionLabel: 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' });
  });

  it('the invoice issued: every step done, the invoice downloadable', () => {
    const steps = quotationChainSteps(q(), confirmedHere({
      deposit: { state: 'PAID', received: true, docNumber: 'GLRD69005', issuedNoticeId: 31 },
      remaining: { state: 'ISSUED', blockedReason: null, invoiceId: 900, docNumber: 'GLR6900014-1', docDate: '2026-11-20' },
    }));
    expect(stateOf(steps)).toEqual(['done', 'done', 'done', 'done']);
    expect(steps[3]).toMatchObject({ detail: 'GLR6900014-1', download: 'invoice' });
  });

  it('no deposit, stock goods: step 3 skipped, step 4 current', () => {
    const steps = quotationChainSteps(q({ depositPercent: 0 }), confirmedHere({
      deposit: { state: 'NOT_REQUIRED', required: false, percent: 0 },
      remaining: { state: 'TO_CREATE', blockedReason: null, canCreate: true },
    }));
    expect(stateOf(steps)).toEqual(['done', 'done', 'skipped', 'current']);
  });

  it('paid in full by the deposit: step 4 skipped as not needed', () => {
    const steps = quotationChainSteps(q(), confirmedHere({
      deposit: { state: 'PAID', received: true, docNumber: 'GLRD69005', issuedNoticeId: 31 },
      remaining: { state: 'NOT_NEEDED', blockedReason: null },
    }));
    expect(steps[3]).toMatchObject({ state: 'skipped', detail: 'ไม่ต้องออก — ชำระครบจากมัดจำ' });
  });

  it('there is never more than ONE current step (= one primary button), for any summary in this file', () => {
    const summaries = [chain(), confirmedHere(), confirmedHere({ deposit: { state: 'DRAFT' } }),
      confirmedHere({ deposit: { state: 'PAID' }, remaining: { state: 'TO_CREATE', blockedReason: null, canCreate: true } }),
      confirmedHere({ deposit: { state: 'PAID' }, remaining: { state: 'DRAFT', blockedReason: null } })];
    for (const s of summaries) {
      expect(quotationChainSteps(q(), s).filter((step) => step.state === 'current').length).toBeLessThanOrEqual(1);
    }
  });

  it('the step marked current/waiting is the one chainNextStep names — the same function the deal page reads', () => {
    for (const s of [chain(), confirmedHere(), confirmedHere({ deposit: { state: 'ISSUED' } })]) {
      const next = chainNextStep(s);
      const steps = quotationChainSteps(q(), s);
      expect(['current', 'waiting']).toContain(steps[next.index].state);
    }
  });
});
