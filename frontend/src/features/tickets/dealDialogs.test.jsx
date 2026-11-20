import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CancelDealModal } from './CancelDealModal.jsx';
import { MarkLostModal } from './MarkLostModal.jsx';
import { MoveToS2Modal } from './MoveToS2Modal.jsx';
import { addMonthsIso, PauseDealModal } from './PauseDealModal.jsx';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';

globalThis.React = React;

describe('MarkLostModal — ปิดงาน · เสียงาน (m-lost)', () => {
  it('confirms only once a reason is chosen, sending the unchanged stored value and an optional note', () => {
    const onSubmit = vi.fn();
    render(<MarkLostModal catalog={DEAL_STAGE_CATALOG} onClose={vi.fn()} onSubmit={onSubmit} />);
    const confirm = screen.getByRole('button', { name: 'ยืนยันเสียงาน' });
    expect(confirm.disabled).toBe(true);

    fireEvent.click(screen.getByRole('radio', { name: /F2/ }));
    fireEvent.change(screen.getByPlaceholderText('เช่น คู่แข่งเสนอต่ำกว่า 12%'), { target: { value: ' ต่ำกว่า 12% ' } });
    fireEvent.click(confirm);

    expect(onSubmit).toHaveBeenCalledWith({ reason: 'PRICE', note: 'ต่ำกว่า 12%' });
  });
});

describe('CancelDealModal — ยกเลิกงาน (m-cancel)', () => {
  it('sends the chosen new code', () => {
    const onSubmit = vi.fn();
    render(<CancelDealModal catalog={DEAL_STAGE_CATALOG} onClose={vi.fn()} onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole('radio', { name: 'เปิดซ้ำกับงานอื่น' }));
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิกงาน' }));

    expect(onSubmit).toHaveBeenCalledWith({ reason: 'DUPLICATE_DEAL', note: undefined });
  });
});

describe('PauseDealModal — พักงาน (m-hold)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-16T03:00:00Z')); // 16 Nov 2026, 10:00 Bangkok
  });

  afterEach(() => { vi.useRealTimers(); });

  // Fix round 1, ruling 8 (F7): a typed past date is refused in the dialog itself, not only by the server.
  // Review round 2, L-5: a refused date must NOT turn into a pause with no date. While the field holds a refused
  // entry nothing is sent; the rep fixes the date, or empties the field to pause without one on purpose.
  it('a typed date before today (Bangkok) is refused by the field and blocks the pause — never posted as "no date"', () => {
    const onSubmit = vi.fn();
    render(<PauseDealModal canHold canDormant initialKind="ON_HOLD" onClose={vi.fn()} onSubmit={onSubmit} />);

    const date = screen.getByLabelText('วันที่จะกลับมาดู');
    fireEvent.change(date, { target: { value: '15/11/2569' } }); // 15 Nov 2026, the day before "today"
    fireEvent.blur(date);
    expect(screen.getByText('วันที่อยู่นอกช่วงที่เลือกได้')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'พักงาน' }).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'พักงาน' }));
    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.change(date, { target: { value: '16/11/2569' } }); // today is allowed
    fireEvent.blur(date);
    fireEvent.click(screen.getByRole('button', { name: 'พักงาน' }));
    expect(onSubmit).toHaveBeenLastCalledWith(expect.objectContaining({ pauseReturnOn: '2026-11-16' }));
  });

  it('addMonthsIso clamps to the end of a shorter month', () => {
    expect(addMonthsIso('2026-12-31', 2)).toBe('2027-02-28');
    expect(addMonthsIso('2026-11-16', 1)).toBe('2026-12-16');
  });
});

describe('MoveToS2Modal — ย้ายไป S2 · สเปคผ่าน (m-stage)', () => {
  const deal = { id: 5, projectName: 'โครงการบ้านสวนลุมพินี', title: 'งาน A', salesStage: 'PRESENTATION' };

  it('when the readiness gate is unmet it asks for exactly what is missing — a follow-up date and the note', () => {
    const onSubmit = vi.fn();
    render(<MoveToS2Modal deal={deal} needsFollowUp needsActivity onClose={vi.fn()} onSubmit={onSubmit} />);

    const confirm = screen.getByRole('button', { name: 'ย้ายไป S2' });
    expect(confirm.disabled).toBe(true);
    expect(screen.getByText(/โน้ตสั้น ๆ/).textContent).not.toContain('(ไม่บังคับ)');
    const date = screen.getByLabelText(/วันติดตามครั้งถัดไป/);
    fireEvent.change(date, { target: { value: '01/12/2569' } });
    fireEvent.blur(date);
    fireEvent.change(screen.getByPlaceholderText('เช่น ผู้ออกแบบเลือก Raku 20×20 สีขาว'), { target: { value: 'ผู้ออกแบบเลือกแล้ว' } });
    fireEvent.click(confirm);

    expect(onSubmit).toHaveBeenCalledWith({ note: 'ผู้ออกแบบเลือกแล้ว', nextFollowUpAt: '2026-12-01' });
  });
});
