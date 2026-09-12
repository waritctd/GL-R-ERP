import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealCustomerCard } from './DealCustomerCard.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      customers: {
        search: vi.fn(),
        create: vi.fn(),
        projects: vi.fn(),
        createProject: vi.fn(),
        contacts: vi.fn(),
        createContact: vi.fn(),
        update: vi.fn(),
      },
    },
  };
});

const testCustomer = { id: 1, name: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด', taxId: '0105565012345' };
const testProject = { id: 1, customerId: 1, name: 'โครงการ Central Ladprao ชั้น B1' };
const testContact = { id: 1, customerId: 1, firstName: 'วิภา', lastName: 'สมิทธ์' };

// The card calls useQueryClient() (F7 invalidates the cached customer searches after a successful
// customer edit), so every render here needs a provider -- as it has in the app, where this card
// only ever renders inside QuotationEditorPage.
function wrap(ui) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>;
}

function Harness({ initial, showToast = vi.fn() }) {
  const [value, setValue] = React.useState(initial ?? { customer: null, project: null, contact: null, entryChannel: 'UNSPECIFIED' });
  return <DealCustomerCard value={value} onChange={(patch) => setValue((prev) => ({ ...prev, ...patch }))} showToast={showToast} />;
}

describe('DealCustomerCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.projects.mockResolvedValue({ projects: [testProject] });
    api.customers.contacts.mockResolvedValue({ contacts: [testContact] });
  });

  it('searches as the rep types, and selecting a result replaces the input with a value chip', async () => {
    api.customers.search.mockResolvedValue({ customers: [testCustomer] });
    render(wrap(<Harness />));

    fireEvent.change(screen.getByLabelText(/^ลูกค้า/), { target: { value: 'ก้าวหน้า' } });
    await waitFor(() => expect(api.customers.search).toHaveBeenCalledWith('ก้าวหน้า'));

    // role="option", not "button": the typeahead popup is a real listbox since V4
    // (2026-09-10) — it used to announce as a plain bulleted list of buttons.
    const option = await screen.findByRole('option', { name: new RegExp(testCustomer.name) });
    fireEvent.mouseDown(option);

    expect(screen.getByText(testCustomer.name)).not.toBeNull();
    expect(screen.queryByLabelText(/^ลูกค้า/)).toBeNull(); // the search input is gone, replaced by the chip
    // Loads โครงการ/ผู้ติดต่อ for the newly-picked customer.
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(testCustomer.id));
    await waitFor(() => expect(api.customers.contacts).toHaveBeenCalledWith(testCustomer.id));
  });

  it('clearing the selected customer resets โครงการ/ผู้ติดต่อ too', async () => {
    render(wrap(<Harness initial={{ customer: testCustomer, project: testProject, contact: testContact, entryChannel: 'UNSPECIFIED' }} />));

    fireEvent.click(screen.getByRole('button', { name: 'ล้างลูกค้าที่เลือก' }));

    expect(screen.queryByText(testCustomer.name)).toBeNull();
    expect(await screen.findByLabelText(/^ลูกค้า/)).not.toBeNull(); // search input is back
    expect(screen.queryByText(testProject.name)).toBeNull(); // โครงการ chip is gone too
    expect(screen.getByLabelText(/^โครงการ/).disabled).toBe(true);
  });

  it('เพิ่มลูกค้าใหม่ creates a customer and selects it, without ever calling customers.search for the new name', async () => {
    api.customers.search.mockResolvedValue({ customers: [] });
    api.customers.create.mockResolvedValue({ customer: { id: 99, name: 'บริษัท ใหม่ จำกัด', taxId: null } });
    render(wrap(<Harness />));

    fireEvent.change(screen.getByLabelText(/^ลูกค้า/), { target: { value: 'xyz' } });
    fireEvent.focus(screen.getByLabelText(/^ลูกค้า/));
    fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));

    const nameInput = screen.getByPlaceholderText('บริษัท … จำกัด');
    fireEvent.change(nameInput, { target: { value: 'บริษัท ใหม่ จำกัด' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกลูกค้าใหม่' }));

    await waitFor(() => expect(api.customers.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'บริษัท ใหม่ จำกัด' })));
    expect(await screen.findByText('บริษัท ใหม่ จำกัด')).not.toBeNull();
  });

  it('F3: เพิ่มลูกค้าใหม่ seeds the name field from what was already typed into the typeahead', async () => {
    api.customers.search.mockResolvedValue({ customers: [] });
    render(wrap(<Harness />));

    fireEvent.change(screen.getByLabelText(/^ลูกค้า/), { target: { value: 'บริษัท ใหม่ จำกัด' } });
    await waitFor(() => expect(api.customers.search).toHaveBeenCalledWith('บริษัท ใหม่ จำกัด'));
    fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));

    // Was emptyNewCustomer()'s '' before the fix -- the rep had to retype the name they had
    // already typed into the search box to get here.
    expect(screen.getByPlaceholderText('บริษัท … จำกัด').value).toBe('บริษัท ใหม่ จำกัด');
  });

  it('F3: เพิ่มลูกค้าใหม่ from an empty search box still opens with an empty name field', async () => {
    render(wrap(<Harness />));

    fireEvent.focus(screen.getByLabelText(/^ลูกค้า/));
    fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));

    expect(screen.getByPlaceholderText('บริษัท … จำกัด').value).toBe('');
  });

  it('โครงการใหม่ creates a project under the selected customer and selects it', async () => {
    api.customers.createProject.mockResolvedValue({ project: { id: 2, customerId: 1, name: 'โครงการใหม่ทดสอบ' } });
    render(wrap(<Harness initial={{ customer: testCustomer, project: null, contact: null, entryChannel: 'UNSPECIFIED' }} />));

    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(testCustomer.id));
    fireEvent.focus(await screen.findByLabelText(/^โครงการ/));
    fireEvent.mouseDown(await screen.findByRole('option', { name: 'เพิ่มโครงการใหม่' }));

    const nameInput = screen.getByPlaceholderText('ชื่อโครงการ');
    fireEvent.change(nameInput, { target: { value: 'โครงการใหม่ทดสอบ' } });
    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มโครงการ' }));

    await waitFor(() => expect(api.customers.createProject).toHaveBeenCalledWith(testCustomer.id, { name: 'โครงการใหม่ทดสอบ' }));
    // The chip replaces the search input once a project is selected -- same pattern as ลูกค้า.
    await waitFor(() => expect(screen.getByText('โครงการใหม่ทดสอบ')).not.toBeNull());
  });

  // ── โครงการ type-ahead (owner testing feedback, 2026-09-11) ──────────────────────────────────
  describe('โครงการ type-ahead filter', () => {
    const projectA = { id: 1, customerId: 1, name: 'โครงการ Central Ladprao ชั้น B1' };
    const projectB = { id: 2, customerId: 1, name: 'โครงการ Siam Paragon ชั้น 3' };

    function renderWithProjects() {
      api.customers.projects.mockResolvedValue({ projects: [projectA, projectB] });
      return render(wrap(<Harness initial={{ customer: testCustomer, project: null, contact: null, entryChannel: 'UNSPECIFIED' }} />));
    }

    it('typing filters the already-loaded list client-side, without a second fetch', async () => {
      renderWithProjects();
      const field = await screen.findByLabelText(/^โครงการ/);
      fireEvent.focus(field);
      await screen.findByRole('option', { name: /Central Ladprao/ });
      expect(screen.getByRole('option', { name: /Siam Paragon/ })).not.toBeNull();

      fireEvent.change(field, { target: { value: 'Siam' } });

      expect(screen.queryByRole('option', { name: /Central Ladprao/ })).toBeNull();
      expect(screen.getByRole('option', { name: /Siam Paragon/ })).not.toBeNull();
      expect(api.customers.projects).toHaveBeenCalledTimes(1); // client-side filter, not a new request
    });

    it('ArrowDown highlights options and Enter picks the highlighted one', async () => {
      renderWithProjects();
      const field = await screen.findByLabelText(/^โครงการ/);
      fireEvent.focus(field);
      await screen.findByRole('option', { name: /Central Ladprao/ });

      fireEvent.keyDown(field, { key: 'ArrowDown' });
      fireEvent.keyDown(field, { key: 'ArrowDown' });
      fireEvent.keyDown(field, { key: 'Enter' });

      // Second row (index 1) is Siam Paragon -- picking it replaces the input with a chip.
      await waitFor(() => expect(screen.getByText(projectB.name)).not.toBeNull());
      expect(screen.queryByLabelText(/^โครงการ/)).toBeNull();
    });

    it('Escape closes the popup without picking anything', async () => {
      renderWithProjects();
      const field = await screen.findByLabelText(/^โครงการ/);
      fireEvent.focus(field);
      await screen.findByRole('option', { name: /Central Ladprao/ });

      fireEvent.keyDown(field, { key: 'Escape' });

      expect(screen.queryByRole('listbox', { name: 'ผลการค้นหาโครงการ' })).toBeNull();
      expect(screen.getByLabelText(/^โครงการ/)).not.toBeNull(); // still unselected, field still there
    });
  });

  it('a create-customer failure toasts the backend message and keeps the inline form open', async () => {
    const showToast = vi.fn();
    api.customers.create.mockRejectedValue(new Error('ชื่อลูกค้าซ้ำในระบบ'));
    render(wrap(<Harness showToast={showToast} />));

    fireEvent.focus(screen.getByLabelText(/^ลูกค้า/));
    fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));
    fireEvent.change(screen.getByPlaceholderText('บริษัท … จำกัด'), { target: { value: 'บริษัท ซ้ำ จำกัด' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกลูกค้าใหม่' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ชื่อลูกค้าซ้ำในระบบ'));
    // Still open -- the rep doesn't lose what they typed.
    expect(screen.getByPlaceholderText('บริษัท … จำกัด').value).toBe('บริษัท ซ้ำ จำกัด');
  });

  it('ช่องทางรับงาน defaults to ไม่ระบุ and toggles to the picked code on click', () => {
    render(wrap(<Harness />));
    const unspecified = screen.getByRole('button', { name: /ไม่ระบุ/ });
    expect(unspecified.getAttribute('aria-pressed')).toBe('true');

    const designerLed = screen.getAllByRole('button').find((b) => b.textContent.includes('ผู้ออกแบบ'));
    fireEvent.click(designerLed);
    expect(designerLed.getAttribute('aria-pressed')).toBe('true');
    expect(unspecified.getAttribute('aria-pressed')).toBe('false');
  });

  // ── F7 (owner, 2026-09-10): เลขที่ผู้เสียภาษี / โทร. on the SELECTED customer ────────────────
  describe('F7: editing the selected customer\'s เลขที่ผู้เสียภาษี / โทร.', () => {
    const withPhone = { ...testCustomer, phone: '02-111-2222' };

    it('renders neither field until a customer is picked', () => {
      render(wrap(<Harness />));
      expect(screen.queryByLabelText(/^เลขที่ผู้เสียภาษี/)).toBeNull();
      expect(screen.queryByLabelText(/^โทร\./)).toBeNull();
    });

    it('renders both fields prefilled from the selected customer record', () => {
      render(wrap(<Harness initial={{ customer: withPhone, project: null, contact: null, entryChannel: 'UNSPECIFIED' }} />));
      expect(screen.getByLabelText(/^เลขที่ผู้เสียภาษี/).value).toBe('0105565012345');
      expect(screen.getByLabelText(/^โทร\./).value).toBe('02-111-2222');
    });

    it('a customer with NO tax id renders an empty (not absent) field -- still quotable', () => {
      render(wrap(<Harness initial={{ customer: { id: 7, name: 'บริษัท ไม่มีเลขภาษี จำกัด' }, project: null, contact: null, entryChannel: 'UNSPECIFIED' }} />));
      expect(screen.getByLabelText(/^เลขที่ผู้เสียภาษี/).value).toBe('');
      expect(screen.getByLabelText(/^โทร\./).value).toBe('');
    });

    it('an edit PUTs ONLY the changed field, and the chip shows the saved value', async () => {
      api.customers.update.mockResolvedValue({ customer: { ...withPhone, taxId: '0105565099999' } });
      render(wrap(<Harness initial={{ customer: withPhone, project: null, contact: null, entryChannel: 'UNSPECIFIED' }} />));

      const taxIdField = screen.getByLabelText(/^เลขที่ผู้เสียภาษี/);
      fireEvent.change(taxIdField, { target: { value: '0105565099999' } });
      fireEvent.blur(taxIdField);

      await waitFor(() => expect(api.customers.update).toHaveBeenCalledTimes(1));
      // ONLY taxId -- not the whole record, so a one-field correction cannot clobber a phone
      // number some other session changed in the meantime (the endpoint is PATCH-shaped).
      expect(api.customers.update).toHaveBeenCalledWith(withPhone.id, { taxId: '0105565099999' });
      await waitFor(() => expect(screen.getByText('(0105565099999)')).not.toBeNull());
    });

    it('blurring an UNCHANGED field sends no request at all', async () => {
      render(wrap(<Harness initial={{ customer: withPhone, project: null, contact: null, entryChannel: 'UNSPECIFIED' }} />));
      fireEvent.blur(screen.getByLabelText(/^เลขที่ผู้เสียภาษี/));
      fireEvent.blur(screen.getByLabelText(/^โทร\./));
      expect(api.customers.update).not.toHaveBeenCalled();
    });

    it('a 403 surfaces the backend Thai message and RESTORES the previous value', async () => {
      const showToast = vi.fn();
      api.customers.update.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
      render(wrap(<Harness initial={{ customer: withPhone, project: null, contact: null, entryChannel: 'UNSPECIFIED' }} showToast={showToast} />));

      const phoneField = screen.getByLabelText(/^โทร\./);
      fireEvent.change(phoneField, { target: { value: '02-999-0000' } });
      fireEvent.blur(phoneField);

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ไม่มีสิทธิ์เข้าถึงรายการนี้'));
      // The rep must not walk away believing a refused correction was saved.
      await waitFor(() => expect(screen.getByLabelText(/^โทร\./).value).toBe('02-111-2222'));
    });

    it('the inline เพิ่มลูกค้าใหม่ form is unchanged -- it keeps its own four fields', async () => {
      api.customers.search.mockResolvedValue({ customers: [] });
      render(wrap(<Harness />));
      fireEvent.focus(screen.getByLabelText(/^ลูกค้า/));
      fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));

      expect(screen.getByPlaceholderText('บริษัท … จำกัด')).not.toBeNull();
      expect(screen.getByPlaceholderText('0105xxxxxxxxx')).not.toBeNull();
      expect(screen.getByPlaceholderText('02-xxx-xxxx')).not.toBeNull();
      expect(screen.getByPlaceholderText('ที่อยู่บริษัท')).not.toBeNull();
    });
  });

  it('renders Thai inline error hints passed via the errors prop', () => {
    render(wrap(
      <DealCustomerCard
        value={{ customer: null, project: null, contact: null, entryChannel: 'UNSPECIFIED' }}
        onChange={vi.fn()}
        errors={{ customer: 'กรุณาเลือกลูกค้า', project: 'กรุณาเลือกโครงการ' }}
      />,
    ));
    expect(screen.getByText('กรุณาเลือกลูกค้า')).not.toBeNull();
    expect(screen.getByText('กรุณาเลือกโครงการ')).not.toBeNull();
  });
});

