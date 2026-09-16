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
  it('offers the four required PLAIN fields — none of the tile machinery', () => {
    renderRow();
    expect(screen.getByLabelText(/^รายละเอียด/)).not.toBeNull();
    expect(screen.getByLabelText(/^จำนวน/)).not.toBeNull();
    expect(screen.getByLabelText(/^หน่วย/)).not.toBeNull();
    expect(screen.getByLabelText(/^ราคา\/หน่วย/)).not.toBeNull();
    expect(screen.queryByLabelText(/ตร\.ม\.\/แผ่น/)).toBeNull();
    expect(screen.queryByText(/เผื่อ/)).toBeNull();
  });

  // D1 (owner decision, 2026-09-16): a สินค้า/บริการอื่น row (sanitaryware) may carry an OPTIONAL
  // import lead time — her reference document QN6900971-4 prints "ระยะเวลานำเข้า 75-90 วัน" against
  // exactly this kind of row. Mirrors QuotationItemRow's own tile lead-time control.
  it('offers an OPTIONAL ระยะเวลานำเข้า (วัน) min–max input, blank by default', () => {
    renderRow();
    expect(screen.getByLabelText(/^ระยะเวลานำเข้า/).value).toBe('');
    expect(screen.getByLabelText('ถึง (วัน)').value).toBe('');
    expect(screen.getByText('ไม่บังคับ')).not.toBeNull();
  });

  it('reports the typed lead-time range as numbers', () => {
    const { onChange } = renderRow();
    fireEvent.change(screen.getByLabelText(/^ระยะเวลานำเข้า/), { target: { value: '75' } });
    expect(onChange).toHaveBeenCalledWith({ leadTimeMinDays: 75 });
    fireEvent.change(screen.getByLabelText('ถึง (วัน)'), { target: { value: '90' } });
    expect(onChange).toHaveBeenCalledWith({ leadTimeMaxDays: 90 });
  });

  it('clearing a typed lead-time field reports null, not an empty string', () => {
    const { onChange } = renderRow({ item: { ...emptyPlainItem('g1'), leadTimeMinDays: 75, leadTimeMaxDays: 90 } });
    fireEvent.change(screen.getByLabelText(/^ระยะเวลานำเข้า/), { target: { value: '' } });
    expect(onChange).toHaveBeenCalledWith({ leadTimeMinDays: null });
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

describe('ส่วนลด % on a plain row — blank by default, clearable (owner, 2026-09-13)', () => {
  it('a new plain row starts with an empty discount and says what blank means', () => {
    renderRow();
    expect(screen.getByLabelText(/^ส่วนลด %/).value).toBe('');
    expect(screen.getByText('เว้นว่าง = ไม่มีส่วนลด (พิมพ์ Net)')).not.toBeNull();
  });

  it('clearing a typed discount leaves it blank instead of snapping back to 0', () => {
    const { onChange } = renderRow({ item: { ...emptyPlainItem('g1'), discountPct: 5 } });
    fireEvent.change(screen.getByLabelText(/^ส่วนลด %/), { target: { value: '' } });
    expect(onChange).toHaveBeenCalledWith({ discountPct: null });
  });
});
