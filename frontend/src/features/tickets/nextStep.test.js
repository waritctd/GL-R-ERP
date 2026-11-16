import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { nextStepFor } from './nextStep.js';
import { TICKET_DETAIL_TABS } from './ticketDetailTabs.js';

// The "ขั้นต่อไป" card's stage table (drawing a-deal). The card's BUTTON is the page's existing, server-gated action;
// this table only names the step, says how the deal moves on, and which tab holds that stage's work.
const TAB_IDS = TICKET_DETAIL_TABS.map((t) => t.id);

const ACTIVE_STAGES = {
  LEAD_APPROACH: { title: 'นำเสนอสินค้าจนผู้ออกแบบตกลงสเปค', hint: 'ตกลงสเปคแล้ว เซลส์ย้ายงานไป S2 เอง', tab: 'overview' },
  PRESENTATION: { title: 'นำเสนอสินค้าจนผู้ออกแบบตกลงสเปค', hint: 'ตกลงสเปคแล้ว เซลส์ย้ายงานไป S2 เอง', tab: 'overview' },
  SPEC_APPROVED: { title: 'ทำใบเสนอราคา', hint: 'ทำใบเสนอราคาแล้วงานย้ายไป S3 เอง', tab: 'quote' },
  QUOTE_DESIGN_SIDE: { title: 'ติดตามผลใบเสนอราคา', hint: 'บันทึกผลแล้วงานย้ายไป S5 / S6 เอง', tab: 'quote' },
  QUOTE_OWNER: { title: 'ติดตามผลใบเสนอราคา', hint: 'บันทึกผลแล้วงานย้ายไป S5 / S6 เอง', tab: 'quote' },
  OWNER_SIGNOFF: { title: 'ติดตามผลใบเสนอราคา', hint: 'บันทึกผลแล้วงานย้ายไป S5 / S6 เอง', tab: 'quote' },
  AWAITING_BUYER: { title: 'ติดตามผลประมูล', hint: 'ทราบผู้ซื้อแล้วงานย้ายไป S6 เอง', tab: 'quote' },
  QUOTE_BUYER: { title: 'ยืนยันคำสั่งซื้อกับผู้ซื้อ', hint: 'ยืนยันคำสั่งซื้อแล้วออกใบแจ้งมัดจำได้', tab: 'quote' },
  NEGOTIATION: { title: 'ยืนยันคำสั่งซื้อกับผู้ซื้อ', hint: 'ยืนยันคำสั่งซื้อแล้วออกใบแจ้งมัดจำได้', tab: 'quote' },
  ORDER_RECEIVED: { title: 'ออกใบแจ้งมัดจำและรอรับมัดจำ', hint: 'ฝ่ายบัญชียืนยันรับมัดจำแล้วงานย้ายไป S7 เอง', tab: 'money' },
  DEPOSIT_RECEIVED: { title: 'จัดซื้อสินค้า', hint: 'ออกใบขอซื้อ (IR) แล้วฝ่ายนำเข้าติดตามของต่อ', tab: 'ship' },
  PROCUREMENT: { title: 'ติดตามการนำเข้า', hint: 'ของถึงโกดังแล้วนัดส่งสินค้าได้ (S8)', tab: 'ship' },
  DELIVERY_SCHEDULING: { title: 'ส่งสินค้าให้ลูกค้า', hint: 'ส่งครบแล้วงานย้ายไป S9 เอง', tab: 'ship' },
  DELIVERED: { title: 'เก็บเงินส่วนที่เหลือ', hint: 'ฝ่ายบัญชียืนยันรับเงินครบแล้วปิดงาน', tab: 'money' },
};

describe('nextStepFor — an ACTIVE deal at each stage', () => {
  it('names every catalog stage (14 working stages + CLOSED_PAID)', () => {
    expect([...Object.keys(ACTIVE_STAGES), 'CLOSED_PAID'].sort())
      .toEqual(DEAL_STAGE_CATALOG.stages.map((s) => s.code).sort());
  });

  it.each(Object.entries(ACTIVE_STAGES))('%s', (salesStage, expected) => {
    expect(nextStepFor({ salesStage, lifecycle: 'ACTIVE' })).toEqual({ kind: 'step', ...expected });
    // An absent lifecycle is the server's default, ACTIVE.
    expect(nextStepFor({ salesStage })).toEqual({ kind: 'step', ...expected });
  });

  it('CLOSED_PAID has no next step: the deal is done', () => {
    expect(nextStepFor({ salesStage: 'CLOSED_PAID', lifecycle: 'ACTIVE' }))
      .toEqual({ kind: 'done', title: 'ปิดการขายแล้ว — เก็บเงินครบ', hint: null, tab: 'money' });
  });

  it('an unknown stage names nothing rather than guessing', () => {
    expect(nextStepFor({ salesStage: 'NOT_A_STAGE', lifecycle: 'ACTIVE' }))
      .toEqual({ kind: 'none', title: null, hint: null, tab: null });
    expect(nextStepFor({})).toEqual({ kind: 'none', title: null, hint: null, tab: null });
  });

  it('every tab it points at is one of the five page tabs', () => {
    Object.keys(ACTIVE_STAGES).forEach((salesStage) => {
      expect(TAB_IDS).toContain(nextStepFor({ salesStage }).tab);
    });
  });
});

describe('nextStepFor — a deal that is not ACTIVE (the lifecycle wins over the stage)', () => {
  it.each([
    ['ON_HOLD', { kind: 'resume', title: 'งานนี้พักไว้ชั่วคราว', hint: 'กด “ดำเนินการต่อ” เมื่อพร้อมทำงานนี้ต่อ ขั้นเดิมยังอยู่', tab: null }],
    ['DORMANT', { kind: 'resume', title: 'งานนี้พักยาว (dormant)', hint: 'กด “ดำเนินการต่อ” เมื่อพร้อมทำงานนี้ต่อ ขั้นเดิมยังอยู่', tab: null }],
    ['CLOSED_LOST', { kind: 'reopen', title: 'เสียงานแล้ว', hint: 'เปิดงานอีกครั้งได้ ขั้นเดิมยังอยู่', tab: null }],
    ['CANCELLED', { kind: 'closed', title: 'ยกเลิกงานแล้ว', hint: null, tab: null }],
    ['COMPLETED', { kind: 'closed', title: 'งานเสร็จสมบูรณ์', hint: null, tab: null }],
  ])('%s', (lifecycle, expected) => {
    // Whatever stage the deal was left at.
    for (const salesStage of ['LEAD_APPROACH', 'QUOTE_OWNER', 'PROCUREMENT', 'CLOSED_PAID']) {
      expect(nextStepFor({ salesStage, lifecycle })).toEqual(expected);
    }
  });
});

describe('nextStepFor wording', () => {
  it('says งาน, never ดีล (owner ruling: labels on this page say งาน)', () => {
    const all = [
      ...DEAL_STAGE_CATALOG.stages.map((s) => nextStepFor({ salesStage: s.code })),
      ...['ON_HOLD', 'DORMANT', 'CLOSED_LOST', 'CANCELLED', 'COMPLETED'].map((lifecycle) => nextStepFor({ salesStage: 'SPEC_APPROVED', lifecycle })),
    ];
    all.forEach((step) => {
      expect(`${step.title ?? ''} ${step.hint ?? ''}`).not.toContain('ดีล');
    });
  });
});
