import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealTrackingPanel } from './DealTrackingPanel.jsx';

globalThis.React = React;

const summary = {
  effectiveWinProbability: 60, winProbabilityOverride: null, nextFollowUpAt: null,
  designerName: 'สมชาย ผู้ออกแบบ', ownerName: 'บริษัท เจ้าของ จำกัด', buyerName: 'ผู้รับเหมา ข.',
  createdAt: '2026-07-01T09:00:00Z', salesStage: 'QUOTE_BUYER',
};

// deal-party-model.md: buyers are deal parties now, so the panel keeps ดีไซเนอร์ / เจ้าของโครงการ
// free text only, and the full-replace PUT sends the stored buyerName back untouched.
describe('DealTrackingPanel — buyers moved to the parties panel', () => {
  it('shows no ผู้ซื้อ row, only designer and owner', () => {
    render(<DealTrackingPanel summary={summary} events={[]} canEdit={false} onUpdateTracking={vi.fn()} />);
    expect(screen.getByText('ดีไซเนอร์')).toBeTruthy();
    expect(screen.getByText('เจ้าของโครงการ')).toBeTruthy();
    expect(screen.queryByText('ผู้ซื้อ')).toBeNull();
    expect(screen.queryByText('ผู้รับเหมา ข.')).toBeNull();
  });

  it('the edit form has no buyer input and preserves the stored buyerName on save', async () => {
    const onUpdateTracking = vi.fn().mockResolvedValue({});
    render(<DealTrackingPanel summary={summary} events={[]} canEdit onUpdateTracking={onUpdateTracking} />);
    fireEvent.click(screen.getByRole('button', { name: /แก้ไขข้อมูลติดตาม/ }));
    expect(screen.queryByLabelText('ชื่อผู้ซื้อ')).toBeNull();
    fireEvent.change(screen.getByLabelText('ชื่อดีไซเนอร์'), { target: { value: 'ใหม่' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(onUpdateTracking).toHaveBeenCalledWith(expect.objectContaining({
      designerName: 'ใหม่', ownerName: 'บริษัท เจ้าของ จำกัด', buyerName: 'ผู้รับเหมา ข.',
    })));
  });
});
