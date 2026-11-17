import React from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealInfoCard } from './DealInfoCard.jsx';

globalThis.React = React;

const summary = {
  effectiveWinProbability: 60, winProbabilityOverride: null, nextFollowUpAt: '2026-11-14',
  customerName: 'บริษัท ลุมพินี จำกัด', createdByName: 'คุณสมหมาย',
  designerName: 'สมชาย ผู้ออกแบบ', ownerName: 'บริษัท เจ้าของ จำกัด', buyerName: 'ผู้รับเหมา ข.',
  createdAt: '2026-07-01T09:00:00Z', updatedAt: '2026-07-02T09:00:00Z', salesStage: 'QUOTE_BUYER',
};

const row = (label) => screen.getByText(label, { selector: 'dt' }).nextElementSibling;

// ข้อมูลงาน (drawing a-deal) merges the old ดีล tab's วันสำคัญ / ผู้เกี่ยวข้อง blocks with the การติดตามดีล panel.
describe('DealInfoCard', () => {
  // Adapted (slice 6.3): the drawn คาดปิด row sits after เจ้าของงาน (approved prototype after-deal-tl), so the drawn
  // order has seven fields now; the six it pinned keep their order around it.
  it('shows the drawn fields first: ลูกค้า, ผู้ออกแบบ, เจ้าของโครงการ, เจ้าของงาน, คาดปิด, โอกาส, ติดตามครั้งถัดไป', () => {
    render(<DealInfoCard summary={summary} trackingVisible canEdit={false} onUpdateTracking={vi.fn()} />);
    const labels = screen.getAllByRole('term').map((dt) => dt.textContent);
    expect(labels.slice(0, 7))
      .toEqual(['ลูกค้า', 'ผู้ออกแบบ', 'เจ้าของโครงการ', 'เจ้าของงาน', 'คาดปิด', 'โอกาส', 'ติดตามครั้งถัดไป']);
    expect(row('ลูกค้า').textContent).toBe('บริษัท ลุมพินี จำกัด');
    expect(row('เจ้าของงาน').textContent).toBe('คุณสมหมาย');
    expect(row('โอกาส').textContent).toContain('60%');
  });

  it('keeps every readout the old blocks had (ติดตามล่าสุด, ใบเสนอราคาหมดอายุ, ผู้รับเรื่องคำขอราคา, บัญชี, อัปเดตล่าสุด)', () => {
    render(<DealInfoCard summary={summary} trackingVisible latestFollowUp="2026-11-01" quotationExpiry="2026-12-01" assignedImport="คุณวรรณา" canEdit={false} onUpdateTracking={vi.fn()} />);
    for (const label of ['ติดตามล่าสุด', 'ใบเสนอราคาหมดอายุ', 'ผู้รับเรื่องคำขอราคา', 'บัญชี', 'อัปเดตล่าสุด']) {
      expect(row(label)).toBeTruthy();
    }
    expect(row('ผู้รับเรื่องคำขอราคา').textContent).toBe('คุณวรรณา');
  });

  it('คาดปิด reads the served expectedQuarter in the board\'s own style, and a dash when the rep set none', () => {
    const { unmount } = render(<DealInfoCard summary={{ ...summary, expectedQuarter: '2027-Q1' }} trackingVisible
      canEdit={false} onUpdateTracking={vi.fn()} />);
    expect(row('คาดปิด').textContent).toBe('Q1/70');
    unmount();

    render(<DealInfoCard summary={{ ...summary, expectedQuarter: null }} trackingVisible canEdit={false}
      onUpdateTracking={vi.fn()} />);
    expect(row('คาดปิด').textContent).toBe('—');
  });

  it('shows no ผู้ซื้อ row (buyers are deal parties)', () => {
    render(<DealInfoCard summary={summary} trackingVisible canEdit={false} onUpdateTracking={vi.fn()} />);
    expect(screen.queryByText('ผู้ซื้อ')).toBeNull();
    expect(screen.queryByText('ผู้รับเหมา ข.')).toBeNull();
  });

  it('wrong-way-round: a viewer outside deal tracking sees no โอกาส and no แก้ไข', () => {
    render(<DealInfoCard summary={summary} trackingVisible={false} canEdit={false} onUpdateTracking={vi.fn()} />);
    expect(screen.queryByText('โอกาส', { selector: 'dt' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'แก้ไข' })).toBeNull();
  });

  it('แก้ไข opens the tracking form; it has no buyer input and preserves the stored buyerName on save', async () => {
    const onUpdateTracking = vi.fn().mockResolvedValue({});
    render(<DealInfoCard summary={summary} trackingVisible canEdit onUpdateTracking={onUpdateTracking} />);
    const card = screen.getByRole('heading', { name: 'ข้อมูลงาน' }).closest('section');
    fireEvent.click(within(card).getByRole('button', { name: 'แก้ไข' }));
    expect(screen.queryByLabelText('ชื่อผู้ซื้อ')).toBeNull();
    fireEvent.change(screen.getByLabelText('ชื่อดีไซเนอร์'), { target: { value: 'ใหม่' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(onUpdateTracking).toHaveBeenCalledWith(expect.objectContaining({
      designerName: 'ใหม่', ownerName: 'บริษัท เจ้าของ จำกัด', buyerName: 'ผู้รับเหมา ข.', nextFollowUpAt: '2026-11-14',
    })));
  });

  // Fix round 1, ruling 3: the create form's มูลค่าโดยประมาณ (V220 estimated_value) is shown here and edited through
  // the same tracking update — set, clear, or (left alone) kept.
  describe('มูลค่าโดยประมาณ', () => {
    const withEstimate = { ...summary, estimatedValue: 1200000 };
    const openForm = () => {
      const card = screen.getByRole('heading', { name: 'ข้อมูลงาน' }).closest('section');
      fireEvent.click(within(card).getByRole('button', { name: 'แก้ไข' }));
    };

    it('is shown as a row', () => {
      render(<DealInfoCard summary={withEstimate} trackingVisible canEdit={false} onUpdateTracking={vi.fn()} />);
      expect(row('มูลค่าโดยประมาณ').textContent).toContain('1,200,000');
    });

    it('is prefilled, and a changed value is sent', async () => {
      const onUpdateTracking = vi.fn().mockResolvedValue({});
      render(<DealInfoCard summary={withEstimate} trackingVisible canEdit onUpdateTracking={onUpdateTracking} />);
      openForm();
      const input = screen.getByLabelText(/มูลค่าโดยประมาณ/);
      expect(input.value).toBe('1200000');
      fireEvent.change(input, { target: { value: '1,500,000' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));
      await waitFor(() => expect(onUpdateTracking).toHaveBeenCalledWith(expect.objectContaining({ estimatedValue: 1500000 })));
    });

    it('emptied, it is cleared', async () => {
      const onUpdateTracking = vi.fn().mockResolvedValue({});
      render(<DealInfoCard summary={withEstimate} trackingVisible canEdit onUpdateTracking={onUpdateTracking} />);
      openForm();
      fireEvent.change(screen.getByLabelText(/มูลค่าโดยประมาณ/), { target: { value: '' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));
      await waitFor(() => expect(onUpdateTracking).toHaveBeenCalledWith(expect.objectContaining({ clearEstimatedValue: true })));
    });

    it('an invalid value is refused in place and nothing is sent', () => {
      const onUpdateTracking = vi.fn();
      render(<DealInfoCard summary={withEstimate} trackingVisible canEdit onUpdateTracking={onUpdateTracking} />);
      openForm();
      fireEvent.change(screen.getByLabelText(/มูลค่าโดยประมาณ/), { target: { value: '0' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));
      expect(screen.getByText('มูลค่าต้องมากกว่า 0 และไม่เกิน 999,999,999,999.99')).toBeTruthy();
      expect(onUpdateTracking).not.toHaveBeenCalled();
    });
  });
});
