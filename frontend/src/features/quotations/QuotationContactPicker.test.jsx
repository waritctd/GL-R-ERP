import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationContactPicker } from './QuotationContactPicker.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      customers: {
        contacts: vi.fn(),
        updateContact: vi.fn(),
      },
    },
  };
});

function Harness({
  customerId = 1, customerName = null, initial = null, showToast,
  omitContactHonorific, onChangeOmitContactHonorific,
}) {
  const [value, setValue] = React.useState(initial);
  return (
    <QuotationContactPicker
      customerId={customerId} customerName={customerName} value={value} onChange={setValue} showToast={showToast}
      omitContactHonorific={omitContactHonorific} onChangeOmitContactHonorific={onChangeOmitContactHonorific}
    />
  );
}

// ── edit-in-place: โทร./อีเมล of the SELECTED contact (gap fix, prod QT-2026-0041-1) ──────────
describe('QuotationContactPicker — edit-in-place โทร./อีเมล', () => {
  const SELECTED_CONTACT = { id: 42, customerId: 1, firstName: 'วิภา', lastName: 'สมิทธ์', phone: '081-000-0000', email: 'wipa@example.com' };

  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: [SELECTED_CONTACT] });
  });

  it('editing the e-mail and blurring calls updateContact with ONLY the changed field', async () => {
    api.customers.updateContact.mockResolvedValue({ contact: { ...SELECTED_CONTACT, email: 'new@example.com' } });
    render(<Harness initial={SELECTED_CONTACT} />);

    const emailInput = await screen.findByLabelText('แก้ไขอีเมลผู้ติดต่อ');
    fireEvent.change(emailInput, { target: { value: 'new@example.com' } });
    fireEvent.blur(emailInput);

    await waitFor(() => expect(api.customers.updateContact).toHaveBeenCalledWith(1, 42, { email: 'new@example.com' }));
    expect(api.customers.updateContact).toHaveBeenCalledTimes(1);
  });

  it('rolls back and shows a toast when the save is refused', async () => {
    api.customers.updateContact.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    const showToast = vi.fn();
    render(<Harness initial={SELECTED_CONTACT} showToast={showToast} />);

    const emailInput = await screen.findByLabelText('แก้ไขอีเมลผู้ติดต่อ');
    fireEvent.change(emailInput, { target: { value: 'broken@example.com' } });
    fireEvent.blur(emailInput);

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    expect(emailInput.value).toBe('wipa@example.com'); // rolled back
  });
});

