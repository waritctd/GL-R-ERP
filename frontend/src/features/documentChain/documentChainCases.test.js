import { describe, expect, it } from 'vitest';
import cases from './documentChainCases.json';
import {
  chainDepositBlockedReason, chainDepositState, chainListStage, chainRemainingBlockedReason, chainRemainingState,
} from '../../api/mockDocumentChainRules.js';

// The mock's mirror of DocumentChainRules against the ONE state table the Java rules are pinned to
// (DocumentChainRulesTest). A rule changed on one side only fails one of the two tests.
const facts = (f) => ({
  noticeDraft: false, noticeIssued: false, invoiceDraft: false, invoiceIssued: false, ...f,
});

describe('mockDocumentChainRules follows documentChainCases.json', () => {
  it.each(cases.chain.map((c) => [c.name, c]))('%s', (_name, c) => {
    const f = facts(c.facts);
    expect({
      depositState: chainDepositState(f),
      depositBlockedReason: chainDepositBlockedReason(f),
      remainingState: chainRemainingState(f),
      remainingBlockedReason: chainRemainingBlockedReason(f),
    }).toEqual({
      depositState: c.depositState,
      depositBlockedReason: c.depositBlockedReason,
      remainingState: c.remainingState,
      remainingBlockedReason: c.remainingBlockedReason,
    });
  });

  it.each(cases.listStage.map((c) => [c.name, c]))('list: %s', (_name, c) => {
    expect(chainListStage(facts(c.facts), c.promoted, c.confirmedQuotationId, c.quotationId)).toBe(c.stage);
  });
});
