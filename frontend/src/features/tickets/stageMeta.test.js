import { describe, expect, it } from 'vitest';
import {
  AUTO_STAGE_HINT, GATE_LABEL,
} from './stageMeta.js';

/** stageMeta.js is Thai display copy and nothing else. It used to also hold the stage list, the write gates and
 * the note rule — a hand-maintained copy of DealStage.java and TicketService, with no guard of any kind, which
 * had gone stale in three separate places at once. */
describe('stageMeta holds labels, and only labels', () => {
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
    // QA-09: the IR is now issued by the owning sales rep (ขั้น 11 button), not by import.
    expect(AUTO_STAGE_HINT.PROCUREMENT).toBe('อัปเดตอัตโนมัติเมื่อฝ่ายขายออกเลขใบขอซื้อ (IR) ของงานนี้ · ถ้าสินค้ามาจากสต็อกทั้งหมด จะข้ามไปขั้นนัดส่งสินค้า');
    for (const hint of Object.values(AUTO_STAGE_HINT)) {
      expect(hint).toContain('อัตโนมัติ');
    }
    // The quote hints must name the DOCUMENT as the way in, not a department: that is the whole
    // answer to "I cannot click เลื่อนไป any more, so what do I do?".
    for (const code of ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'QUOTE_BUYER']) {
      expect(AUTO_STAGE_HINT[code], code).toContain('ใบเสนอราคา');
    }
  });
});
