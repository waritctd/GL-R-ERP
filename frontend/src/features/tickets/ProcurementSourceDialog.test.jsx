import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { ProcurementSourceDialog } from './ProcurementSourceDialog.jsx';

const decide = vi.fn();
// A rejection is raised outside the vi.fn: vitest 4 reports a mock's own rejected result as an
// unhandled error even when the caller awaits and catches it.
let rejectWith = null;
vi.mock('../../api/index.js', () => ({
  api: { tickets: { decideProcurementSource: (...a) => (rejectWith ? Promise.reject(rejectWith) : decide(...a)) } },
}));

const items = [
  { id: 1, brand: 'แบรนด์ A', model: '60x120', size: '', qty: 120, unit: 'แผ่น' },
  { id: 2, brand: 'แบรนด์ A', model: '30x60', size: '', qty: 48, unit: 'แผ่น' },
];
const src = (over = {}) => ({
  legacy: false, editable: false, decidedAt: null, quotationNumber: 'QT-TEST-1',
  lines: [{ itemId: 1, fromStock: false }, { itemId: 2, fromStock: true }], ...over,
});
function renderDialog(procurementSource, props = {}) {
  const onClose = vi.fn();
  const onDecided = vi.fn();
  render(<ProcurementSourceDialog open ticketId={7} items={items} procurementSource={procurementSource}
    onClose={onClose} onDecided={onDecided} {...props} />);
  return { onClose, onDecided };
}
const oldDeal = (lines) => src({ legacy: true, editable: true, quotationNumber: null, lines });

beforeEach(() => { decide.mockReset(); rejectWith = null; });

describe('ProcurementSourceDialog', () => {
  it('posts every line once, then onDecided with anyImport', async () => {
    const ticket = { id: 7 };
    decide.mockResolvedValue({ ticket });
    const { onDecided } = renderDialog(oldDeal([{ itemId: 1, fromStock: false }, { itemId: 2, fromStock: true }]));
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยัน' }));
    await waitFor(() => expect(onDecided).toHaveBeenCalledWith(ticket, { anyImport: true }));
    expect(decide).toHaveBeenCalledTimes(1);
    expect(decide).toHaveBeenCalledWith(7, { lines: [{ itemId: 1, fromStock: false }, { itemId: 2, fromStock: true }] });
  });

  it("shows the server's 409 message and stays open", async () => {
    rejectWith = Object.assign(new Error('เลือกที่มาสินค้าของดีลนี้แล้ว แก้ไขไม่ได้'), { status: 409 });
    const { onDecided, onClose } = renderDialog(oldDeal([{ itemId: 1, fromStock: false }, { itemId: 2, fromStock: true }]));
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยัน' }));
    expect(await screen.findByText('เลือกที่มาสินค้าของดีลนี้แล้ว แก้ไขไม่ได้')).toBeTruthy();
    expect(onDecided).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('disables confirm when the deal has no lines', () => {
    renderDialog(src({ lines: [] }), { items: [] });
    expect(screen.getByText('ยังไม่มีรายการสินค้า')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'ยืนยัน' }).disabled).toBe(true);
  });
});
