import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealActivityTimeline } from './DealActivityTimeline.jsx';

globalThis.React = React;

describe('DealActivityTimeline', () => {
  it('renders compact chronological rows with collapsed snapshots', () => {
    const { container } = render(
      <DealActivityTimeline
        events={[
          {
            id: 1,
            kind: 'CREATED',
            actorName: 'ฝ่ายขาย',
            createdAt: '2026-07-01T08:00:00.000Z',
          },
          {
            id: 2,
            kind: 'PRICE_PROPOSED',
            actorName: 'ฝ่ายนำเข้า',
            createdAt: '2026-07-02T08:00:00.000Z',
            itemSnapshot: JSON.stringify([{ brand: 'SCG', model: 'A1', qty: 10, rawPrice: 100, rawCurrency: 'THB', rawUnit: 'piece' }]),
          },
        ]}
      />,
    );

    expect(screen.getByTestId('deal-activity-timeline')).not.toBeNull();
    expect(screen.getByText('เสนอราคาสินค้า')).not.toBeNull();
    expect(screen.getByText('สร้างดีล')).not.toBeNull();
    expect(screen.getByText('ดูรายการสินค้า ณ เวลาที่เสนอราคา').closest('details').open).toBe(false);
    expect(container.querySelectorAll('.rounded-lg, .shadow-sm')).toHaveLength(0);
  });

  it('keeps the comment composer as a compact row action', () => {
    const onSubmit = vi.fn();
    const onChange = vi.fn();
    render(
      <DealActivityTimeline
        events={[]}
        canComment
        commentText="ติดตามแล้ว"
        onCommentTextChange={onChange}
        onSubmitComment={onSubmit}
      />,
    );

    fireEvent.change(screen.getByLabelText('เพิ่มความคิดเห็น'), { target: { value: 'รอลูกค้าตอบ' } });
    expect(onChange).toHaveBeenCalledWith('รอลูกค้าตอบ');

    fireEvent.click(screen.getByRole('button', { name: /ส่งความคิดเห็น/ }));
    expect(onSubmit).toHaveBeenCalled();
    expect(screen.getByText('ยังไม่มีประวัติ')).not.toBeNull();
  });
});
