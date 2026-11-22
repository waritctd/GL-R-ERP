import { describe, expect, it } from 'vitest';
import {
  groupChainQuotations,
  groupDirectQuotations, groupRemainingInvoices, realBeforeSamples,
} from './dealDocumentVersions.js';

describe('dealDocumentVersions', () => {
  it('chain: groups by pricingRequestId (numbers may be unrelated), versions newest first, groups by newest date', () => {
    const groups = groupChainQuotations([
      { id: 1, pricingRequestId: 7, number: 'QT-A-1', quotationRevisionNo: 1, issuedAt: '2026-01-01' },
      { id: 2, pricingRequestId: 7, number: 'QT-Z-9', quotationRevisionNo: 2, issuedAt: '2026-01-05' },
      { id: 3, pricingRequestId: 8, number: 'QT-B-1', quotationRevisionNo: 1, issuedAt: '2026-03-01' },
      { id: 4, number: null, quotationRevisionNo: 1, createdAt: '2026-02-01' },
    ]);
    expect(groups.map((g) => g.versions.map((v) => v.id))).toEqual([[3], [4], [2, 1]]);
  });

  it('a DRAFT that joins a group refreshes that group sort time (older group with the newest draft sorts first)', () => {
    const groups = groupRemainingInvoices([
      { id: 1, baseNumber: 'GLR-A', version: 1, status: 'ISSUED', customerQuotationId: 1, issuedAt: '2026-01-01' },
      { id: 2, baseNumber: 'GLR-B', version: 1, status: 'ISSUED', customerQuotationId: 2, issuedAt: '2026-03-01' },
      { id: 3, baseNumber: null, version: 2, status: 'DRAFT', customerQuotationId: 1, createdAt: '2026-05-01' },
    ]);
    expect(groups.map((g) => g.key)).toEqual(['remaining:GLR-A', 'remaining:GLR-B']);
    expect(groups[0].versions.map((v) => v.id)).toEqual([3, 1]);
  });

  // ── grouping is per RECIPIENT, not per base (owner ruling 2026-10-17) ──────────────────────
  it('keeps a sample out of the real quotation\'s versions and lists it after real documents', () => {
    const groups = realBeforeSamples(groupDirectQuotations([
      { id: 1, number: 'QT-2026-0002-1', revisionNo: 1, recipientType: 'BUYER', docStatus: 'DRAFT', createdAt: '2026-11-22T01:00:00Z' },
      { id: 2, number: 'QT-2026-0002-2', revisionNo: 2, recipientType: 'BUYER', docStatus: 'DRAFT', isSample: true, createdAt: '2026-11-23T01:00:00Z' },
    ]));
    expect(groups.map((g) => g.versions.map((v) => v.id))).toEqual([[1], [2]]);
  });

  // Why: see groupDirectQuotations — the base is deal-wide, so the recipient identifies a document.
  it('splits two recipients sharing one deal family into separate documents', () => {
    const groups = groupDirectQuotations([
      { id: 1, number: 'QT-2026-0014-1', revisionNo: 1, recipientType: 'DESIGNER', createdAt: '2026-10-01' },
      { id: 2, number: 'QT-2026-0014-2', revisionNo: 2, recipientType: 'OWNER', createdAt: '2026-10-02' },
    ]);

    expect(groups).toHaveLength(2);
    expect(groups.map((g) => g.key).sort())
      .toEqual(['direct:QT-2026-0014:DESIGNER', 'direct:QT-2026-0014:OWNER']);
    // Neither is nested under the other — one version each.
    expect(groups.map((g) => g.versions.length)).toEqual([1, 1]);
  });

  // ── the head row is the version in force, not merely the newest (PR-2026-0289) ──────────────
  // Confirming the order from -3 auto-cancelled the open -4 draft; newest-first put the CANCELLED -4 on top.
  const DEAL_0289 = [
    { id: 426, number: 'QT-2026-0290-1', revisionNo: 1, docStatus: 'SUPERSEDED', recipientType: 'OWNER' },
    { id: 429, number: 'QT-2026-0290-2', revisionNo: 2, docStatus: 'SUPERSEDED', recipientType: 'OWNER' },
    { id: 430, number: 'QT-2026-0290-3', revisionNo: 3, docStatus: 'APPROVED', recipientType: 'OWNER' },
    { id: 437, number: 'QT-2026-0290-4', revisionNo: 4, docStatus: 'CANCELLED', recipientType: 'OWNER' },
  ];

  it('direct: the version the order was confirmed from leads, then the rest newest first', () => {
    const [group] = groupDirectQuotations(DEAL_0289, { orderConfirmedQuotationId: 430 });
    expect(group.versions.map((v) => v.id)).toEqual([430, 437, 429, 426]);
  });
});
