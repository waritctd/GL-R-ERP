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

// ข้อมูลงาน (drawing a-deal) merges the old ดีล tab's วันสำคัญ / ผู้เกี่ยวข้อง blocks with the การติดตามดีล panel.
describe('DealInfoCard', () => {
  it('wrong-way-round: a viewer outside deal tracking sees no โอกาส and no แก้ไข', () => {
    render(<DealInfoCard summary={summary} trackingVisible={false} canEdit={false} onUpdateTracking={vi.fn()} />);
    expect(screen.queryByText('โอกาส', { selector: 'dt' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'แก้ไข' })).toBeNull();
  });

  // A call or visit may set the follow-up date while this form is open; a save that did not touch the date must not
  // write back the one the form loaded. Left alone it is not sent (the server keeps it); emptied it is cleared.
  describe('วันติดตามครั้งถัดไป', () => {
    const openForm = () => {
      const card = screen.getByRole('heading', { name: 'ข้อมูลงาน' }).closest('section');
      fireEvent.click(within(card).getByRole('button', { name: 'แก้ไข' }));
    };

    it('changed, the new date is sent', async () => {
      const onUpdateTracking = vi.fn().mockResolvedValue({});
      render(<DealInfoCard summary={summary} trackingVisible canEdit onUpdateTracking={onUpdateTracking} />);
      openForm();
      const date = screen.getByLabelText('วันติดตามครั้งถัดไป');
      fireEvent.change(date, { target: { value: '20/11/2569' } });
      fireEvent.blur(date);
      fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));
      await waitFor(() => expect(onUpdateTracking).toHaveBeenCalledWith(expect.objectContaining({ nextFollowUpAt: '2026-11-20' })));
      expect(onUpdateTracking.mock.calls[0][0]).not.toHaveProperty('clearNextFollowUpAt');
    });
  });
});
