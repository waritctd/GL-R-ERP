import React, { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ThaiAddressFields, emptyThaiAddress } from './ThaiAddressFields.jsx';
import { api } from '../../api/index.js';
import data from '../../data/thai-locations.json';
import { formatThaiAddress } from './thaiAddress.js';

globalThis.React = React;
vi.mock('../../api/index.js', () => ({ api: { locations: { provinces: vi.fn(), districts: vi.fn(), subdistricts: vi.fn() } } }));
function Harness() {
  const [value, setValue] = useState(emptyThaiAddress());
  return <><ThaiAddressFields value={value} onChange={(patch) => setValue((old) => ({ ...old, ...patch }))} /><output data-testid="value">{JSON.stringify(value)}</output></>;
}
function state() { return JSON.parse(screen.getByTestId('value').textContent); }
async function pick(label, name) {
  fireEvent.focus(screen.getByRole('combobox', { name: label }));
  fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value: name } });
  fireEvent.click(await screen.findByRole('option', { name, exact: true }));
}
beforeEach(() => {
  api.locations.provinces.mockResolvedValue({ items: data.provinces });
  api.locations.districts.mockImplementation(async (code) => ({ items: data.districts.filter((d) => d.provinceCode === code) }));
  api.locations.subdistricts.mockImplementation(async (code) => ({ items: data.subdistricts.filter((s) => s.districtCode === code) }));
});
function setup() { render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Harness /></QueryClientProvider>); }
describe('Thai address cascade', () => {
  it('selects Bangkok → Watthana → Khlong Toei Nuea and resolves postcode, labels and scoped options', async () => {
    setup();
    expect(screen.getByRole('combobox', { name: 'เขต / อำเภอ' }).disabled).toBe(true);
    expect(screen.getByRole('combobox', { name: 'แขวง / ตำบล' }).disabled).toBe(true);
    await pick('จังหวัด', 'กรุงเทพมหานคร');
    fireEvent.focus(screen.getByRole('combobox', { name: 'เขต' }));
    await screen.findByRole('option', { name: 'วัฒนา', exact: true });
    expect(screen.queryByRole('option', { name: 'เมืองเชียงใหม่' })).toBeNull();
    await pick('เขต', 'วัฒนา');
    fireEvent.focus(screen.getByRole('combobox', { name: 'แขวง' }));
    await screen.findByRole('option', { name: 'คลองเตยเหนือ', exact: true });
    expect(screen.getAllByRole('option').map((x) => x.textContent)).toEqual(['คลองเตยเหนือ', 'คลองตันเหนือ', 'พระโขนงเหนือ']);
    await pick('แขวง', 'คลองเตยเหนือ');
    expect(state()).toMatchObject({ provinceCode: '10', districtCode: '1039', subdistrictCode: '103901', postalCode: '10110' });
    expect(screen.getByLabelText('รหัสไปรษณีย์').value).toBe('10110');
    await pick('เขต', 'พระนคร');
    expect(state()).toMatchObject({ districtCode: '1001', subdistrictCode: '', postalCode: '' });
    await pick('จังหวัด', 'เชียงใหม่');
    expect(state()).toMatchObject({ provinceCode: '50', districtCode: '', subdistrictCode: '', postalCode: '' });
    expect(screen.getByRole('combobox', { name: 'ตำบล' }).disabled).toBe(true);
    await pick('อำเภอ', 'เมืองเชียงใหม่');
    await pick('ตำบล', 'สุเทพ');
    expect(state().postalCode).toBe('50200');
    fireEvent.click(screen.getByRole('button', { name: 'ล้างจังหวัด' }));
    expect(state()).toEqual(emptyThaiAddress());
  });
  it('keeps legacy address text byte-for-byte and avoids duplicated prefixes', () => {
    expect(formatThaiAddress({ address: 'ที่อยู่เดิม\nบรรทัดสอง' })).toBe('ที่อยู่เดิม\nบรรทัดสอง');
    expect(formatThaiAddress({ addressLine: '88/8', provinceCode: '10', districtCode: '1039', subdistrictCode: '103901', provinceNameTh: 'กรุงเทพมหานคร', districtNameTh: 'เขต วัฒนา', subdistrictNameTh: 'แขวง คลองเตยเหนือ', postalCode: '10110' })).toBe('88/8 แขวงคลองเตยเหนือ เขตวัฒนา กรุงเทพมหานคร 10110');
  });
});
