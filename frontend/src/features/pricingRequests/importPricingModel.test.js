import { describe, expect, it } from 'vitest';
import { nextStep } from './importPricingModel.js';

const card = (over = {}) => ({ name: 'SCG', isContacted: true, isConfirmed: false, isDeclined: false, ...over });
const base = { status: 'IMPORT_REVIEWING', cards: [card()], missingCount: 0, firstUnassignedName: '', canSetItemFactory: true, hasGateNotice: false };
const step = (over) => nextStep({ ...base, ...over });

describe('nextStep precedence', () => {
  it('an unassigned line beats a declined factory: names the line when the picker exists, states the fact when not', () => {
    const cards = [card({ isDeclined: true })];
    expect(step({ cards, missingCount: 1, firstUnassignedName: 'SCG A1' }).next).toBe('ระบุโรงงานให้ "SCG A1" ก่อน');
    expect(step({ cards, missingCount: 3, canSetItemFactory: false })).toEqual({
      next: 'ยังไม่ได้ระบุโรงงาน 3 รายการ — ฝ่ายนำเข้าระบุไม่ได้ในขั้นนี้', sub: '',
    });
  });

  it('first not-contacted factory, then the first pending one, with the remaining-count sub-line', () => {
    const one = step({ cards: [card({ name: 'A', isConfirmed: true }), card({ name: 'B', isContacted: false })] });
    expect(one.next).toBe('ติดต่อ B');
    expect(one.sub).toBe('ยืนยันโรงงานนี้แล้วคำขอนี้จะส่งถึง CEO อัตโนมัติ');
    const two = step({ cards: [card({ name: 'A' }), card({ name: 'B' })] });
    expect(two.next).toBe('กรอกราคาและยืนยัน A');
    expect(two.sub).toMatch(/ยังเหลือ 2 โรงงาน/);
  });
});
