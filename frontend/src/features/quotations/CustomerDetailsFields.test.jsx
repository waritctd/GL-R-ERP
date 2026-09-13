import React, { useState } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
