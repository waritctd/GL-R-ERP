import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BOARD_COLUMNS } from './boardFormat.js';

// The board's column hints (BOARD_COLUMNS[].moveHint / moveRule) pinned against the Java that actually moves a deal:
// TicketService's autoAdvanceStage call sites, DealStage's rep-code → column table and DealRoute's off-route sets. If
// the backend gains, loses or re-targets an automatic move, this file fails and the hints must be re-read.
const JAVA = (path) => readFileSync(resolve(process.cwd(), '../backend/src/main/java/th/co/glr/hr', path), 'utf8');
const TICKET_SERVICE = JAVA('ticket/TicketService.java');
const DEAL_STAGE = JAVA('ticket/DealStage.java');
const DEAL_ROUTE = JAVA('ticket/DealRoute.java');

const hint = (column) => BOARD_COLUMNS.find((c) => c.column === column);

/** The S-code a stage maps to in DealStage.REP_CODE, and the column that S-code sits in (DealStage.BOARD_COLUMN). */
function columnOf(stage) {
  // REP_CODE only (BUSINESS_CODE above it maps the same stages to the S1–S20 sheet numbers).
  const repCode = DEAL_STAGE.slice(DEAL_STAGE.indexOf('REP_CODE = Map.ofEntries('), DEAL_STAGE.indexOf('BOARD_COLUMN = Map.of('));
  const code = new RegExp(`Map\\.entry\\(${stage}, "(S\\d)"\\)`).exec(repCode)?.[1];
  const column = new RegExp(`"${code}", (\\d)`).exec(DEAL_STAGE.slice(DEAL_STAGE.indexOf('BOARD_COLUMN = Map.of(')))?.[1];
  return Number(column);
}

describe('board move hints vs TicketService', () => {
  it('every automatic move target is one the hints account for, and no other exists', () => {
    const targets = new Set([...TICKET_SERVICE.matchAll(/autoAdvanceStage\(\w+, DealStage\.(\w+), actor\)/g)].map((m) => m[1]));
    // ORDER_RECEIVED (col 4), DEPOSIT_RECEIVED / PROCUREMENT / DELIVERY_SCHEDULING (col 5 entries), DELIVERED and
    // CLOSED_PAID (moves inside col 5 / off the board). The quotation path passes a variable (stage / target).
    expect([...targets].sort()).toEqual(
      ['CLOSED_PAID', 'DELIVERED', 'DELIVERY_SCHEDULING', 'DEPOSIT_RECEIVED', 'ORDER_RECEIVED', 'PROCUREMENT']);
  });

  it('column 3: only a designer or owner quotation moves a deal in', () => {
    expect(TICKET_SERVICE).toMatch(/QuotationRecipient\.DESIGNER\.equals\(recipientType\)\) \{\s*return DealStage\.QUOTE_DESIGN_SIDE;/);
    expect(TICKET_SERVICE).toMatch(/QuotationRecipient\.OWNER\.equals\(recipientType\)\) \{\s*return DealStage\.QUOTE_OWNER;/);
    expect(columnOf('QUOTE_DESIGN_SIDE')).toBe(3);
    expect(columnOf('QUOTE_OWNER')).toBe(3);
    expect(hint(3).moveHint).toBe('เข้าเองเมื่ออนุมัติใบถึงผู้ออกแบบ/เจ้าของ');
  });

  it('column 4: a buyer quotation, a quotation with no recipient, or the order confirm', () => {
    expect(TICKET_SERVICE).toMatch(/QuotationRecipient\.BUYER\.equals\(recipientType\)\) \{\s*return DealStage\.QUOTE_BUYER;/);
    // A no-recipient quotation goes to the nearest on-route stage at or before QUOTE_BUYER — QUOTE_BUYER itself, because
    // no route ever drops it (DealRoute's off-route sets).
    expect(TICKET_SERVICE).toMatch(/stageForUnspecifiedRecipient[\s\S]*?nearestOnRouteAtOrBefore\(stageForQuotationRecipient\(QuotationRecipient\.BUYER\)/);
    const offRoute = DEAL_ROUTE.slice(DEAL_ROUTE.indexOf('OFF_ROUTE = Map.of('), DEAL_ROUTE.indexOf('static {'));
    expect(offRoute).not.toContain('QUOTE_BUYER');
    expect(TICKET_SERVICE).toMatch(/autoAdvanceStage\(s, DealStage\.ORDER_RECEIVED, actor\)/);
    expect(columnOf('QUOTE_BUYER')).toBe(4);
    expect(columnOf('ORDER_RECEIVED')).toBe(4);
    expect(hint(4).moveHint).toBe('เข้าเองเมื่ออนุมัติใบถึงผู้ซื้อหรือยืนยันสั่งซื้อ');
    expect(hint(4).moveRule).toContain('ไม่ระบุผู้รับ');
  });

  it('column 5: the deposit, the first import request, or a deal covered from stock', () => {
    expect(TICKET_SERVICE).toMatch(/autoAdvanceStage\(s, DealStage\.DEPOSIT_RECEIVED, actor\)/);
    expect(TICKET_SERVICE).toMatch(/autoAdvanceStage\(s, DealStage\.PROCUREMENT, actor\)/);
    expect(TICKET_SERVICE).toMatch(/private boolean routeAllStockDealToDelivery[\s\S]*?autoAdvanceStage\(s, DealStage\.DELIVERY_SCHEDULING, actor\)/);
    ['DEPOSIT_RECEIVED', 'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED'].forEach((stage) => expect(columnOf(stage), stage).toBe(5));
    expect(hint(5).moveHint).toBe('เข้าเองเมื่อรับมัดจำหรือเริ่มสั่ง/ส่งของ');
    ['มัดจำ', 'ใบขอซื้อ', 'สต็อก'].forEach((word) => expect(hint(5).moveRule).toContain(word));
  });
});
