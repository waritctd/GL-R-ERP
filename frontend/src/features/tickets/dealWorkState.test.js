import { describe, expect, it } from 'vitest';
import { DEAL_WORK_STATES, resolveDealWorkState } from './dealWorkState.js';

const salesOwner = { id: 1, role: 'sales', name: 'ฝ่ายขาย' };
const otherSales = { id: 2, role: 'sales', name: 'ฝ่ายขายคนอื่น' };
const importUser = { id: 5, role: 'import', name: 'ฝ่ายนำเข้า' };
const accountUser = { id: 6, role: 'account', name: 'ฝ่ายบัญชี' };
const ceoUser = { id: 9, role: 'ceo', name: 'CEO' };

function summary(overrides = {}) {
  return {
    id: 701,
    code: 'PR-2026-0701',
    status: 'quotation_issued',
    lifecycle: 'ACTIVE',
    salesStage: 'QUOTE_DESIGN_SIDE',
    createdById: 1,
    paymentStatus: null,
    fulfillmentStatus: null,
    amountOutstanding: 0,
    overdue: false,
    closeConfirmedAt: null,
    closeConfirmedByName: null,
    invoiceOnFile: false,
    lostReason: null,
    cancelReason: null,
    ...overrides,
  };
}

function pr(overrides = {}) {
  return {
    id: 11,
    requestCode: 'PCR-11',
    ticketId: 701,
    ticketCreatedById: 1,
    status: 'SUBMITTED',
    createdAt: '2026-07-01T09:00:00.000Z',
    updatedAt: '2026-07-01T09:00:00.000Z',
    assignedImportId: null,
    assignedImportName: null,
    orderConfirmedAt: null,
    ...overrides,
  };
}

function action(actionKey, overrides = {}) {
  return { action: actionKey, kind: 'test', label: actionKey, ...overrides };
}

describe('resolveDealWorkState terminal states', () => {
  it('renders completed records as visibly terminal', () => {
    const state = resolveDealWorkState({
      summary: summary({ lifecycle: 'COMPLETED', status: 'closed' }),
      user: ceoUser,
      availableActions: [action('VERIFY_CLOSE')],
    });

    expect(state.state).toBe(DEAL_WORK_STATES.COMPLETE);
    expect(state.labelTh).toBe('ปิดงานแล้ว');
    expect(state.primaryActionKey).toBeNull();
  });

  it('renders cancelled records as terminal and carries the cancel reason', () => {
    const state = resolveDealWorkState({
      summary: summary({ lifecycle: 'CANCELLED', status: 'cancelled', cancelReason: 'OWNER_CANCELLED' }),
      user: salesOwner,
    });

    expect(state.state).toBe(DEAL_WORK_STATES.CANCELLED);
    expect(state.explanationTh.includes('OWNER_CANCELLED')).toBe(true);
    expect(state.blocker).toBe('OWNER_CANCELLED');
  });

  it('renders lost records as terminal while preserving a supported reopen action', () => {
    const state = resolveDealWorkState({
      summary: summary({ lifecycle: 'CLOSED_LOST', lostReason: 'PRICE' }),
      user: salesOwner,
      availableActions: [action('REOPEN')],
    });

    expect(state.state).toBe(DEAL_WORK_STATES.CANCELLED);
    expect(state.labelTh).toBe('เสียงาน');
    expect(state.explanationTh.includes('ราคา')).toBe(true);
    expect(state.primaryActionKey).toBe('REOPEN');
  });
});

describe('resolveDealWorkState ticket actions', () => {
  it('uses server-offered ticket actions for the current viewer primary action', () => {
    const state = resolveDealWorkState({
      summary: summary({
        paymentStatus: 'FULLY_PAID',
        fulfillmentStatus: 'FULLY_DELIVERED',
        amountOutstanding: 0,
        invoiceOnFile: true,
      }),
      user: accountUser,
      availableActions: [action('CONFIRM_CLOSE')],
    });

    expect(state.state).toBe(DEAL_WORK_STATES.NEEDS_ACTION);
    expect(state.primaryActionKey).toBe('CONFIRM_CLOSE');
    expect(state.responsibleRole).toBe('account');
  });

  it('does not turn utility permissions into a fake needs-action state', () => {
    const state = resolveDealWorkState({
      summary: summary({ paymentStatus: 'CUSTOMER_CONFIRMED', salesStage: 'NEGOTIATION' }),
      user: accountUser,
      availableActions: [action('SET_BILLING'), action('WAIVE_DEPOSIT')],
    });

    expect(state.state).not.toBe(DEAL_WORK_STATES.NEEDS_ACTION);
    expect(state.primaryActionKey).toBeNull();
  });

  it('does not invent close permission when the server has not offered the close action', () => {
    const state = resolveDealWorkState({
      summary: summary({
        paymentStatus: 'FULLY_PAID',
        fulfillmentStatus: 'FULLY_DELIVERED',
        amountOutstanding: 0,
        invoiceOnFile: true,
      }),
      user: accountUser,
      availableActions: [],
    });

    expect(state.state).not.toBe(DEAL_WORK_STATES.NEEDS_ACTION);
    expect(state.primaryActionKey).toBeNull();
  });

  it('does not invent one false owner for multi-role fulfilment actions', () => {
    const state = resolveDealWorkState({
      summary: summary({ paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, salesStage: 'PROCUREMENT' }),
      user: importUser,
      availableActions: [action('ISSUE_IMPORT_REQUEST')],
    });

    expect(state.state).toBe(DEAL_WORK_STATES.NEEDS_ACTION);
    expect(state.primaryActionKey).toBe('ISSUE_IMPORT_REQUEST');
    expect(state.responsibleRole).toBeNull();
  });
});