// ── Owner, 2026-09-11: "a way for the sales to fill in the customer address" ────────────────────
describe('DealCustomerCard — ที่อยู่ on the selected customer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.projects.mockResolvedValue({ projects: [testProject] });
    api.customers.contacts.mockResolvedValue({ contacts: [testContact] });
  });

  const repeat = { ...testCustomer, phone: '02-111-2222', address: '201 ซอยสุขุมวิท 63\nเขตวัฒนา กทม. 10110' };
  const initial = (customer) => ({ customer, project: null, contact: null, entryChannel: 'UNSPECIFIED' });

  it('a REPEAT customer arrives with every saved detail prefilled, the address multi-line', () => {
    render(wrap(<Harness initial={initial(repeat)} />));
    const address = screen.getByLabelText(/^ที่อยู่/);
    expect(address.tagName).toBe('TEXTAREA');
    expect(address.value).toBe('201 ซอยสุขุมวิท 63\nเขตวัฒนา กทม. 10110');
    expect(screen.getByLabelText(/^เลขที่ผู้เสียภาษี/).value).toBe('0105565012345');
    expect(screen.getByLabelText(/^โทร\./).value).toBe('02-111-2222');
  });

  it('a blank address is an empty, fillable field — and a blur PUTs ONLY the address', async () => {
    api.customers.update.mockResolvedValue({ customer: { ...testCustomer, address: '1 ถนนพระราม 9' } });
    render(wrap(<Harness initial={initial(testCustomer)} />));
    const address = screen.getByLabelText(/^ที่อยู่/);
    expect(address.value).toBe('');
    fireEvent.change(address, { target: { value: '  1 ถนนพระราม 9  ' } });
    fireEvent.blur(address);
    await waitFor(() => expect(api.customers.update).toHaveBeenCalledWith(testCustomer.id, { address: '1 ถนนพระราม 9' }));
    await waitFor(() => expect(screen.getByLabelText(/^ที่อยู่/).value).toBe('1 ถนนพระราม 9'));
  });

  it('blurring an UNCHANGED address sends nothing', () => {
    render(wrap(<Harness initial={initial(repeat)} />));
    fireEvent.blur(screen.getByLabelText(/^ที่อยู่/));
    expect(api.customers.update).not.toHaveBeenCalled();
  });

  it('a refused address save restores the previous value and says why', async () => {
    const showToast = vi.fn();
    api.customers.update.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    render(wrap(<Harness initial={initial(repeat)} showToast={showToast} />));
    const address = screen.getByLabelText(/^ที่อยู่/);
    fireEvent.change(address, { target: { value: 'ที่อยู่ผิด' } });
    fireEvent.blur(address);
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    await waitFor(() => expect(screen.getByLabelText(/^ที่อยู่/).value).toBe(repeat.address));
  });
});
