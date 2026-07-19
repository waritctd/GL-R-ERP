import { describe, expect, it } from 'vitest';
import {
  allowedTargetStages, canMarkLost, canSetStage, isRoutineBackwardMove, nextStage,
  SALES_STAGES, stageIndex,
} from './stageMeta.js';

// NOTE: these gates only drive what the UI offers — the authoritative rules are
// TicketService's deal-pipeline gates, covered by TicketServiceTest on the backend.

const owner = { id: 6, role: 'sales' };
const otherSales = { id: 2, role: 'sales' };
const salesManager = { id: 9, role: 'sales_manager' };
const importUser = { id: 7, role: 'import' };
const accountUser = { id: 11, role: 'account' };
const ceo = { id: 8, role: 'ceo' };

const deal = { salesStage: 'PRESENTATION', createdById: 6 };

describe('stageMeta', () => {
  it('defines the 14-stage model in order', () => {
    expect(SALES_STAGES).toHaveLength(14);
    expect(SALES_STAGES[0].code).toBe('LEAD_APPROACH');
    expect(SALES_STAGES[13].code).toBe('CLOSED_PAID');
    expect(stageIndex('ORDER_RECEIVED')).toBe(8);
    expect(nextStage('CLOSED_PAID')).toBeNull();
  });

  it('sales owner may set sales stages but never money/import stages', () => {
    expect(canSetStage(owner, deal, 'SPEC_APPROVED')).toBe(true);
    expect(canSetStage(owner, deal, 'DELIVERED')).toBe(true);
    expect(canSetStage(owner, deal, 'DEPOSIT_RECEIVED')).toBe(false);
    expect(canSetStage(owner, deal, 'PROCUREMENT')).toBe(false);
    expect(canSetStage(owner, deal, 'CLOSED_PAID')).toBe(false);
  });

  it('non-owner sales gets nothing; sales_manager and ceo pass sales stages', () => {
    expect(canSetStage(otherSales, deal, 'SPEC_APPROVED')).toBe(false);
    expect(allowedTargetStages(otherSales, deal)).toHaveLength(0);
    expect(canSetStage(salesManager, deal, 'SPEC_APPROVED')).toBe(true);
    expect(canSetStage(ceo, deal, 'SPEC_APPROVED')).toBe(true);
  });

  it('account and import fall back only on their own stages', () => {
    expect(canSetStage(accountUser, deal, 'DEPOSIT_RECEIVED')).toBe(true);
    expect(canSetStage(accountUser, deal, 'CLOSED_PAID')).toBe(true);
    expect(canSetStage(accountUser, deal, 'SPEC_APPROVED')).toBe(false);
    expect(canSetStage(importUser, deal, 'PROCUREMENT')).toBe(true);
    expect(canSetStage(importUser, deal, 'SPEC_APPROVED')).toBe(false);
    expect(canSetStage(ceo, deal, 'PROCUREMENT')).toBe(true);
  });

  it('allowedTargetStages excludes the current stage', () => {
    const codes = allowedTargetStages(owner, deal).map((s) => s.code);
    expect(codes).not.toContain('PRESENTATION');
    expect(codes).toContain('LEAD_APPROACH');
    expect(codes).toContain('DELIVERED');
  });

  it('lost/reopen is owner, sales_manager or ceo only', () => {
    expect(canMarkLost(owner, deal)).toBe(true);
    expect(canMarkLost(salesManager, deal)).toBe(true);
    expect(canMarkLost(ceo, deal)).toBe(true);
    expect(canMarkLost(otherSales, deal)).toBe(false);
    expect(canMarkLost(importUser, deal)).toBe(false);
    expect(canMarkLost(accountUser, deal)).toBe(false);
  });

  it('treats S4 → S3 as routine, every other backward move as an exception', () => {
    // The designer is normally quoted before signing off the spec, so
    // QUOTE_DESIGN_SIDE → SPEC_APPROVED must not demand a written reason.
    expect(isRoutineBackwardMove('QUOTE_DESIGN_SIDE', 'SPEC_APPROVED')).toBe(true);
    // One adjacent pair only — not a general relaxation, and not symmetric.
    expect(isRoutineBackwardMove('SPEC_APPROVED', 'QUOTE_DESIGN_SIDE')).toBe(false);
    expect(isRoutineBackwardMove('QUOTE_DESIGN_SIDE', 'PRESENTATION')).toBe(false);
    expect(isRoutineBackwardMove('OWNER_SIGNOFF', 'SPEC_APPROVED')).toBe(false);
  });

  it("another rep's deal offers a plain sales user nothing", () => {
    const othersDeal = { salesStage: 'LEAD_APPROACH', createdById: 99 };
    expect(canSetStage(owner, othersDeal, 'PRESENTATION')).toBe(false);
    expect(canMarkLost(owner, othersDeal)).toBe(false);
    expect(canSetStage(salesManager, othersDeal, 'PRESENTATION')).toBe(true);
  });
});
