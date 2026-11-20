import { describe, expect, it } from 'vitest';
import {
  canAttachToDealQuotation, canRemoveDealQuotationAttachment, canSeeDealQuotationAttachments,
} from './quotationMeta.js';

// Which เอกสารแนบ controls the quotation page offers to whom. These three predicates only decide
// what is DRAWN. DealQuotationAttachmentService enforces the rule, and
// DealQuotationAttachmentAuthzIntegrationTest proves it against real Postgres. Nothing here is
// authorization evidence.
//
// The rule they echo:
//   see     whoever may view the quotation, except the roles account and import (grant or not)
//   attach  whoever may edit it, while it is DRAFT or PENDING_APPROVAL
//   remove  whoever may edit it, while it is DRAFT, and only a file stored on THIS quotation

const owner = { id: 6, role: 'sales' };
const manager = { id: 9, role: 'sales_manager' };
const ceo = { id: 8, role: 'ceo' };
const grantedQc = { id: 3, role: 'qc', canCreateQuotation: true };
/** A quotation owned by `owner`. salesRepId is the deal's creator (see quotationMeta.js). */
const quotation = (docStatus) => ({ id: 41, docStatus, salesRepId: 6, origin: 'DEAL_DIRECT' });
const ownFile = { id: 71, quotationId: 41, inherited: false };
// Every value of th.co.glr.hr.ticket.QuotationStatus, the ones a direct quotation never reaches too.
const EVERY_STATUS = [
  'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SUPERSEDED', 'CANCELLED', 'ISSUED', 'SENT', 'ACCEPTED',
  'REJECTED', 'EXPIRED', 'READY_TO_ISSUE', 'REVISION_REQUESTED',
];

describe('canSeeDealQuotationAttachments', () => {
  it.each([
    ['the owning rep', owner], ['the sales manager', manager], ['the CEO', ceo], ['a grant holder', grantedQc],
  ])('%s sees the panel', (_who, user) => {
    expect(canSeeDealQuotationAttachments(user)).toBe(true);
  });
});

describe('canAttachToDealQuotation', () => {
  it.each([
    ['the owning rep', owner], ['the sales manager', manager], ['a grant holder', grantedQc],
  ])('%s attaches to a draft, and still while it awaits approval', (_who, user) => {
    expect(canAttachToDealQuotation(user, quotation('DRAFT'))).toBe(true);
    expect(canAttachToDealQuotation(user, quotation('PENDING_APPROVAL'))).toBe(true);
  });
});

describe('canRemoveDealQuotationAttachment', () => {
  it.each([
    ['the owning rep', owner], ['the sales manager', manager], ['a grant holder', grantedQc],
  ])('%s removes a file of a draft', (_who, user) => {
    expect(canRemoveDealQuotationAttachment(user, quotation('DRAFT'), ownFile)).toBe(true);
  });

  it('nothing is removed once the quotation has left DRAFT, not even while it awaits approval', () => {
    for (const status of EVERY_STATUS.filter((s) => s !== 'DRAFT')) {
      for (const user of [owner, manager, grantedQc]) {
        expect(canRemoveDealQuotationAttachment(user, quotation(status), ownFile), `${user.role} on ${status}`)
          .toBe(false);
      }
    }
  });
});
