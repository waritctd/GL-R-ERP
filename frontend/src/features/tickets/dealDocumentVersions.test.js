import { describe, expect, it } from 'vitest';
import {
  baseNumberOf, dropLegacyDuplicatedByChain, groupChainQuotations,
  groupDepositNotices, groupDirectQuotations, groupRemainingInvoices,
} from './dealDocumentVersions.js';

describe('dealDocumentVersions', () => {
  it('strips only a short revision suffix from the number', () => {
    expect(baseNumberOf('QT-2026-0016-3')).toBe('QT-2026-0016');
    expect(baseNumberOf('QT-2026-0901')).toBe('QT-2026-0901');
    expect(baseNumberOf(null)).toBeNull();
  });

  it('chain: groups by pricingRequestId (numbers may be unrelated), versions newest first, groups by newest date', () => {
    const groups = groupChainQuotations([
      { id: 1, pricingRequestId: 7, number: 'QT-A-1', quotationRevisionNo: 1, issuedAt: '2026-01-01' },
      { id: 2, pricingRequestId: 7, number: 'QT-Z-9', quotationRevisionNo: 2, issuedAt: '2026-01-05' },
      { id: 3, pricingRequestId: 8, number: 'QT-B-1', quotationRevisionNo: 1, issuedAt: '2026-03-01' },
      { id: 4, number: null, quotationRevisionNo: 1, createdAt: '2026-02-01' },
    ]);
    expect(groups.map((g) => g.versions.map((v) => v.id))).toEqual([[3], [4], [2, 1]]);
  });

  it('puts every deposit notice version in one group, version descending', () => {
    const groups = groupDepositNotices([{ id: 1, version: 1 }, { id: 3, version: 3 }, { id: 2, version: 2 }]);
    expect(groups).toHaveLength(1);
    expect(groups[0].versions.map((v) => v.id)).toEqual([3, 2, 1]);
  });

  it('groups remaining invoices by baseNumber; a DRAFT with no baseNumber is its own group', () => {
    const groups = groupRemainingInvoices([
      { id: 1, baseNumber: 'GLR1', version: 1 }, { id: 2, baseNumber: 'GLR1', version: 2 }, { id: 3, baseNumber: null, version: 1 },
    ]);
    expect(groups).toHaveLength(2);
    expect(groups.find((g) => g.key === 'remaining:GLR1').versions.map((v) => v.id)).toEqual([2, 1]);
  });

  it('drops legacy rows shared with the chain by id', () => {
    expect(dropLegacyDuplicatedByChain([{ id: 3 }, { id: 4 }], [{ id: 3 }])).toEqual([{ id: 4 }]);
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

  it('direct base number: when the number does not end with -revisionNo it is returned unchanged (no regex guess)', () => {
    const groups = groupDirectQuotations([
      { id: 1, number: 'QT-A-1', revisionNo: 2 },
      { id: 2, number: 'QT-A-2', revisionNo: 2 },
    ]);
    expect(groups).toHaveLength(2); // QT-A-1 is not a revision-2 suffix, so it is its own base
    expect(groups.map((g) => g.key).sort()).toEqual(['direct:QT-A', 'direct:QT-A-1']);
  });

  it('direct base number: the short-suffix regex applies only when revisionNo is null', () => {
    const groups = groupDirectQuotations([{ id: 1, number: 'QT-A-1', revisionNo: null }, { id: 2, number: 'QT-A-2', revisionNo: null }]);
    expect(groups).toHaveLength(1);
  });
});
