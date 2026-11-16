import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CancelDealModal } from './CancelDealModal.jsx';
import { MarkLostModal } from './MarkLostModal.jsx';
import { MoveToS2Modal } from './MoveToS2Modal.jsx';
import { addMonthsIso, PauseDealModal } from './PauseDealModal.jsx';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';

globalThis.React = React;

// The four lifecycle dialogs rebuilt to the owner-approved drawings (2026-11-16): m-lost, m-cancel, m-hold, m-stage.
// Labels say งาน where the drawing says ดีล (owner ruling 2026-11-16). Pure components: who may open them is the
// server's (availableActions) and is pinned in DealStagePanel / TicketDetailPage tests, not here.

const dialog = () => screen.getByRole('dialog');

describe('MarkLostModal — ปิดงาน · เสียงาน (m-lost)', () => {
  it('shows the drawn title, subtitle and the eight reasons as chips WITH their F1–F8 codes, in order', () => {
    render(<MarkLostModal catalog={DEAL_STAGE_CATALOG} onClose={vi.fn()} onSubmit={vi.fn()} />);

    expect(within(dialog()).getByRole('heading', { name: 'ปิดงาน · เสียงาน' })).toBeTruthy();
    expect(within(dialog()).getByText('เลือกเหตุผลหนึ่งข้อ ใช้ทำรายงาน F1–F8 เหมือนเดิม เปิดงานใหม่ได้ภายหลัง')).toBeTruthy();
    const chips = within(screen.getByRole('radiogroup', { name: 'เหตุผลที่เสียงาน' })).getAllByRole('radio');
    expect(chips.map((chip) => chip.textContent)).toEqual([
      'F1ไม่มีสินค้าที่ลูกค้าต้องการ', 'F2ราคา', 'F3ระยะเวลาส่งมอบ (leadtime)', 'F4เงื่อนไขการชำระเงิน (payment term)',
      'F5ความสัมพันธ์ / connection', 'F6โครงการหยุดก่อสร้างชั่วคราว', 'F7โครงการหยุดก่อสร้างถาวร',
      'F8ลูกค้าสั่งซื้อกระเบื้องไปหมดแล้ว',
    ]);
    expect(screen.getByPlaceholderText('เช่น คู่แข่งเสนอต่ำกว่า 12%')).toBeTruthy();
  });

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
  it('offers exactly the drawn four reasons, with the drawn subtitle, warning and buttons', () => {
    render(<CancelDealModal catalog={DEAL_STAGE_CATALOG} onClose={vi.fn()} onSubmit={vi.fn()} />);

    expect(within(dialog()).getByRole('heading', { name: 'ยกเลิกงาน' })).toBeTruthy();
    expect(within(dialog()).getByText('ใช้เมื่อเปิดงานผิดหรือซ้ำ ไม่นับเป็นเสียงาน')).toBeTruthy();
    const chips = within(screen.getByRole('radiogroup', { name: 'เหตุผลที่ยกเลิก' })).getAllByRole('radio');
    expect(chips.map((chip) => chip.textContent)).toEqual([
      'เปิดซ้ำกับงานอื่น', 'เปิดผิดลูกค้า / โครงการ', 'ไม่มีโครงการจริง', 'อื่น ๆ',
    ]);
    expect(screen.getByRole('note').textContent)
      .toBe('ยกเลิกแล้วกลับมาไม่ได้ ถ้ายังมีโอกาสขาย ใช้ “พักงาน” หรือ “เสียงาน” แทน');
    expect(screen.getByRole('button', { name: 'กลับ' })).toBeTruthy();
    expect(screen.getByPlaceholderText('เช่น ซ้ำกับ PR-2026-0028')).toBeTruthy();
  });

  it('sends the chosen new code', () => {
    const onSubmit = vi.fn();
    render(<CancelDealModal catalog={DEAL_STAGE_CATALOG} onClose={vi.fn()} onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole('radio', { name: 'เปิดซ้ำกับงานอื่น' }));
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิกงาน' }));

    expect(onSubmit).toHaveBeenCalledWith({ reason: 'DUPLICATE_DEAL', note: undefined });
  });

  it('กลับ closes without cancelling', () => {
    const onClose = vi.fn();
    const onSubmit = vi.fn();
    render(<CancelDealModal catalog={DEAL_STAGE_CATALOG} onClose={onClose} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: 'กลับ' }));
    expect(onClose).toHaveBeenCalled();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});