describe('resolveDealWorkState pricing request actions', () => {
  it('does not rely on legacy ticket.status when a pricing request needs Import pickup', () => {
    const state = resolveDealWorkState({
      summary: summary({ status: 'draft', salesStage: 'QUOTE_DESIGN_SIDE' }),
      pricingRequests: [pr({ status: 'SUBMITTED' })],
      user: importUser,
    });

    expect(state.state).toBe(DEAL_WORK_STATES.NEEDS_ACTION);
    expect(state.primaryActionKey).toBe('PICKUP_PRICING_REQUEST');
    expect(state.responsibleRole).toBe('import');
  });

  it('returns draft for an owning sales rep with an existing draft pricing request', () => {
    const state = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'DRAFT' })],
      user: salesOwner,
    });

    expect(state.state).toBe(DEAL_WORK_STATES.DRAFT);
    expect(state.primaryActionKey).toBe('SUBMIT_PRICING_REQUEST');
  });

  it('returns draft for an owning sales rep before any pricing request exists', () => {
    const state = resolveDealWorkState({
      summary: summary({ status: 'draft', salesStage: 'PRESENTATION' }),
      pricingRequests: [],
      user: salesOwner,
      availableActions: [action('EDIT_ITEMS')],
    });

    expect(state.state).toBe(DEAL_WORK_STATES.DRAFT);
    expect(state.labelTh).toBe('แบบร่าง');
    expect(state.primaryActionKey).toBe('CREATE_PRICING_REQUEST');
  });

  it('routes CEO review states to CEO actions only when the viewer is CEO', () => {
    const ready = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'READY_FOR_CEO_REVIEW' })],
      user: ceoUser,
    });
    const reviewing = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'CEO_REVIEWING' })],
      user: ceoUser,
    });
    const importView = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'READY_FOR_CEO_REVIEW' })],
      user: importUser,
    });

    expect(ready.primaryActionKey).toBe('START_CEO_REVIEW');
    expect(reviewing.primaryActionKey).toBe('DECIDE_PRICING');
    expect(importView.state).toBe(DEAL_WORK_STATES.WAITING);
    expect(importView.responsibleRole).toBe('ceo');
  });

  it('routes approved quotation and accepted order work to the owning sales rep', () => {
    const approved = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'APPROVED_FOR_QUOTATION' })],
      user: salesOwner,
    });
    const accepted = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null })],
      user: salesOwner,
    });
    const nonOwner = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null })],
      user: otherSales,
    });

    expect(approved.primaryActionKey).toBe('CREATE_CUSTOMER_QUOTATION');
    expect(accepted.primaryActionKey).toBe('CONFIRM_ORDER');
    expect(nonOwner.primaryActionKey).toBeNull();
    expect(nonOwner.state).toBe(DEAL_WORK_STATES.WAITING);
  });
});

