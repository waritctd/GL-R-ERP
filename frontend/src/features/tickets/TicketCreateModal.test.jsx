import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketCreateModal } from './TicketCreateModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// UX-03 (still true after the section-hub rebuild, handoff 107): every
// invalid field gets its own aria-invalid + aria-describedby + inline
// role="alert" message, the first invalid field is focused on submit
// (jumping to whichever hub section owns it), and the submit payload shape
// is unchanged (plus the new entryChannel/priority fields).

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
      tickets: {
        list: vi.fn(),
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
    source: 'custom', catalogPrice: null, catalogCurrency: null,
    ...overrides,
  };
}

function submitForm() {
  fireEvent.submit(document.getElementById('ticket-create-form'));
}

// Anchored to the start of the accessible name: a hub row's name is its
// title + subtitle concatenated (e.g. "โครงการจำเป็นเลือกลูกค้าก่อน"), so an
// unanchored search for "ลูกค้า" would also match the โครงการ row while it's
// waiting on a customer, and "สร้างดีล" would also match the ตรวจสอบ & บันทึก
// row's "ตรวจก่อนสร้างดีล" subtitle.
function goToSection(name) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${name}`) }));
}

// Drives the real customer/project pickers (not stubbed — this is the exact
// flow the finding is about) via the ลูกค้า then โครงการ hub sections, so
// tests that need a valid customer+project exercise the real SearchSelect +
// pill-button UI. Customer must be picked before project — projects are
// nested under a customer via api.customers.projects(customerId).
async function selectCustomerAndProject() {
  goToSection('ลูกค้า');
  const searchInput = screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท...');
  fireEvent.change(searchInput, { target: { value: 'บริษัท' } });
  const option = await screen.findByText(mockCustomer.name);
  fireEvent.mouseDown(option);

  goToSection('กลับ');
  goToSection('โครงการ');
  const projectButton = await screen.findByRole('button', { name: mockProject.name });
  fireEvent.click(projectButton);
  goToSection('กลับ');
}

beforeEach(() => {
  vi.clearAllMocks();
  api.customers.search.mockResolvedValue({ customers: [mockCustomer] });
  api.customers.projects.mockResolvedValue({ projects: [mockProject] });
  api.customers.contacts.mockResolvedValue({ contacts: [] });
  api.tickets.list.mockResolvedValue({ tickets: [] });
  // Best-effort: none of these tests exercise the "บันทึกร่าง" save-draft
  // action, so nothing here actually depends on a clean localStorage — this
  // is just hygiene. Guarded because some Node versions ship a broken
  // built-in global `localStorage` (needs --localstorage-file) that this
  // repo's engines range doesn't target; the component's own loadDraft/
  // saveDraft/clearDraft already tolerate that (try/catch, see
  // TicketCreateModal.jsx), so a real browser or CI's pinned Node 22 is
  // unaffected either way.
  try { localStorage.clear(); } catch { /* see comment above */ }
});

describe('TicketCreateModal validation', () => {
  it('marks the customer field invalid when none is selected, and does not submit', async () => {
    const onSubmit = vi.fn();
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

    submitForm();

    const customerInput = await screen.findByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท...');
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

    const colorInput = await screen.findByPlaceholderText('เช่น ขาว, เทา, ครีม');
    await waitFor(() => expect(colorInput.getAttribute('aria-invalid')).toBe('true'));
    expect(colorInput.id).toBe('item-0-color');
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

    const qtyInput = await screen.findByPlaceholderText('จำนวนแผ่น');
    await waitFor(() => expect(qtyInput.getAttribute('aria-invalid')).toBe('true'));
    expect(qtyInput.id).toBe('item-0-qty');
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

    const sqmInput = await screen.findByPlaceholderText('เช่น 120.500');
    await waitFor(() => expect(sqmInput.getAttribute('aria-invalid')).toBe('true'));
    expect(sqmInput.id).toBe('item-0-qtySqm');
    expect(sqmInput.getAttribute('aria-describedby')).toBe('item-0-qtySqm-error');
    expect(screen.getByText('กรุณากรอกพื้นที่ (ตร.ม.) ในรายการที่ 1')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits the exact existing payload shape (plus entryChannel/priority) once every field is valid', async () => {
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
      entryChannel: 'DESIGNER_LED',
      priority: 'NORMAL',
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

    const colorInput = await screen.findByPlaceholderText('เช่น ขาว, เทา, ครีม');
    await waitFor(() => expect(colorInput.getAttribute('aria-invalid')).toBe('true'));

    fireEvent.change(colorInput, { target: { value: 'เทา' } });

    expect(colorInput.getAttribute('aria-invalid')).toBeNull();
    expect(colorInput.getAttribute('aria-describedby')).toBeNull();
    expect(screen.queryByText('กรุณากรอกสี')).toBeNull();
  });

  it('carries the chosen entry channel into the create payload', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

    await selectCustomerAndProject();

    goToSection('ผู้ติดต่อ & ช่องทางดีล');
    fireEvent.click(screen.getByRole('radio', { name: /เจ้าของตรง/ }));
    goToSection('กลับ');

    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ entryChannel: 'OWNER_DIRECT', items: [] });
  });

  it('blocks creation until both customer and project are set, but never requires items', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

    // Hub's "สร้างดีล" stays disabled with nothing selected yet.
    expect(screen.getByRole('button', { name: /^สร้างดีล/ }).disabled).toBe(true);

    await selectCustomerAndProject();

    // Zero items — creation must still be allowed (items are optional, V50).
    expect(screen.getByRole('button', { name: /^สร้างดีล/ }).disabled).toBe(false);

    submitForm();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items).toEqual([]);
  });
});
