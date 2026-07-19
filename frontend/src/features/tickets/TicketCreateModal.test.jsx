import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketCreateModal } from './TicketCreateModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// UX-03: TicketCreateModal used to show one generic bottom-of-form string
// (e.g. "กรุณากรอกข้อมูลสินค้าให้ครบทุกช่องในรายการที่ 3") for every kind of
// validation failure, with no field association for assistive tech. These
// tests assert the new per-field contract: each invalid field gets its own
// aria-invalid + aria-describedby + inline role="alert" message, the first
// invalid field is focused on submit, and the submit payload shape is
// unchanged.

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      customers: {
        search: vi.fn(),
        projects: vi.fn(),
        contacts: vi.fn(),
        createProject: vi.fn(),
        create: vi.fn(),
        createContact: vi.fn(),
      },
      catalog: {
        prices: vi.fn(),
      },
    },
  };
});

const mockCustomer = { id: 1, name: 'บริษัท ทดสอบ จำกัด', taxId: null };
const mockProject = { id: 10, name: 'โครงการ A' };

function validItem(overrides = {}) {
  return {
    brand: 'SCG', model: 'Stone', color: 'ขาว', texture: 'ด้าน', size: '60x60',
    factory: '', unitBasis: 'PIECE', qty: 5, qtySqm: '', sqmPerPiece: null,
    ...overrides,
  };
}

function submitForm() {
  fireEvent.submit(document.getElementById('ticket-create-form'));
}

// Drives the real customer/project pickers (not stubbed — this is the exact
// flow the finding is about) so tests that need a valid customer+project
// exercise the real SearchSelect + pill-button UI.
async function selectCustomerAndProject() {
  const searchInput = screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท...');
  fireEvent.change(searchInput, { target: { value: 'บริษัท' } });
  const option = await screen.findByText(mockCustomer.name);
  fireEvent.mouseDown(option);

  const projectButton = await screen.findByRole('button', { name: mockProject.name });
  fireEvent.click(projectButton);
}

beforeEach(() => {
  vi.clearAllMocks();
  api.customers.search.mockResolvedValue({ customers: [mockCustomer] });
  api.customers.projects.mockResolvedValue({ projects: [mockProject] });
  api.customers.contacts.mockResolvedValue({ contacts: [] });
});

describe('TicketCreateModal validation', () => {
  it('marks the customer field invalid when none is selected, and does not submit', async () => {
    const onSubmit = vi.fn();
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

    submitForm();

    const customerInput = document.getElementById('customer-select');
    await waitFor(() => expect(customerInput.getAttribute('aria-invalid')).toBe('true'));
    expect(customerInput.getAttribute('aria-describedby')).toBe('customer-select-error');
    expect(screen.getByText('กรุณาเลือกบริษัท/ลูกค้า')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('marks a specific blank field (color), not just a generic row message', async () => {
    const onSubmit = vi.fn();
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem({ color: '' })]}
      />,
    );

    await selectCustomerAndProject();
    submitForm();

    const colorInput = document.getElementById('item-0-color');
    await waitFor(() => expect(colorInput.getAttribute('aria-invalid')).toBe('true'));
    expect(colorInput.getAttribute('aria-describedby')).toBe('item-0-color-error');
    expect(screen.getByText('กรุณากรอกสี')).toBeTruthy();

    // The old generic string must not appear — the whole point of the fix.
    expect(screen.queryByText(/กรุณากรอกข้อมูลสินค้าให้ครบทุกช่องในรายการที่/)).toBeNull();
    // Other fields in the same row stay untouched.
    expect(document.getElementById('item-0-brand').getAttribute('aria-invalid')).toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('marks the qty field for a PIECE row with qty 0', async () => {
    const onSubmit = vi.fn();
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem({ unitBasis: 'PIECE', qty: 0 })]}
      />,
    );

    await selectCustomerAndProject();
    submitForm();

    const qtyInput = document.getElementById('item-0-qty');
    await waitFor(() => expect(qtyInput.getAttribute('aria-invalid')).toBe('true'));
    expect(qtyInput.getAttribute('aria-describedby')).toBe('item-0-qty-error');
    expect(screen.getByText('กรุณากรอกจำนวน (แผ่น) ในรายการที่ 1')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('marks the qtySqm field for an SQM row with an empty area', async () => {
    const onSubmit = vi.fn();
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem({ unitBasis: 'SQM', qtySqm: '' })]}
      />,
    );

    await selectCustomerAndProject();
    submitForm();

    const sqmInput = document.getElementById('item-0-qtySqm');
    await waitFor(() => expect(sqmInput.getAttribute('aria-invalid')).toBe('true'));
    expect(sqmInput.getAttribute('aria-describedby')).toBe('item-0-qtySqm-error');
    expect(screen.getByText('กรุณากรอกพื้นที่ (ตร.ม.) ในรายการที่ 1')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits the exact existing payload shape once every field is valid', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem()]}
      />,
    );

    await selectCustomerAndProject();
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      title: mockCustomer.name,
      customerName: mockCustomer.name,
      customerId: mockCustomer.id,
      projectId: mockProject.id,
      contactId: null,
      note: null,
      items: [{
        brand: 'SCG',
        model: 'Stone',
        color: 'ขาว',
        texture: 'ด้าน',
        size: '60x60',
        factory: null,
        unitBasis: 'PIECE',
        qty: 5,
        qtySqm: null,
      }],
    });
  });

  it('clears a field error once the user fixes it', async () => {
    const onSubmit = vi.fn();
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem({ color: '' })]}
      />,
    );

    await selectCustomerAndProject();
    submitForm();

    const colorInput = document.getElementById('item-0-color');
    await waitFor(() => expect(colorInput.getAttribute('aria-invalid')).toBe('true'));

    fireEvent.change(colorInput, { target: { value: 'เทา' } });

    expect(colorInput.getAttribute('aria-invalid')).toBeNull();
    expect(colorInput.getAttribute('aria-describedby')).toBeNull();
    expect(screen.queryByText('กรุณากรอกสี')).toBeNull();
  });
});
