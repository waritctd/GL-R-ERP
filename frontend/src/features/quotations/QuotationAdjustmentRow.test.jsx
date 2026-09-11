import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { QuotationAdjustmentRow } from './QuotationAdjustmentRow.jsx';
import { emptyAdjustment } from './QuotationItemRow.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({ api: { catalog: { prices: vi.fn() } } }));

function renderRow(props = {}) {
  const onChange = vi.fn();
  render(<ul><QuotationAdjustmentRow adjustment={emptyAdjustment('2026-07-31')} index={4} onChange={onChange} onRemove={vi.fn()} {...props} /></ul>);
  return { onChange };
}

describe('QuotationAdjustmentRow (v3 S3)', () => {
  it('defaults to a percent with the deadline prefilled — the normal case is one typed number', () => {
    renderRow();
    expect(screen.getByLabelText(/^ส่วนลด %/)).not.toBeNull();
    expect(screen.getByLabelText(/^สั่งซื้อภายในวันที่/).value).toBe('2026-07-31');
    expect(screen.queryByLabelText(/^จำนวนเงินส่วนลด/)).toBeNull();
  });

  it('switches to a flat amount on request', () => {
    const { onChange } = renderRow();
    fireEvent.click(screen.getByRole('button', { name: 'เป็นจำนวนเงิน' }));
    expect(onChange).toHaveBeenCalledWith({ adjustmentKind: 'AMOUNT' });
  });

  it('a flat adjustment asks for the amount in the document currency and allows its own wording', () => {
    renderRow({ adjustment: { ...emptyAdjustment(), adjustmentKind: 'AMOUNT' }, currency: 'USD' });
    expect(screen.getByLabelText(/^จำนวนเงินส่วนลด \(USD\)/)).not.toBeNull();
    expect(screen.getByLabelText(/^ข้อความ/)).not.toBeNull();
  });

  it('shows the DERIVED description and a NEGATIVE amount, flagged as an estimate while unsaved', () => {
    renderRow({ amount: 38198.21, description: 'ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569', estimated: true });
    expect(screen.getByTestId('adjustment-description-4').textContent).toBe('ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569');
    expect(screen.getByTestId('adjustment-amount-4').textContent).toBe('-฿38,198.21');
    expect(screen.getByText('โดยประมาณ')).not.toBeNull();
  });

  it('drops the estimate flag once the figure is the server\'s own', () => {
    renderRow({ amount: 38198.21, description: 'x', estimated: false });
    expect(screen.queryByText('โดยประมาณ')).toBeNull();
  });

  it('has no ราคา/หน่วย field at all — the server derives it and ignores one if sent', () => {
    renderRow();
    expect(screen.queryByLabelText(/ราคา\/หน่วย/)).toBeNull();
  });
});
