import { describe, expect, it } from 'vitest';
import { dealInScope, SALES_VIEW_SECTION_IDS, visibleSections } from './salesViewScope.js';

// Presentation-only module — see salesViewScope.js's own doc comment. These
// tests assert what a role's screen renders, not what the server allows; the
// wrong-way-round cases below are the ones that matter (a role must NOT see
// a section it has no business in), not merely that each role sees its own.

describe('visibleSections', () => {
  it('ceo and sales_manager are an unconditional pass-through', () => {
    for (const role of ['ceo', 'sales_manager']) {
      const sections = visibleSections(role);
      for (const id of SALES_VIEW_SECTION_IDS) {
        expect(sections[id], `${role}.${id}`).toBe(true);
      }
    }
  });

  it('sales sees its own full pipeline', () => {
    const sections = visibleSections('sales');
    for (const id of SALES_VIEW_SECTION_IDS) {
      expect(sections[id], `sales.${id}`).toBe(true);
    }
  });

  it('import does NOT see quotations, deposit notice, payment, or the pulled-in deal-quotation section', () => {
    const sections = visibleSections('import');
    expect(sections.quotation).toBe(false);
    expect(sections.depositNotice).toBe(false);
    expect(sections.payment).toBe(false);
    expect(sections.dealQuotation).toBe(false);
  });

  it('import DOES see the pricing-request/factory-quote/costing panel and procurement/fulfilment', () => {
    const sections = visibleSections('import');
    expect(sections.pricingRequest).toBe(true);
    expect(sections.delivery).toBe(true);
  });

  it('account does NOT see the pricing-request panel', () => {
    const sections = visibleSections('account');
    expect(sections.pricingRequest).toBe(false);
  });

  // Phase 3 Slice S4 (handoff 105): account now sees DealFulfilmentPanel too
  // (read-only — no `can.*` action inside it ever resolves true for
  // account's role), since the deal's fulfilment progress is exactly what
  // gates the final-payment confirmation account is waiting to make.
  it('account DOES see the fulfilment/delivery section', () => {
    const sections = visibleSections('account');
    expect(sections.delivery).toBe(true);
  });

  it('account DOES see the payment section, legacy quotations (for the total), and the deal-quotation section', () => {
    const sections = visibleSections('account');
    expect(sections.payment).toBe(true);
    expect(sections.quotation).toBe(true);
    expect(sections.dealQuotation).toBe(true);
  });

  it('an unexpected role (never reaches this page in production) defaults to nothing rather than leaking a section', () => {
    const sections = visibleSections('hr');
    for (const id of SALES_VIEW_SECTION_IDS) {
      expect(sections[id], `hr.${id}`).toBe(false);
    }
  });
});

describe('dealInScope', () => {
  const baseDeal = {
    lifecycle: 'ACTIVE',
    status: 'in_review',
    salesStage: 'NEGOTIATION',
    paymentStatus: null,
    overdue: false,
  };

  it('ceo and sales_manager see every deal', () => {
    const deal = { ...baseDeal, salesStage: 'LEAD_APPROACH' };
    expect(dealInScope('ceo', deal)).toBe(true);
    expect(dealInScope('sales_manager', deal)).toBe(true);
    expect(dealInScope('ceo', { ...deal, lifecycle: 'CLOSED_LOST' })).toBe(true);
  });

  it('sales sees every deal it is handed (the list endpoint already scoped it to its own)', () => {
    expect(dealInScope('sales', baseDeal)).toBe(true);
  });

  it('returns false for a missing deal regardless of role', () => {
    expect(dealInScope('ceo', null)).toBe(false);
    expect(dealInScope('import', undefined)).toBe(false);
  });

  it('import: a deal still in the lead/presentation phase (no pricing work yet) is NOT in scope', () => {
    expect(dealInScope('import', { ...baseDeal, salesStage: 'LEAD_APPROACH' })).toBe(false);
    expect(dealInScope('import', { ...baseDeal, salesStage: 'PRESENTATION' })).toBe(false);
  });

  it('import: phase 2/3 quote stages (pricing work likely in flight) ARE in scope', () => {
    expect(dealInScope('import', { ...baseDeal, salesStage: 'SPEC_APPROVED' })).toBe(true);
    expect(dealInScope('import', { ...baseDeal, salesStage: 'NEGOTIATION' })).toBe(true);
  });

  it('import: salesStage index >= PROCUREMENT is in scope, even though phase 4 order/deposit stages are not', () => {
    expect(dealInScope('import', { ...baseDeal, salesStage: 'ORDER_RECEIVED' })).toBe(false);
    expect(dealInScope('import', { ...baseDeal, salesStage: 'DEPOSIT_RECEIVED' })).toBe(false);
    expect(dealInScope('import', { ...baseDeal, salesStage: 'PROCUREMENT' })).toBe(true);
    expect(dealInScope('import', { ...baseDeal, salesStage: 'DELIVERED' })).toBe(true);
  });

  it('import: a closed/lost/cancelled deal is never in scope even at a late stage', () => {
    expect(dealInScope('import', { ...baseDeal, salesStage: 'PROCUREMENT', lifecycle: 'CLOSED_LOST' })).toBe(false);
    expect(dealInScope('import', { ...baseDeal, salesStage: 'PROCUREMENT', status: 'cancelled' })).toBe(false);
  });

  it('account: no money action pending means NOT in scope', () => {
    expect(dealInScope('account', { ...baseDeal, paymentStatus: null, overdue: false })).toBe(false);
    expect(dealInScope('account', { ...baseDeal, paymentStatus: 'CUSTOMER_CONFIRMED', overdue: false })).toBe(false);
  });

  it('account: an awaited deposit or final-payment confirmation IS in scope', () => {
    expect(dealInScope('account', { ...baseDeal, paymentStatus: 'DEPOSIT_NOTICE_ISSUED' })).toBe(true);
    expect(dealInScope('account', { ...baseDeal, paymentStatus: 'AWAITING_FINAL_PAYMENT' })).toBe(true);
  });

  it('account: an overdue outstanding balance IS in scope regardless of paymentStatus', () => {
    expect(dealInScope('account', { ...baseDeal, paymentStatus: 'FULLY_PAID', overdue: true })).toBe(true);
  });

  it('an unexpected role never gets a deal placed in its scope', () => {
    expect(dealInScope('hr', { ...baseDeal, paymentStatus: 'DEPOSIT_NOTICE_ISSUED', overdue: true })).toBe(false);
  });
});