describe('PauseDealModal — พักงาน (m-hold)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-16T03:00:00Z')); // 16 Nov 2026, 10:00 Bangkok
  });

  afterEach(() => { vi.useRealTimers(); });

  it('is one dialog with the drawn choice, date chips, hint and note', () => {
    render(<PauseDealModal canHold canDormant initialKind="ON_HOLD" onClose={vi.fn()} onSubmit={vi.fn()} />);

    expect(within(dialog()).getByRole('heading', { name: 'พักงาน' })).toBeTruthy();
    expect(within(dialog()).getByText('งานที่พักไว้ไม่นับเป็นงานเงียบ')).toBeTruthy();
    expect(screen.getByRole('radio', { name: /พักชั่วคราว/ }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('radio', { name: /ไม่เคลื่อนไหว/ }).getAttribute('aria-checked')).toBe('false');
    for (const chip of ['2 สัปดาห์', '1 เดือน', '2 เดือน']) expect(screen.getByRole('button', { name: chip })).toBeTruthy();
    expect(screen.getByText('ถึงวันนั้นงานจะกลับมาที่ “ควรตามต่อ” เอง')).toBeTruthy();
    expect(screen.getByPlaceholderText('เช่น เจ้าของโครงการรอผลขออนุญาตก่อสร้าง')).toBeTruthy();
  });

  it('a chip sets the return date from today (Bangkok); พักชั่วคราว posts ON_HOLD with it', () => {
    const onSubmit = vi.fn();
    render(<PauseDealModal canHold canDormant initialKind="ON_HOLD" onClose={vi.fn()} onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole('button', { name: '2 เดือน' }));
    fireEvent.click(screen.getByRole('button', { name: 'พักงาน' }));

    expect(onSubmit).toHaveBeenCalledWith({ kind: 'ON_HOLD', pauseReturnOn: '2027-01-16', note: undefined });
  });

  it('ไม่เคลื่อนไหว posts DORMANT; 2 สัปดาห์ is fourteen days', () => {
    const onSubmit = vi.fn();
    render(<PauseDealModal canHold canDormant initialKind="ON_HOLD" onClose={vi.fn()} onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole('radio', { name: /ไม่เคลื่อนไหว/ }));
    fireEvent.click(screen.getByRole('button', { name: '2 สัปดาห์' }));
    fireEvent.click(screen.getByRole('button', { name: 'พักงาน' }));

    expect(onSubmit).toHaveBeenCalledWith({ kind: 'DORMANT', pauseReturnOn: '2026-11-30', note: undefined });
  });

  it('with no date the pause behaves as before (pauseReturnOn null)', () => {
    const onSubmit = vi.fn();
    render(<PauseDealModal canHold canDormant initialKind="ON_HOLD" onClose={vi.fn()} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: 'พักงาน' }));
    expect(onSubmit).toHaveBeenCalledWith({ kind: 'ON_HOLD', pauseReturnOn: null, note: undefined });
  });

  it('a choice the server did not offer is shown but cannot be picked (an ON_HOLD deal may only go dormant)', () => {
    render(<PauseDealModal canHold={false} canDormant initialKind="DORMANT" onClose={vi.fn()} onSubmit={vi.fn()} />);
    expect(screen.getByRole('radio', { name: /พักชั่วคราว/ }).disabled).toBe(true);
    expect(screen.getByRole('radio', { name: /ไม่เคลื่อนไหว/ }).getAttribute('aria-checked')).toBe('true');
  });

  it('addMonthsIso clamps to the end of a shorter month', () => {
    expect(addMonthsIso('2026-12-31', 2)).toBe('2027-02-28');
    expect(addMonthsIso('2026-11-16', 1)).toBe('2026-12-16');
  });
});

describe('MoveToS2Modal — ย้ายไป S2 · สเปคผ่าน (m-stage)', () => {
  const deal = { id: 5, projectName: 'โครงการบ้านสวนลุมพินี', title: 'งาน A', salesStage: 'PRESENTATION' };

  it('shows the drawn move, optional note and the "from S3 it moves by documents" note', () => {
    render(<MoveToS2Modal deal={deal} onClose={vi.fn()} onSubmit={vi.fn()} />);

    expect(within(dialog()).getByRole('heading', { name: 'ย้ายไป S2 · สเปคผ่าน' })).toBeTruthy();
    expect(within(dialog()).getByText('โครงการบ้านสวนลุมพินี')).toBeTruthy();
    expect(screen.getByText('S1 อยู่ระหว่างเสนอสินค้า')).toBeTruthy();
    expect(screen.getByText('S2 ผู้ออกแบบตกลงสเปค')).toBeTruthy();
    expect(screen.getByText(/โน้ตสั้น ๆ/).textContent).toContain('(ไม่บังคับ)');
    expect(screen.getByTestId('move-s2-info').textContent)
      .toBe('ตั้งแต่ S3 งานย้ายเอง ไม่ต้องกด: ใบเสนอราคาได้รับอนุมัติ → S3 · ได้รับมัดจำ → S7 · ส่งของครบ → S9');
    expect(screen.queryByLabelText(/วันติดตามครั้งถัดไป/)).toBeNull();
  });

  it('confirms with or without a note', () => {
    const onSubmit = vi.fn();
    render(<MoveToS2Modal deal={deal} onClose={vi.fn()} onSubmit={onSubmit} />);

    fireEvent.change(screen.getByPlaceholderText('เช่น ผู้ออกแบบเลือก Raku 20×20 สีขาว'), { target: { value: 'Raku ขาว' } });
    fireEvent.click(screen.getByRole('button', { name: 'ย้ายไป S2' }));

    expect(onSubmit).toHaveBeenCalledWith({ note: 'Raku ขาว', nextFollowUpAt: null });
  });

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
