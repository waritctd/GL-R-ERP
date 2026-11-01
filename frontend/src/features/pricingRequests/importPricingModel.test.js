import { describe, expect, it } from 'vitest';
import { formatQty, metaOf, nextStep, originOf, qtyDetailOf, specFor } from './importPricingModel.js';

const card = (over = {}) => ({ name: 'SCG', isContacted: true, isConfirmed: false, isDeclined: false, ...over });
const base = { status: 'IMPORT_REVIEWING', cards: [card()], missingCount: 0, firstUnassignedName: '', canSetItemFactory: true, hasGateNotice: false };
const step = (over) => nextStep({ ...base, ...over });

describe('nextStep precedence', () => {
  it('cancelled and superseded win over everything, with no sub-line', () => {
    const loud = { missingCount: 2, cards: [card({ isDeclined: true })] };
    expect(step({ ...loud, status: 'CANCELLED' })).toEqual({ next: 'คำขอนี้ถูกยกเลิกแล้ว', sub: '' });
    expect(step({ ...loud, status: 'SUPERSEDED' })).toEqual({ next: 'คำขอนี้ถูกแทนด้วยรอบใหม่', sub: '' });
  });

  it('an unassigned line beats a declined factory: names the line when the picker exists, states the fact when not', () => {
    const cards = [card({ isDeclined: true })];
    expect(step({ cards, missingCount: 1, firstUnassignedName: 'SCG A1' }).next).toBe('ระบุโรงงานให้ "SCG A1" ก่อน');
    expect(step({ cards, missingCount: 3, canSetItemFactory: false })).toEqual({
      next: 'ยังไม่ได้ระบุโรงงาน 3 รายการ — ฝ่ายนำเข้าระบุไม่ได้ในขั้นนี้', sub: '',
    });
  });

  it('a declined factory beats not-contacted, and the sub-line follows the gate notice', () => {
    const cards = [card({ name: 'A', isContacted: false }), card({ name: 'B', isDeclined: true })];
    expect(step({ cards }).next).toBe('B แจ้งไม่พร้อมเสนอราคา');
    expect(step({ cards }).sub).toMatch(/ยังไม่ส่งถึง CEO/);
    expect(step({ cards, hasGateNotice: true }).sub).toBe('ดูรายละเอียดด้านล่าง');
  });

  it('no factory cards at all', () => {
    expect(step({ cards: [] })).toEqual({ next: 'ไม่มีรายการที่ต้องขอราคาจากโรงงาน', sub: '' });
  });

  it('first not-contacted factory, then the first pending one, with the remaining-count sub-line', () => {
    const one = step({ cards: [card({ name: 'A', isConfirmed: true }), card({ name: 'B', isContacted: false })] });
    expect(one.next).toBe('ติดต่อ B');
    expect(one.sub).toBe('ยืนยันโรงงานนี้แล้วคำขอนี้จะส่งถึง CEO อัตโนมัติ');
    const two = step({ cards: [card({ name: 'A' }), card({ name: 'B' })] });
    expect(two.next).toBe('กรอกราคาและยืนยัน A');
    expect(two.sub).toMatch(/ยังเหลือ 2 โรงงาน/);
  });

  it('all confirmed but the request is still pre-gate: not sent to the CEO yet', () => {
    const cards = [card({ isConfirmed: true })];
    expect(step({ cards })).toEqual({ next: 'ยืนยันราคาครบแล้ว แต่ยังไม่ส่งถึง CEO', sub: 'ระบบจะส่งถึง CEO เมื่อครบเงื่อนไข' });
    expect(step({ cards, hasGateNotice: true }).sub).toBe('ดูรายละเอียดด้านล่าง');
  });

  it('all confirmed and past the gate: sent to the CEO', () => {
    expect(step({ cards: [card({ isConfirmed: true })], status: 'READY_FOR_CEO_REVIEW' }).next).toBe('ส่งถึง CEO แล้ว');
  });
});

describe('item descriptions', () => {
  it('"อื่นๆ" origin shows the typed country in the card meta and the spec row', () => {
    const items = [{ originCountry: 'อื่นๆ', originCountryOther: 'เวียดนาม' }];
    expect(originOf(items)).toBe('อื่นๆ (เวียดนาม)');
    expect(metaOf(items, 2)).toBe('อื่นๆ (เวียดนาม) · 2 รายการ');
    expect(specFor(items[0]).find(([label]) => label === 'ประเทศต้นทาง')[1]).toBe('อื่นๆ (เวียดนาม)');
  });

  it('a named country is shown as is; none shows only the count / a dash', () => {
    expect(metaOf([{ originCountry: 'จีน' }], 1)).toBe('จีน · 1 รายการ');
    expect(metaOf([{}], 1)).toBe('1 รายการ');
    expect(specFor({}).find(([label]) => label === 'ประเทศต้นทาง')[1]).toBe('—');
  });

  it('quantities are formatted: float noise rounds to 2 decimals, boxes follow', () => {
    expect(formatQty(4.319999999999999)).toBe('4.32');
    expect(qtyDetailOf({ requestedQtySqm: 4.319999999999999, boxes: 3 })).toBe('4.32 ตร.ม. · 3 กล่อง');
    expect(qtyDetailOf({})).toBe('');
  });
});
