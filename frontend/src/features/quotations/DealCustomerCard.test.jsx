import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealCustomerCard } from './DealCustomerCard.jsx';
import data from '../../data/thai-locations.json';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  // Imported inside the factory, not at the top of the file: vi.mock is hoisted above every import.
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      // The ผู้รับ field reads the deal's route from the served stage catalog: the SAME canned payload
      // mockApi serves (data/dealStageCatalog.js, pinned against DealStage.java / DealRoute.java by
      // stageCatalog.test.js), never a fixture of its own.
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      locations: { provinces: vi.fn(), districts: vi.fn(), subdistricts: vi.fn() },
      customers: {
        search: vi.fn(),
        create: vi.fn(),
        projects: vi.fn(),
        contacts: vi.fn(),
        update: vi.fn(),
      },
      // The "เลือกดีลที่มีอยู่" branch mounts DealPicker, which reads the deal list.
      tickets: { list: vi.fn() },
    },
  };
});

const testCustomer = { id: 1, name: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด', taxId: '0105565012345' };
const testProject = { id: 1, customerId: 1, name: 'โครงการ Central Ladprao ชั้น B1' };
const testContact = { id: 1, customerId: 1, firstName: 'วิภา', lastName: 'สมิทธ์' };

// The card calls useQueryClient() (F7 invalidates the cached customer searches after a successful customer
// edit), so every render here needs a provider -- as it has in the app, where this card only ever renders
// inside QuotationEditorPage.
function wrap(ui) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>;
}

function Harness({ initial, showToast = vi.fn(), errors }) {
  const [value, setValue] = React.useState(initial ?? { customer: null, project: null, contact: null, entryChannel: 'UNSPECIFIED' });
  return <DealCustomerCard value={value} onChange={(patch) => setValue((prev) => ({ ...prev, ...patch }))} showToast={showToast} errors={errors} />;
}

