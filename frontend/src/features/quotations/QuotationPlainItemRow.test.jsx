import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { QuotationPlainItemRow } from './QuotationPlainItemRow.jsx';
import { emptyPlainItem } from './QuotationItemRow.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({ api: { catalog: { prices: vi.fn() } } }));

function renderRow(props = {}) {
  const onChange = vi.fn();
  render(<ul><QuotationPlainItemRow item={emptyPlainItem('g1')} index={0} onChange={onChange} onRemove={vi.fn()} {...props} /></ul>);
  return { onChange };
}

describe('QuotationPlainItemRow (v3 S2)', () => {
  it('offers ONLY the four PLAIN fields — none of the tile machinery', () => {
    renderRow();
    expect(screen.getByLabelText(/^รายละเอียด/)).not.toBeNull();
    expect(screen.getByLabelText(/^จำนวน/)).not.toBeNull();
    expect(screen.getByLabelText(/^หน่วย/)).not.toBeNull();
    expect(screen.getByLabelText(/^ราคา\/หน่วย/)).not.toBeNull();
    expect(screen.queryByLabelText(/ตร\.ม\.\/แผ่น/)).toBeNull();
    expect(screen.queryByText(/เผื่อ/)).toBeNull();
  });

  it('หน่วย is a controlled list, the document language\'s units first', () => {
    renderRow({ documentLanguage: 'EN' });
    const options = [...screen.getByLabelText(/^หน่วย/).querySelectorAll('option')].map((o) => o.value);
    expect(options.slice(0, 5)).toEqual(['', 'JOB', 'SQM', 'Bags', 'Barrels']);
    expect(options).toContain('ชุด');
  });

  it('keeps a stored unit that is not in the list rather than silently blanking it', () => {
    renderRow({ item: { ...emptyPlainItem('g1'), description: 'x', unit: 'Pallet' } });
    expect(screen.getByLabelText(/^หน่วย/).value).toBe('Pallet');
  });

  it('a one-click preset fills description + unit + quantity on a blank row', () => {
    const { onChange } = renderRow();
    fireEvent.click(screen.getByRole('button', { name: 'ค่าขนส่ง' }));
    expect(onChange).toHaveBeenCalledWith({ description: 'ค่าขนส่ง', unit: 'งาน', quantity: 1 });
  });

  it('offers no preset once the row has content', () => {
    renderRow({ item: { ...emptyPlainItem('g1'), description: 'ค่าแรง' } });
    expect(screen.queryByRole('button', { name: 'ค่าขนส่ง' })).toBeNull();
  });

  it('reports typed values as numbers, and shows the server\'s amount in the document currency', () => {
    const { onChange } = renderRow({ item: { ...emptyPlainItem('g1'), lineAmount: 3500 }, currency: 'USD' });
    fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '85' } });
    expect(onChange).toHaveBeenCalledWith({ quantity: 85 });
    expect(screen.getByText('$3,500.00')).not.toBeNull();
  });

  it('shows the inline errors it is given', () => {
    renderRow({ errors: { description: 'กรุณาระบุรายละเอียด', unit: 'กรุณาเลือกหน่วย' } });
    expect(screen.getByText('กรุณาระบุรายละเอียด')).not.toBeNull();
    expect(screen.getByText('กรุณาเลือกหน่วย')).not.toBeNull();
  });
});
