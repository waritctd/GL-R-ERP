import React, { forwardRef, useImperativeHandle, useState } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CustomerDetailsFields } from './CustomerDetailsFields.jsx';
import { api } from '../../api/index.js';
import data from '../../data/thai-locations.json';
globalThis.React = React;
vi.mock('../../api/index.js', () => ({ api: { customers: { update: vi.fn() }, locations: { provinces: vi.fn(), districts: vi.fn(), subdistricts: vi.fn() } } }));
const legacy = { id: 5, name: 'Test', address: 'ข้อความเดิม\nยังไม่ทราบจังหวัด' };
function Harness({ showToast } = {}) {
  const [customer, setCustomer] = useState(legacy);
  return <CustomerDetailsFields customer={customer} onChange={setCustomer} showToast={showToast} />;
}

// The real caller (QuotationEditorPage's `summaryCustomer`) swaps the `customer` prop out from
// under the component whenever the live record query resolves -- independently of anything
// CustomerDetailsFields itself does through `onChange`. `Harness` above ties `customer` to
// `onChange` one-for-one, which can never reproduce that: the only way `customer` changes there is
// a save response this component made itself. This ref-controlled harness lets a test push a new
// `customer` prop the way the page does (a record refresh), while `onChange` still flows to the
// same state so a save's own optimistic/rollback updates behave exactly as in the app.
const ResyncHarness = forwardRef(function ResyncHarness({ initial, showToast }, ref) {
  const [customer, setCustomer] = useState(initial);
  useImperativeHandle(ref, () => ({ setCustomer }));
  return <CustomerDetailsFields customer={customer} onChange={setCustomer} showToast={showToast} />;
});
beforeEach(() => {
  api.locations.provinces.mockResolvedValue({ items: data.provinces });
  api.locations.districts.mockResolvedValue({ items: data.districts.filter((d) => d.provinceCode === '10') });
  api.locations.subdistricts.mockResolvedValue({ items: data.subdistricts.filter((s) => s.districtCode === '1039') });
});
it('preserves legacy text on cancel, then saves a complete structured address explicitly', async () => {
  render(<QueryClientProvider client={new QueryClient()}><Harness /></QueryClientProvider>);
  expect(screen.getByLabelText('ที่อยู่').value).toBe(legacy.address);
  fireEvent.click(screen.getByRole('button', { name: 'แก้ไขที่อยู่แบบแยกจังหวัด' }));
  expect(screen.getByRole('combobox', { name: 'จังหวัด' }).value).toBe('');
  expect(screen.getByLabelText('เลขที่ / อาคาร / หมู่ / ซอย / ถนน').value).toBe('');
  fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));
  expect(screen.getByLabelText('ที่อยู่').value).toBe(legacy.address);
  expect(api.customers.update).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'แก้ไขที่อยู่แบบแยกจังหวัด' }));
  for (const [label, name] of [['จังหวัด', 'กรุงเทพมหานคร'], ['เขต', 'วัฒนา'], ['แขวง', 'คลองเตยเหนือ']]) {
    fireEvent.focus(screen.getByRole('combobox', { name: label }));
    fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value: name } });
    fireEvent.click(await screen.findByRole('option', { name, exact: true }));
  }
  api.customers.update.mockResolvedValue({ customer: { ...legacy, provinceCode: '10', districtCode: '1039', subdistrictCode: '103901', postalCode: '10110', address: 'คลองเตยเหนือ วัฒนา กรุงเทพมหานคร 10110' } });
  fireEvent.click(screen.getByRole('button', { name: 'บันทึกที่อยู่' }));
  await waitFor(() => expect(api.customers.update).toHaveBeenCalledWith(5, { addressLine: '', provinceCode: '10', districtCode: '1039', subdistrictCode: '103901', postalCode: '10110' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(screen.getByLabelText('ที่อยู่').readOnly).toBe(true);
});

// Reproduces the resync race: QuotationEditorPage's `summaryCustomer` first renders the
// quotation's frozen snapshot, then the live customer record query resolves with different
// values for the SAME customer id. The effect that re-seeds `edits` from every `customer.*`
// change used to overwrite whatever the rep had typed but not yet blurred.
it('a dirty edit survives a record refresh for the SAME customer', async () => {
  const ref = React.createRef();
  const snapshot = { id: 5, name: 'Test', taxId: '', phone: '', address: 'ข้อความเดิม' };
  render(<QueryClientProvider client={new QueryClient()}><ResyncHarness ref={ref} initial={snapshot} /></QueryClientProvider>);

  const taxId = screen.getByLabelText('เลขที่ผู้เสียภาษี');
  fireEvent.change(taxId, { target: { value: '0105551234567' } }); // typed, NOT blurred yet

  // The live record arrives: same id, different name/phone/taxId (the rep's still-unsaved value).
  act(() => { ref.current.setCustomer({ id: 5, name: 'บริษัท เต็มชื่อ จำกัด', taxId: '9999999999999', phone: '02-111-2222', address: 'ข้อความเดิม' }); });

  expect(taxId.value).toBe('0105551234567'); // dirty field: kept the rep's typed text
  expect(screen.getByLabelText('โทร.').value).toBe('02-111-2222'); // untouched: took the new record
});

it('switching to a DIFFERENT customer discards a dirty edit and resets every field', async () => {
  const ref = React.createRef();
  const first = { id: 5, name: 'Test', taxId: '', phone: '02-000-0000', address: 'ที่อยู่เดิม' };
  render(<QueryClientProvider client={new QueryClient()}><ResyncHarness ref={ref} initial={first} /></QueryClientProvider>);

  const taxId = screen.getByLabelText('เลขที่ผู้เสียภาษี');
  fireEvent.change(taxId, { target: { value: '0105551234567' } }); // dirty, unsaved

  const second = { id: 9, name: 'ลูกค้าอื่น', taxId: '0207778889990', phone: '081-234-5678', address: 'ที่อยู่ใหม่' };
  act(() => { ref.current.setCustomer(second); });

  expect(taxId.value).toBe('0207778889990'); // the dirty edit belonged to customer 5, not 9 — discarded
  expect(screen.getByLabelText('โทร.').value).toBe('081-234-5678');
  expect(screen.getByLabelText('ที่อยู่').value).toBe('ที่อยู่ใหม่');
});

it('a save response re-seeds the field with the SERVER value, even if normalised', async () => {
  const showToast = vi.fn();
  render(<QueryClientProvider client={new QueryClient()}><Harness showToast={showToast} /></QueryClientProvider>);
  const taxId = screen.getByLabelText('เลขที่ผู้เสียภาษี');

  api.customers.update.mockResolvedValueOnce({ customer: { ...legacy, taxId: '0-1055-56000-00-0' } });
  fireEvent.change(taxId, { target: { value: '0105556000000' } });
  fireEvent.blur(taxId);

  await waitFor(() => expect(api.customers.update).toHaveBeenCalledWith(5, { taxId: '0105556000000' }));
  await waitFor(() => expect(taxId.value).toBe('0-1055-56000-00-0'));
});

it('a refused save rolls back the field, then a later refresh applies cleanly', async () => {
  const showToast = vi.fn();
  const ref = React.createRef();
  const initial = { id: 5, name: 'Test', taxId: '0105551234567', phone: '', address: '' };
  render(<QueryClientProvider client={new QueryClient()}><ResyncHarness ref={ref} initial={initial} showToast={showToast} /></QueryClientProvider>);
  const taxId = screen.getByLabelText('เลขที่ผู้เสียภาษี');

  api.customers.update.mockRejectedValueOnce(new Error('บันทึกข้อมูลลูกค้าไม่สำเร็จ'));
  fireEvent.change(taxId, { target: { value: '9999999999999' } });
  fireEvent.blur(taxId);

  await waitFor(() => expect(taxId.value).toBe('0105551234567')); // rolled back to the previous value
  expect(showToast).toHaveBeenCalledWith('error', 'บันทึกข้อมูลลูกค้าไม่สำเร็จ');

  // A later record refresh with a DIFFERENT value for that field now applies -- the field is
  // clean again after the rollback, not stuck "dirty" forever.
  act(() => { ref.current.setCustomer({ id: 5, name: 'Test', taxId: '0207778889990', phone: '', address: '' }); });
  expect(taxId.value).toBe('0207778889990');
});

// Owner feedback (2026-09-14): "sometimes there's a typo in the name so they should be able to
// correct it" — ชื่อลูกค้า joins the same generic save-on-blur mechanism the other three fields
// already use.
it('saves a corrected customer name on blur', async () => {
  render(<QueryClientProvider client={new QueryClient()}><Harness /></QueryClientProvider>);
  const name = screen.getByLabelText('ชื่อลูกค้า');
  expect(name.value).toBe(legacy.name);

  api.customers.update.mockResolvedValueOnce({ customer: { ...legacy, name: 'บริษัท เต็มชื่อ จำกัด' } });
  fireEvent.change(name, { target: { value: 'บริษัท เต็มชื่อ จำกัด' } });
  fireEvent.blur(name);

  await waitFor(() => expect(api.customers.update).toHaveBeenCalledWith(5, { name: 'บริษัท เต็มชื่อ จำกัด' }));
  await waitFor(() => expect(name.value).toBe('บริษัท เต็มชื่อ จำกัด'));
});

// The backend refuses a blank name (NOT NULL column, "กรุณาระบุชื่อลูกค้า") — this pins that
// saveField's EXISTING generic rollback/toast path covers it with no special-casing, the same way
// it already covers a refused taxId (the test just above this one).
it('a blank name is refused by the server and rolled back', async () => {
  const showToast = vi.fn();
  render(<QueryClientProvider client={new QueryClient()}><Harness showToast={showToast} /></QueryClientProvider>);
  const name = screen.getByLabelText('ชื่อลูกค้า');

  api.customers.update.mockRejectedValueOnce(new Error('กรุณาระบุชื่อลูกค้า'));
  fireEvent.change(name, { target: { value: '' } });
  fireEvent.blur(name);

  await waitFor(() => expect(name.value).toBe(legacy.name)); // rolled back
  expect(showToast).toHaveBeenCalledWith('error', 'กรุณาระบุชื่อลูกค้า');
});

// Bug fix (prod customer_id 4326 "DONG NGO GROUP..." / QT-2026-0039-1): a customer already saved
// with a Thai structured address could never become a foreign/free-text one. The shape below
// mirrors 4326's real prod row (fake structured codes standing in for a Vietnamese address).
const structuredForeignCandidate = {
  id: 4326,
  name: 'DONG NGO GROUP INVESTMENT AND DEVELOPMENT JSC',
  taxId: '',
  phone: '',
  address: '---Vietnam--- แขวงคลองตันเหนือ เขตวัฒนา กรุงเทพมหานคร 10110',
  provinceCode: '10',
  districtCode: '1039',
  subdistrictCode: '103902',
  postalCode: '10110',
};

describe('switching a structured customer to ลูกค้าต่างประเทศ / ที่อยู่นอกประเทศไทย', () => {
  it('is not offered for a customer that already has no structured codes', () => {
    render(<QueryClientProvider client={new QueryClient()}><Harness /></QueryClientProvider>);
    expect(screen.queryByRole('checkbox', { name: /ลูกค้าต่างประเทศ/ })).toBeNull();
    expect(screen.getByLabelText('ที่อยู่').readOnly).toBe(false);
  });

  it('unlocks the textarea and saves with ONLY the address key — no structured keys, not even blank', async () => {
    const ref = React.createRef();
    render(<QueryClientProvider client={new QueryClient()}><ResyncHarness ref={ref} initial={structuredForeignCandidate} /></QueryClientProvider>);
    const address = screen.getByLabelText('ที่อยู่');
    expect(address.readOnly).toBe(true);

    fireEvent.click(screen.getByRole('checkbox', { name: /ลูกค้าต่างประเทศ/ }));
    expect(address.readOnly).toBe(false);

    const freeText = 'No. 12 Le Loi Street, District 1, Ho Chi Minh City, Vietnam';
    fireEvent.change(address, { target: { value: freeText } });
    api.customers.update.mockResolvedValueOnce({
      customer: { ...structuredForeignCandidate, address: freeText, provinceCode: null, districtCode: null, subdistrictCode: null, postalCode: null },
    });
    fireEvent.blur(address);

    await waitFor(() => expect(api.customers.update).toHaveBeenCalledWith(4326, { address: freeText }));
    // The exact clearing contract: no structured key at all, not even as null/blank.
    const sentPayload = api.customers.update.mock.calls.at(-1)[1];
    expect(Object.keys(sentPayload)).toEqual(['address']);

    // Reflects the SERVER's returned record: provinceCode is now null, so readOnly drops on its
    // own and the switch checkbox — having nothing left to switch — is gone.
    await waitFor(() => expect(address.readOnly).toBe(false));
    expect(screen.queryByRole('checkbox', { name: /ลูกค้าต่างประเทศ/ })).toBeNull();
  });

  it('a refused switch rolls the address AND the checkbox back — the field returns to readOnly', async () => {
    const showToast = vi.fn();
    const ref = React.createRef();
    render(<QueryClientProvider client={new QueryClient()}><ResyncHarness ref={ref} initial={structuredForeignCandidate} showToast={showToast} /></QueryClientProvider>);
    const address = screen.getByLabelText('ที่อยู่');

    fireEvent.click(screen.getByRole('checkbox', { name: /ลูกค้าต่างประเทศ/ }));
    fireEvent.change(address, { target: { value: 'a rejected free-text address' } });
    api.customers.update.mockRejectedValueOnce(new Error('บันทึกข้อมูลลูกค้าไม่สำเร็จ'));
    fireEvent.blur(address);

    await waitFor(() => expect(address.value).toBe(structuredForeignCandidate.address)); // rolled back
    expect(showToast).toHaveBeenCalledWith('error', 'บันทึกข้อมูลลูกค้าไม่สำเร็จ');
    expect(address.readOnly).toBe(true); // structured again — the toggle was dropped, not left "unlocked"
    expect(screen.getByRole('checkbox', { name: /ลูกค้าต่างประเทศ/ }).checked).toBe(false);
  });

  it('unchecking before saving re-locks the field without sending any request', () => {
    const callsBefore = api.customers.update.mock.calls.length;
    render(<QueryClientProvider client={new QueryClient()}><ResyncHarness initial={structuredForeignCandidate} /></QueryClientProvider>);
    const address = screen.getByLabelText('ที่อยู่');
    const checkbox = screen.getByRole('checkbox', { name: /ลูกค้าต่างประเทศ/ });

    fireEvent.click(checkbox);
    expect(address.readOnly).toBe(false);
    fireEvent.click(checkbox);
    expect(address.readOnly).toBe(true);
    expect(api.customers.update.mock.calls.length).toBe(callsBefore); // no blur-triggered save happened
  });

  it('picking a DIFFERENT structured customer resets the toggle rather than carrying it over', () => {
    const ref = React.createRef();
    render(<QueryClientProvider client={new QueryClient()}><ResyncHarness ref={ref} initial={structuredForeignCandidate} /></QueryClientProvider>);
    fireEvent.click(screen.getByRole('checkbox', { name: /ลูกค้าต่างประเทศ/ }));
    expect(screen.getByLabelText('ที่อยู่').readOnly).toBe(false);

    const other = { ...structuredForeignCandidate, id: 4328, name: 'คุณออย' };
    act(() => { ref.current.setCustomer(other); });

    expect(screen.getByLabelText('ที่อยู่').readOnly).toBe(true); // back to locked for the NEW customer
    expect(screen.getByRole('checkbox', { name: /ลูกค้าต่างประเทศ/ }).checked).toBe(false);
  });
});

it('focusing and leaving a field without typing is not dirty: a refresh still applies', async () => {
  const ref = React.createRef();
  const initial = { id: 5, name: 'Test', taxId: '0105551234567', phone: '', address: '' };
  render(<QueryClientProvider client={new QueryClient()}><ResyncHarness ref={ref} initial={initial} /></QueryClientProvider>);
  const taxId = screen.getByLabelText('เลขที่ผู้เสียภาษี');

  fireEvent.focus(taxId);
  fireEvent.blur(taxId); // untouched -- saveField's own `next === previous.trim()` guard, no request

  act(() => { ref.current.setCustomer({ id: 5, name: 'Test', taxId: '0207778889990', phone: '', address: '' }); });
  expect(taxId.value).toBe('0207778889990');
});
