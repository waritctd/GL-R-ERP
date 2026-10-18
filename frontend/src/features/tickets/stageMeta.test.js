import { describe, expect, it } from 'vitest';
import * as stageMeta from './stageMeta.js';
import {
  AUTO_STAGE_HINT, CANCEL_REASON_LABEL, GATE_LABEL, labelledReasons, LOST_REASON_LABEL,
} from './stageMeta.js';

/**
 * stageMeta.js is Thai display copy and nothing else.
 *
 * It used to also hold the stage list, the write gates and the note rule — a hand-maintained copy
 * of DealStage.java and TicketService, with no guard of any kind, which had gone stale in three
 * separate places at once. Those tests are gone with the code they covered, and their subject
 * matter moved to where it can actually be proven:
 *
 *   • who may set which stage → StageDecisionIntegrationTest (real Postgres, real TicketService,
 *     written wrong-way-round). It was never testable here: this file only ever agreed with itself.
 *   • when a move needs a written reason → DealStageTest + StageDecisionIntegrationTest.
 *   • the stage list, its order, phases and gates → DealStageTest, plus stageCatalog.test.js,
 *     which checks this side against DealStage.java directly.
 *
 * What is left to test here is the copy, and — below — that no rule has crept back in.
 */
describe('stageMeta holds labels, and only labels', () => {
  // The regression guard for the whole refactor. If a `canSetStage`, an `allowedTargetStages` or
  // an `isRoutineBackwardMove` reappears here, the frontend has started deciding again and this
  // fails before anyone has to notice the drift in production.
  it('exports no decision function — the backend decides, this module renders', () => {
    const decisionShaped = Object.keys(stageMeta).filter((name) => (
      /^(can|is|allowed|requires|may)[A-Z]/.test(name) || /Stages$/.test(name)
    ));
    expect(decisionShaped).toEqual([]);
  });

  it('labels every write gate the backend can report', () => {
    // The gate VALUES come from DealStage.gateOf; stageCatalog.test.js pins that this covers them.
    expect(Object.keys(GATE_LABEL).sort()).toEqual(['account', 'import', 'sales']);
    expect(GATE_LABEL.sales).toBe('ฝ่ายขาย');
  });

  it('explains where each auto-advanced stage comes from', () => {
    // Seven since the owner's 2026-10-16 quote-stage ruling: the four OPERATIONAL stages, plus the
    // three DOCUMENT-driven quote stages a quotation's recipient now advances the deal onto. The
    // *set* is DealStage.AUTO_ADVANCED, pinned against the Java by stageCatalog.test.js; this only
    // pins that every one of them has words, since a stage marked auto with no hint renders the
    // panel's explanation blank exactly where the one-click button used to be.
    expect(Object.keys(AUTO_STAGE_HINT).sort()).toEqual(
      ['CLOSED_PAID', 'DEPOSIT_RECEIVED', 'ORDER_RECEIVED', 'PROCUREMENT',
        'QUOTE_BUYER', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER'],
    );
    for (const hint of Object.values(AUTO_STAGE_HINT)) {
      expect(hint).toContain('อัตโนมัติ');
    }
    // The quote hints must name the DOCUMENT as the way in, not a department: that is the whole
    // answer to "I cannot click เลื่อนไป any more, so what do I do?".
    for (const code of ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'QUOTE_BUYER']) {
      expect(AUTO_STAGE_HINT[code], code).toContain('ใบเสนอราคา');
    }
  });

  it('labels a backend-supplied reason code set, and passes an unknown code through', () => {
    // The order is the backend's — labelledReasons must not sort, filter or re-order it, or the
    // list a rep reads would stop matching the business's own F1–F8 sequence.
    expect(labelledReasons(['PRICE', 'PRODUCT_FIT'], LOST_REASON_LABEL)).toEqual([
      { code: 'PRICE', label: 'ราคา' },
      { code: 'PRODUCT_FIT', label: 'ไม่มีสินค้าที่ลูกค้าต้องการ' },
    ]);
    expect(labelledReasons(['OTHER'], CANCEL_REASON_LABEL)[0].label).toBe('อื่น ๆ (ระบุในหมายเหตุ)');
    // A code the backend adds before this side has wording for it renders as its code rather than
    // as `undefined` — visible, not blank.
    expect(labelledReasons(['F9_BRAND_NEW'], LOST_REASON_LABEL)).toEqual([
      { code: 'F9_BRAND_NEW', label: 'F9_BRAND_NEW' },
    ]);
    expect(labelledReasons(undefined, LOST_REASON_LABEL)).toEqual([]);
  });
});
