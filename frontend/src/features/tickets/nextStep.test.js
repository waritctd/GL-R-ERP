import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { nextStepFor } from './nextStep.js';

const ACTIVE_STAGES = {
  LEAD_APPROACH: { title: 'นำเสนอสินค้าจนผู้ออกแบบตกลงสเปค', hint: 'ตกลงสเปคแล้ว เซลส์ย้ายงานไป S2 เอง', tab: 'overview' },
  PRESENTATION: { title: 'นำเสนอสินค้าจนผู้ออกแบบตกลงสเปค', hint: 'ตกลงสเปคแล้ว เซลส์ย้ายงานไป S2 เอง', tab: 'overview' },
  SPEC_APPROVED: { title: 'ทำใบเสนอราคา', hint: 'ทำใบเสนอราคาแล้วงานย้ายไป S3 ตามเอกสาร', tab: 'quote' },
  QUOTE_DESIGN_SIDE: { title: 'ติดตามผลใบเสนอราคา', hint: 'บันทึกผลแล้วเซลส์ย้ายงานไป S5 หรือเสนอราคาผู้ซื้อแล้วงานย้ายไป S6 ตามเอกสาร', tab: 'quote' },
  QUOTE_OWNER: { title: 'ติดตามผลใบเสนอราคา', hint: 'บันทึกผลแล้วเซลส์ย้ายงานไป S5 หรือเสนอราคาผู้ซื้อแล้วงานย้ายไป S6 ตามเอกสาร', tab: 'quote' },
  OWNER_SIGNOFF: { title: 'ติดตามผลใบเสนอราคา', hint: 'บันทึกผลแล้วเซลส์ย้ายงานไป S5 หรือเสนอราคาผู้ซื้อแล้วงานย้ายไป S6 ตามเอกสาร', tab: 'quote' },
  AWAITING_BUYER: { title: 'ติดตามผลประมูล', hint: 'ทราบผู้ซื้อแล้วเสนอราคาผู้ซื้อ งานย้ายไป S6 ตามเอกสาร', tab: 'quote' },
  QUOTE_BUYER: { title: 'ยืนยันคำสั่งซื้อกับผู้ซื้อ', hint: 'ยืนยันคำสั่งซื้อแล้วออกใบแจ้งมัดจำได้', tab: 'quote' },
  NEGOTIATION: { title: 'ยืนยันคำสั่งซื้อกับผู้ซื้อ', hint: 'ยืนยันคำสั่งซื้อแล้วออกใบแจ้งมัดจำได้', tab: 'quote' },
  ORDER_RECEIVED: { title: 'ออกใบแจ้งมัดจำและรอรับมัดจำ', hint: 'ฝ่ายบัญชียืนยันรับมัดจำแล้วงานย้ายไป S7 ตามเอกสาร', tab: 'money' },
  DEPOSIT_RECEIVED: { title: 'จัดซื้อสินค้า', hint: 'ออกใบขอซื้อ (IR) แล้วฝ่ายนำเข้าติดตามของต่อ', tab: 'ship' },
  PROCUREMENT: { title: 'ติดตามการนำเข้า', hint: 'ของถึงโกดังแล้วนัดส่งสินค้าได้ (S8)', tab: 'ship' },
  DELIVERY_SCHEDULING: { title: 'ส่งสินค้าให้ลูกค้า', hint: 'ส่งครบแล้วเซลส์ย้ายงานไป S9', tab: 'ship' },
  DELIVERED: { title: 'เก็บเงินส่วนที่เหลือ', hint: 'ฝ่ายบัญชียืนยันรับเงินครบแล้วปิดงาน', tab: 'money' },
};

describe('nextStepFor — an ACTIVE deal at each stage', () => {
  it('names every catalog stage (14 working stages + CLOSED_PAID)', () => {
    expect([...Object.keys(ACTIVE_STAGES), 'CLOSED_PAID'].sort())
      .toEqual(DEAL_STAGE_CATALOG.stages.map((s) => s.code).sort());
  });
});

// Fix round 1 (L2): a deal that skips the deposit (dealMoneyStatus.depositBypassed: NOT_REQUIRED / WAIVED /
// CREDIT_CUSTOMER) leaves ORDER_RECEIVED on its first payment (TicketService: the bypass target is
// AWAITING_FINAL_PAYMENT, then DEPOSIT_RECEIVED), not on a deposit notice.
describe('nextStepFor — ORDER_RECEIVED and the deposit policy', () => {
  it.each(['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'])('%s: no deposit notice, the first payment moves it', (depositPolicy) => {
    expect(nextStepFor({ salesStage: 'ORDER_RECEIVED', depositPolicy })).toEqual({
      kind: 'step', title: 'รอรับชำระงวดแรก', hint: 'ไม่เก็บมัดจำ — ฝ่ายบัญชีบันทึกรับชำระงวดแรกแล้วงานย้ายไป S7 ตามเอกสาร', tab: 'money',
    });
  });

  it.each(['REQUIRED', undefined, null])('wrong-way: %s keeps the deposit step', (depositPolicy) => {
    expect(nextStepFor({ salesStage: 'ORDER_RECEIVED', depositPolicy }).title).toBe('ออกใบแจ้งมัดจำและรอรับมัดจำ');
  });
});
