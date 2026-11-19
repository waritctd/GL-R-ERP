import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { dealInScope, SALES_VIEW_SECTION_IDS, visibleSections } from './salesViewScope.js';

// The stage order/phases dealInScope keys off are the backend's now (GET /api/meta/deal-stages).
// DEAL_STAGE_CATALOG is the same canned payload mockApi serves, and stageCatalog.test.js proves it matches
// DealStage.java — so passing it here is not a fixture inventing its own pipeline.
const catalog = DEAL_STAGE_CATALOG;

describe('visibleSections', () => {
  it('ceo and sales_manager are an unconditional pass-through', () => {
    for (const role of ['ceo', 'sales_manager']) {
      const sections = visibleSections(role);
      for (const id of SALES_VIEW_SECTION_IDS) {
        expect(sections[id], `${role}.${id}`).toBe(true);
      }
    }
  });

  it('import does NOT see quotations, deposit notice, payment, or the pulled-in deal-quotation section', () => {
    const sections = visibleSections('import');
    expect(sections.quotation).toBe(false);
    expect(sections.depositNotice).toBe(false);
    expect(sections.payment).toBe(false);
    expect(sections.dealQuotation).toBe(false);
  });

  it('account does NOT see the pricing-request panel', () => {
    const sections = visibleSections('account');
    expect(sections.pricingRequest).toBe(false);
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

  it('import: a deal still in the lead/presentation phase (no pricing work yet) is NOT in scope', () => {
    expect(dealInScope('import', { ...baseDeal, salesStage: 'LEAD_APPROACH' }, catalog)).toBe(false);
    expect(dealInScope('import', { ...baseDeal, salesStage: 'PRESENTATION' }, catalog)).toBe(false);
  });

  it('import: a closed/lost/cancelled deal is never in scope even at a late stage', () => {
    expect(dealInScope('import', { ...baseDeal, salesStage: 'PROCUREMENT', lifecycle: 'CLOSED_LOST' }, catalog)).toBe(false);
    expect(dealInScope('import', { ...baseDeal, salesStage: 'PROCUREMENT', status: 'cancelled' }, catalog)).toBe(false);
  });

  it('account: an awaited deposit or final-payment confirmation IS in scope', () => {
    expect(dealInScope('account', { ...baseDeal, paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }, catalog)).toBe(true);
    expect(dealInScope('account', { ...baseDeal, paymentStatus: 'AWAITING_FINAL_PAYMENT' }, catalog)).toBe(true);
  });
});
