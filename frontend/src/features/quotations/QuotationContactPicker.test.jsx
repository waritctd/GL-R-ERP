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
        createContact: vi.fn(),
      },
    },
  };
});

function Harness({ customerId = 1, customerName = null, initial = null }) {
  const [value, setValue] = React.useState(initial);
  return <QuotationContactPicker customerId={customerId} customerName={customerName} value={value} onChange={setValue} />;
}

// ── "ใช้ชื่อเดียวกับลูกค้า" (owner testing feedback, 2026-09-11): "when the customer and the
// purchaser are the same person, let the rep reuse the customer name instead of retyping it". ──
describe('QuotationContactPicker — ใช้ชื่อเดียวกับลูกค้า', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: [] });
  });

  async function openNewContactForm() {
    fireEvent.change(await screen.findByLabelText(/^ผู้สั่งซื้อ/), { target: { value: '__new__' } });
  }

  it('does not render the checkbox when no customer name is known yet', async () => {
    render(<Harness customerName={null} />);
    await openNewContactForm();
    expect(screen.queryByText(/ใช้ชื่อเดียวกับลูกค้า/)).toBeNull();
  });

  it('checking the box copies the customer name into ชื่อ, blanks นามสกุล, and locks ชื่อ', async () => {
    render(<Harness customerName="วิภา สมิทธ์" />);
    await openNewContactForm();

    const lastNameInput = screen.getByLabelText('นามสกุลผู้สั่งซื้อ');
    fireEvent.change(lastNameInput, { target: { value: 'เดิม' } });

    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));

    const firstNameInput = screen.getByLabelText('ชื่อผู้สั่งซื้อ');
    expect(firstNameInput.value).toBe('วิภา สมิทธ์');
    expect(firstNameInput.disabled).toBe(true);
    expect(lastNameInput.value).toBe('');
  });

  it('unchecking hands ชื่อ back for manual editing without clearing it', async () => {
    render(<Harness customerName="วิภา สมิทธ์" />);
    await openNewContactForm();
    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));

    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));

    const firstNameInput = screen.getByLabelText('ชื่อผู้สั่งซื้อ');
    expect(firstNameInput.disabled).toBe(false);
    expect(firstNameInput.value).toBe('วิภา สมิทธ์'); // kept, not cleared -- a starting point to edit from

    fireEvent.change(firstNameInput, { target: { value: 'วิภา (ผู้จัดการ)' } });
    expect(firstNameInput.value).toBe('วิภา (ผู้จัดการ)');
  });

  it('stays live-bound to the customer name while checked, e.g. across a customer switch', async () => {
    function SwitchHarness() {
      const [name, setName] = React.useState('บริษัท เอ จำกัด');
      const [value, setValue] = React.useState(null);
      return (
        <>
          <button type="button" onClick={() => setName('บริษัท บี จำกัด')}>switch</button>
          <QuotationContactPicker customerId={1} customerName={name} value={value} onChange={setValue} />
        </>
      );
    }
    render(<SwitchHarness />);
    await openNewContactForm();
    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));
    expect(screen.getByLabelText('ชื่อผู้สั่งซื้อ').value).toBe('บริษัท เอ จำกัด');

    fireEvent.click(screen.getByRole('button', { name: 'switch' }));

    expect(screen.getByLabelText('ชื่อผู้สั่งซื้อ').value).toBe('บริษัท บี จำกัด');
  });

  it('submits the copied name as the new contact', async () => {
    api.customers.createContact.mockResolvedValue({ contact: { id: 9, customerId: 1, firstName: 'วิภา สมิทธ์', lastName: null } });
    render(<Harness customerName="วิภา สมิทธ์" />);
    await openNewContactForm();
    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));

    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มผู้สั่งซื้อ' }));

    await waitFor(() => expect(api.customers.createContact).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ firstName: 'วิภา สมิทธ์', lastName: null }),
    ));
  });
});