describe('DealCustomerCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.locations.provinces.mockResolvedValue({ items: data.provinces });
    api.locations.districts.mockResolvedValue({ items: data.districts.filter((d) => d.provinceCode === '10') });
    api.locations.subdistricts.mockResolvedValue({ items: data.subdistricts.filter((d) => d.districtCode === '1039') });
    api.customers.projects.mockResolvedValue({ projects: [testProject] });
    api.customers.contacts.mockResolvedValue({ contacts: [testContact] });
  });

  it('searches as the rep types, and selecting a result replaces the input with a value chip', async () => {
    api.customers.search.mockResolvedValue({ customers: [testCustomer] });
    render(wrap(<Harness />));

    fireEvent.change(screen.getByLabelText(/^ลูกค้า/), { target: { value: 'ก้าวหน้า' } });
    await waitFor(() => expect(api.customers.search).toHaveBeenCalledWith('ก้าวหน้า'));

    // role="option", not "button": the typeahead popup is a real listbox.
    const option = await screen.findByRole('option', { name: new RegExp(testCustomer.name) });
    fireEvent.mouseDown(option);

    expect(screen.getByText(testCustomer.name)).not.toBeNull();
    expect(screen.queryByLabelText(/^ลูกค้า/)).toBeNull(); // the search input is gone, replaced by the chip
    // Loads โครงการ for the newly-picked customer. This card does not fetch ผู้ติดต่อ at all.
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(testCustomer.id));
    expect(api.customers.contacts).not.toHaveBeenCalled();
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
    await selectAddress();
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกลูกค้าใหม่' }));

    await waitFor(() => expect(api.customers.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'บริษัท ใหม่ จำกัด' })));
    expect(await screen.findByText('บริษัท ใหม่ จำกัด')).not.toBeNull();
  });

  it('a create-customer failure toasts the backend message and keeps the inline form open', async () => {
    const showToast = vi.fn();
    api.customers.create.mockRejectedValue(new Error('ชื่อลูกค้าซ้ำในระบบ'));
    render(wrap(<Harness showToast={showToast} />));

    fireEvent.focus(screen.getByLabelText(/^ลูกค้า/));
    fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));
    fireEvent.change(screen.getByPlaceholderText('บริษัท … จำกัด'), { target: { value: 'บริษัท ซ้ำ จำกัด' } });
    await selectAddress();
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกลูกค้าใหม่' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ชื่อลูกค้าซ้ำในระบบ'));
    // Still open -- the rep doesn't lose what they typed.
    expect(screen.getByPlaceholderText('บริษัท … จำกัด').value).toBe('บริษัท ซ้ำ จำกัด');
  });

  // ── เลขที่ผู้เสียภาษี / โทร. on the SELECTED customer ──
  describe('F7: editing the selected customer\'s เลขที่ผู้เสียภาษี / โทร.', () => {
    const withPhone = { ...testCustomer, phone: '02-111-2222' };

    it('an edit PUTs ONLY the changed field, and the chip shows the saved value', async () => {
      api.customers.update.mockResolvedValue({ customer: { ...withPhone, taxId: '0105565099999' } });
      render(wrap(<Harness initial={{ customer: withPhone, project: null, contact: null, entryChannel: 'UNSPECIFIED' }} />));

      const taxIdField = screen.getByLabelText(/^เลขที่ผู้เสียภาษี/);
      fireEvent.change(taxIdField, { target: { value: '0105565099999' } });
      fireEvent.blur(taxIdField);

      await waitFor(() => expect(api.customers.update).toHaveBeenCalledTimes(1));
      // ONLY taxId -- not the whole record, so a one-field correction cannot clobber a phone number some other
      // session changed in the meantime (the endpoint is PATCH-shaped).
      expect(api.customers.update).toHaveBeenCalledWith(withPhone.id, { taxId: '0105565099999' });
      await waitFor(() => expect(screen.getByText('(0105565099999)')).not.toBeNull());
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
  });

  // ── Foreign-customer address ──
  describe('เพิ่มลูกค้าใหม่ — ลูกค้าต่างประเทศ / ที่อยู่นอกประเทศไทย', () => {
    async function openNewCustomerModal() {
      api.customers.search.mockResolvedValue({ customers: [] });
      render(wrap(<Harness />));
      fireEvent.focus(screen.getByLabelText(/^ลูกค้า/));
      fireEvent.mouseDown(await screen.findByRole('button', { name: 'เพิ่มลูกค้าใหม่' }));
    }

    it('submits a foreign customer with a free-text address and NO structured codes, hitting the non-structured backend path', async () => {
      api.customers.create.mockResolvedValue({ customer: { id: 101, name: 'Vietnam Tiles Co., Ltd.', taxId: null } });
      await openNewCustomerModal();

      fireEvent.change(screen.getByPlaceholderText('บริษัท … จำกัด'), { target: { value: 'Vietnam Tiles Co., Ltd.' } });
      fireEvent.click(screen.getByRole('checkbox', { name: /ลูกค้าต่างประเทศ/ }));
      // No tax id required for a foreign customer -- Save must not be blocked on it.
      expect(screen.getByRole('button', { name: 'บันทึกลูกค้าใหม่' }).disabled).toBe(false);

      fireEvent.change(screen.getByLabelText(/^ที่อยู่/), { target: { value: '123 Nguyen Hue St, District 1, Ho Chi Minh City, Vietnam' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกลูกค้าใหม่' }));

      await waitFor(() => expect(api.customers.create).toHaveBeenCalledTimes(1));
      const payload = api.customers.create.mock.calls[0][0];
      expect(payload).toEqual({
        name: 'Vietnam Tiles Co., Ltd.',
        taxId: null,
        address: '123 Nguyen Hue St, District 1, Ho Chi Minh City, Vietnam',
        phone: null,
      });
      // CustomerController#structured / mockApi's structuredCustomerAddress both flag "structured" on
      // ANY of these keys being non-null, an empty string included. The payload must not carry them
      // at all, not even as ''.
      for (const key of ['addressLine', 'provinceCode', 'districtCode', 'subdistrictCode', 'postalCode']) {
        expect(payload[key]).toBeUndefined();
      }
      await waitFor(() => expect(screen.getByText('Vietnam Tiles Co., Ltd.')).not.toBeNull());
    });
  });
});

describe('DealCustomerCard — off-route pills on a PICKED deal (owner ruling 2026-11-07)', () => {
  const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
  const isDisabled = (el) => el.disabled || el.getAttribute('aria-disabled') === 'true';

  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: [] });
  });

  function renderPicked(selectedDeal, recipientType) {
    return render(wrap(
      <MemoryRouter initialEntries={['/quotations/new?ticket=18']}>
        <DealCustomerCard
          user={salesUser}
          mode="pick"
          onModeChange={vi.fn()}
          selectedDeal={selectedDeal}
          value={{ customer: null, project: null, entryChannel: 'UNSPECIFIED', recipientType }}
          onChange={vi.fn()}
          showToast={vi.fn()}
        />
      </MemoryRouter>,
    ));
  }

  it('reads the PICKED deal\'s own channel: an owner-direct deal disables ผู้ออกแบบ and says why', async () => {
    renderPicked({ id: 18, code: 'DL-2026-0018', customerName: 'ก', salesStage: 'PRESENTATION', entryChannel: 'OWNER_DIRECT' }, 'OWNER');
    expect((await screen.findAllByText('ช่องทางรับงานของงานนี้คือเจ้าของติดต่อโดยตรง — ขั้นที่ 4 ไม่อยู่ในเส้นทางนี้ — แก้ช่องทางรับงานก่อน')).length)
      .toBeGreaterThan(0);
    expect(isDisabled(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }))).toBe(true);
    expect(isDisabled(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }))).toBe(false);
    expect(isDisabled(screen.getByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' }))).toBe(false);
  });

  it('a designer-led deal: every pill enabled, no reason', async () => {
    renderPicked({ id: 18, code: 'DL-2026-0018', customerName: 'ก', salesStage: 'PRESENTATION', entryChannel: 'DESIGNER_LED' }, 'DESIGNER');
    await waitFor(() => expect(api.meta.dealStages).toHaveBeenCalled());
    await new Promise((resolve) => { setTimeout(resolve, 0); });
    screen.getAllByRole('radio').forEach((pill) => expect(isDisabled(pill)).toBe(false));
    expect(screen.queryByText(/ไม่อยู่ในเส้นทางนี้/)).toBeNull();
  });
});

async function selectAddress() {
  for (const [label, name] of [['จังหวัด', 'กรุงเทพมหานคร'], ['เขต', 'วัฒนา'], ['แขวง', 'คลองเตยเหนือ']]) {
    fireEvent.focus(screen.getByRole('combobox', { name: label }));
    fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value: name } });
    fireEvent.click(await screen.findByRole('option', { name, exact: true }));
  }
}
