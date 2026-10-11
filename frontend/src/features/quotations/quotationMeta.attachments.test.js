import { describe, expect, it } from 'vitest';
import {
  buildQuotationChecklist, canAttachToDealQuotation, canRemoveDealQuotationAttachment,
  canSeeDealQuotationAttachments, QUOTATION_BLOCKING_CHECKS, QUOTATION_CHECK,
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
const otherRep = { id: 7, role: 'sales' };
const manager = { id: 9, role: 'sales_manager' };
const ceo = { id: 8, role: 'ceo' };
const account = { id: 5, role: 'account' };
const importer = { id: 4, role: 'import' };
const grantedQc = { id: 3, role: 'qc', canCreateQuotation: true };
const accountWithGrant = { id: 2, role: 'account', canCreateQuotation: true };
const importWithGrant = { id: 12, role: 'import', canCreateQuotation: true };
const employee = { id: 1, role: 'employee' };

/** A quotation owned by `owner`. salesRepId is the deal's creator (see quotationMeta.js). */
const quotation = (docStatus) => ({ id: 41, docStatus, salesRepId: 6, origin: 'DEAL_DIRECT' });
const ownFile = { id: 71, quotationId: 41, inherited: false };
const inheritedFile = { id: 55, quotationId: 40, inherited: true };

const EVERY_STATUS = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SUPERSEDED', 'CANCELLED', 'ISSUED', 'EXPIRED'];

describe('canSeeDealQuotationAttachments', () => {
  it.each([
    ['the owning rep', owner], ['the sales manager', manager], ['the CEO', ceo], ['a grant holder', grantedQc],
  ])('%s sees the panel', (_who, user) => {
    expect(canSeeDealQuotationAttachments(user)).toBe(true);
  });

  // Wrong way round. account and import open the quotation itself on main; the vendor's cost
  // document is not for them, and a grant does not change that.
  it.each([
    ['account', account], ['import', importer], ['account holding the grant', accountWithGrant],
    ['import holding the grant', importWithGrant], ['a plain employee', employee], ['nobody', null],
  ])('%s does not', (_who, user) => {
    expect(canSeeDealQuotationAttachments(user)).toBe(false);
  });
});

describe('canAttachToDealQuotation', () => {
  it.each([
    ['the owning rep', owner], ['the sales manager', manager], ['a grant holder', grantedQc],
  ])('%s attaches to a draft, and still while it awaits approval', (_who, user) => {
    expect(canAttachToDealQuotation(user, quotation('DRAFT'))).toBe(true);
    expect(canAttachToDealQuotation(user, quotation('PENDING_APPROVAL'))).toBe(true);
  });

  it('in no other status, whoever asks', () => {
    for (const status of EVERY_STATUS.filter((s) => s !== 'DRAFT' && s !== 'PENDING_APPROVAL')) {
      for (const user of [owner, manager, grantedQc]) {
        expect(canAttachToDealQuotation(user, quotation(status)), `${user.role} on ${status}`).toBe(false);
      }
    }
  });

  // The CEO approves and reads; another rep does neither; account and import are out entirely.
  it.each([
    ['the CEO, who reads but does not edit', ceo], ['another rep', otherRep], ['account', account],
    ['import', importer], ['account holding the grant', accountWithGrant],
    ['import holding the grant', importWithGrant], ['a plain employee', employee], ['nobody', null],
  ])('%s never attaches', (_who, user) => {
    expect(canAttachToDealQuotation(user, quotation('DRAFT'))).toBe(false);
    expect(canAttachToDealQuotation(user, quotation('PENDING_APPROVAL'))).toBe(false);
  });

  it('nothing can be attached to a quotation that has never been saved', () => {
    expect(canAttachToDealQuotation(owner, null)).toBe(false);
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

  it('a file inherited from an earlier document is not removable from the revision, draft or not', () => {
    for (const user of [owner, manager, grantedQc]) {
      expect(canRemoveDealQuotationAttachment(user, quotation('DRAFT'), inheritedFile), user.role).toBe(false);
    }
    // Control: the same callers, the same draft, a file of its own.
    expect(canRemoveDealQuotationAttachment(owner, quotation('DRAFT'), ownFile)).toBe(true);
  });

  it.each([
    ['the CEO', ceo], ['another rep', otherRep], ['account', account], ['import', importer],
    ['account holding the grant', accountWithGrant], ['a plain employee', employee], ['nobody', null],
  ])('%s never removes', (_who, user) => {
    expect(canRemoveDealQuotationAttachment(user, quotation('DRAFT'), ownFile)).toBe(false);
  });
});

// "this can be optional": a quotation with no file saves and submits exactly as before. This is a
// REGRESSION GUARD, green before the feature exists and meant to stay green. It fails the day
// someone adds an attachment entry to the checklist, which is what disables บันทึกร่าง and
// ส่งขออนุมัติ.
describe('the checklist knows nothing about เอกสารแนบ', () => {
  it('has no check, blocking or not, that mentions a file', () => {
    const names = [...Object.keys(QUOTATION_CHECK), ...Object.values(QUOTATION_CHECK), ...QUOTATION_BLOCKING_CHECKS];
    expect(names.filter((name) => /attach|file|แนบ/i.test(String(name)))).toEqual([]);
  });

  it('never produces an entry about a file, with or without one attached', () => {
    const entries = buildQuotationChecklist({});
    expect(entries.filter((entry) => /แนบ|ไฟล์|เอกสารแนบ/.test(`${entry.check} ${entry.message}`))).toEqual([]);
  });
});