describe('resolveDealWorkState returned, waiting, overdue and blocked states', () => {
  it('shows returned work with the return reason for Sales information requests', () => {
    const state = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'MORE_INFO_REQUIRED', infoRequestMessage: 'ขอ BOQ ล่าสุด' })],
      user: salesOwner,
    });

    expect(state.state).toBe(DEAL_WORK_STATES.RETURNED);
    expect(state.primaryActionKey).toBe('RESPOND_PRICING_INFO');
    expect(state.explanationTh.includes('ขอ BOQ ล่าสุด')).toBe(true);
    expect(state.blocker).toBe('ขอ BOQ ล่าสุด');
  });

  it('shows returned work with the return reason for Import costing revisions', () => {
    const state = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'COSTING_REVISION_REQUIRED', returnReason: 'ต้นทุนขนส่งไม่ตรง' })],
      user: importUser,
    });

    expect(state.state).toBe(DEAL_WORK_STATES.RETURNED);
    expect(state.primaryActionKey).toBe('REVISE_COSTING');
    expect(state.explanationTh.includes('ต้นทุนขนส่งไม่ตรง')).toBe(true);
  });

  it('shows customer quotation revision requests as returned work only when a reason is present', () => {
    const returned = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({
        status: 'QUOTATION_ISSUED',
        quotationStatus: 'REVISION_REQUESTED',
        outcomeNote: 'ลูกค้าขอแยกส่วนลด',
      })],
      user: salesOwner,
    });
    const missingReason = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'QUOTATION_ISSUED', quotationStatus: 'REVISION_REQUESTED' })],
      user: salesOwner,
    });

    expect(returned.state).toBe(DEAL_WORK_STATES.RETURNED);
    expect(returned.explanationTh.includes('ลูกค้าขอแยกส่วนลด')).toBe(true);
    expect(missingReason.state).toBe(DEAL_WORK_STATES.INFORMATIONAL);
    expect(missingReason.blocker).toBeNull();
  });

  it('keeps waiting ownerless when the data does not support naming one', () => {
    const unassignedImportWork = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'IMPORT_REVIEWING', assignedImportId: null, assignedImportName: null })],
      user: salesOwner,
    });
    const assignedImportWork = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'IMPORT_REVIEWING', assignedImportId: 5, assignedImportName: 'นำเข้า' })],
      user: salesOwner,
    });
    const customerDecision = resolveDealWorkState({
      summary: summary(),
      pricingRequests: [pr({ status: 'QUOTATION_ISSUED' })],
      user: salesOwner,
    });

    expect(unassignedImportWork.state).toBe(DEAL_WORK_STATES.INFORMATIONAL);
    expect(unassignedImportWork.responsibleRole).toBeNull();
    expect(assignedImportWork.state).toBe(DEAL_WORK_STATES.WAITING);
    expect(assignedImportWork.responsibleRole).toBe('import');
    expect(customerDecision.state).toBe(DEAL_WORK_STATES.WAITING);
    expect(customerDecision.responsibleRole).toBeNull();
  });

  it('marks overdue outstanding balances and carries only supported account actions', () => {
    const accountView = resolveDealWorkState({
      summary: summary({ overdue: true, amountOutstanding: 5000, paymentStatus: 'AWAITING_FINAL_PAYMENT' }),
      user: accountUser,
      availableActions: [action('FINAL_PAYMENT')],
    });
    const salesView = resolveDealWorkState({
      summary: summary({ overdue: true, amountOutstanding: 5000, paymentStatus: 'AWAITING_FINAL_PAYMENT' }),
      user: salesOwner,
      availableActions: [],
    });

    expect(accountView.state).toBe(DEAL_WORK_STATES.OVERDUE);
    expect(accountView.primaryActionKey).toBe('FINAL_PAYMENT');
    expect(accountView.responsibleRole).toBe('account');
    expect(salesView.state).toBe(DEAL_WORK_STATES.OVERDUE);
    expect(salesView.primaryActionKey).toBeNull();
  });

  it('blocks paused deals and only exposes resume when the server offers it', () => {
    const state = resolveDealWorkState({
      summary: summary({ lifecycle: 'ON_HOLD' }),
      user: salesOwner,
      availableActions: [action('RESUME'), action('MARK_DORMANT')],
    });

    expect(state.state).toBe(DEAL_WORK_STATES.BLOCKED);
    expect(state.primaryActionKey).toBe('RESUME');
    expect(state.blocker).toBe('ON_HOLD');
  });
});

describe('resolveDealWorkState fallback and purity', () => {
  it('returns a calm neutral fallback for unknown combinations', () => {
    const state = resolveDealWorkState({
      summary: summary({ salesStage: 'UNKNOWN_STAGE', paymentStatus: 'MYSTERY', status: 'weird' }),
      pricingRequests: [pr({ status: 'UNMODELLED_STATUS' })],
      user: ceoUser,
    });

    expect(state.state).toBe(DEAL_WORK_STATES.INFORMATIONAL);
    expect(state.primaryActionKey).toBeNull();
    expect(state.responsibleRole).toBeNull();
  });

  it('does not mutate input arrays or records', () => {
    const p = pr({ status: 'SUBMITTED' });
    const actions = [action('ISSUE_IMPORT_REQUEST')];
    const pricingRequests = [p];

    resolveDealWorkState({
      summary: summary({ status: 'draft' }),
      pricingRequests,
      availableActions: actions,
      user: importUser,
    });

    expect(pricingRequests).toEqual([p]);
    expect(actions).toEqual([action('ISSUE_IMPORT_REQUEST')]);
  });

  it('returns informational fallback when no summary is provided', () => {
    const state = resolveDealWorkState();

    expect(state.state).toBe(DEAL_WORK_STATES.INFORMATIONAL);
    expect(state.primaryActionKey).toBeNull();
  });
});
