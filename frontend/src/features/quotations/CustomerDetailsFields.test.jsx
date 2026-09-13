import React, { forwardRef, useImperativeHandle, useState } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, expect, it, vi } from 'vitest';
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

it('refuses to save a blank ชื่อลูกค้า: restores the previous name and shows the backend\'s own wording, with no request sent', async () => {
  // api.customers.update is a shared vi.fn() across this file's tests (no clearAllMocks), so this
  // asserts the call count is UNCHANGED by the blank-name blur, not that it is zero.
  const callsBefore = api.customers.update.mock.calls.length;
  const showToast = vi.fn();
  render(<QueryClientProvider client={new QueryClient()}><Harness showToast={showToast} /></QueryClientProvider>);
  const name = screen.getByLabelText('ชื่อลูกค้า');
  expect(name.value).toBe('Test');

  fireEvent.change(name, { target: { value: '   ' } });
  fireEvent.blur(name);

  expect(api.customers.update).toHaveBeenCalledTimes(callsBefore);
  expect(showToast).toHaveBeenCalledWith('error', 'กรุณาระบุชื่อลูกค้า');
  expect(name.value).toBe('Test');
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
  expect(screen.getByLabelText('ชื่อลูกค้า').value).toBe('บริษัท เต็มชื่อ จำกัด'); // untouched: took the new record
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
  expect(screen.getByLabelText('ชื่อลูกค้า').value).toBe('ลูกค้าอื่น');
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
