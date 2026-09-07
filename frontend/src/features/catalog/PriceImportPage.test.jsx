import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PriceImportPage } from './PriceImportPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// UX-07: product delete used to fire a native window.confirm() instead of the
// shared branded ConfirmDialog already used elsewhere in this same file (see
// the upload/commit confirm below). These tests cover the guard the fix adds:
// clicking delete must open the dialog and must NOT call the delete API until
// the dialog is explicitly confirmed.
//
// updateFactory/countries (V163 factory master-data merge): country is now a required <select>
// sourced from GET /api/price-import/countries instead of a free-text 2-letter input, and every
// factory row gets its own แก้ไข action backed by PUT /api/price-import/factories/{factoryId} —
// see the "factory master data" describe block below.
vi.mock('../../api/index.js', () => ({
  api: {
    priceImport: {
      factories: vi.fn(),
      versions: vi.fn(),
      createFactory: vi.fn(),
      updateFactory: vi.fn(),
      countries: vi.fn(),
      uploadAndCommit: vi.fn(),
    },
    catalog: {
      prices: vi.fn(),
      deleteProduct: vi.fn(),
    },
  },
}));

const MOCK_COUNTRIES = [
  { countryCode: 'IT', nameEn: 'Italy', nameTh: 'อิตาลี' },
  { countryCode: 'TH', nameEn: 'Thailand', nameTh: 'ไทย' },
];

function renderPage(props = {}) {
  return render(<PriceImportPage showToast={vi.fn()} {...props} />);
}

async function selectFactoryAndAwaitProduct() {
  renderPage();
  const select = await screen.findByLabelText('โรงงาน');
  fireEvent.change(select, { target: { value: '1' } });
  await screen.findByText('Stone Series');
}

beforeEach(() => {
  vi.clearAllMocks();
  api.priceImport.factories.mockResolvedValue([{ factoryId: 1, name: 'Rex Ceramics' }]);
  api.priceImport.versions.mockResolvedValue([]);
  api.priceImport.countries.mockResolvedValue(MOCK_COUNTRIES);
  api.catalog.prices.mockResolvedValue({
    items: [
      {
        priceId: 501,
        collection: 'Stone Series',
        productName: 'Grey Stone',
        productCode: 'STN-01',
        color: 'เทา',
        surface: 'ด้าน',
        sizeRaw: '60x60',
        price: 350,
        currency: 'THB',
        priceUnit: 'per_sqm',
      },
    ],
  });
  api.catalog.deleteProduct.mockResolvedValue({});
});

describe('PriceImportPage product delete (UX-07)', () => {
  it('opens the branded confirm dialog on delete click and does not call the API until confirmed', async () => {
    const nativeConfirm = vi.spyOn(window, 'confirm');
    await selectFactoryAndAwaitProduct();

    fireEvent.click(screen.getByRole('button', { name: 'ลบ' }));

    // The native dialog must never be invoked — the branded ConfirmDialog
    // (Modal with an <h2> title) replaces it entirely.
    expect(nativeConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'ลบสินค้า' })).not.toBeNull();
    expect(screen.getByText('ยืนยันลบ "Stone Series"?')).not.toBeNull();
    expect(api.catalog.deleteProduct).not.toHaveBeenCalled();

    nativeConfirm.mockRestore();
  });

  it('calls deleteProduct and refreshes the list only after the dialog is confirmed', async () => {
    await selectFactoryAndAwaitProduct();

    fireEvent.click(screen.getByRole('button', { name: 'ลบ' }));
    const confirmButton = await screen.findByRole('button', { name: 'ลบสินค้า' });
    fireEvent.click(confirmButton);

    await waitFor(() => expect(api.catalog.deleteProduct).toHaveBeenCalledWith(501));
    // loadProducts(factoryId) refresh: prices() is called once on factory
    // select and once more after a successful delete.
    await waitFor(() => expect(api.catalog.prices).toHaveBeenCalledTimes(2));
  });

  it('cancelling the dialog closes it without calling the API', async () => {
    await selectFactoryAndAwaitProduct();

    fireEvent.click(screen.getByRole('button', { name: 'ลบ' }));
    await screen.findByRole('heading', { name: 'ลบสินค้า' });

    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    await waitFor(() => expect(screen.queryByRole('heading', { name: 'ลบสินค้า' })).toBeNull());
    expect(api.catalog.deleteProduct).not.toHaveBeenCalled();
  });

  it('uses Thai display copy for price-import status and commit actions', async () => {
    api.priceImport.versions.mockResolvedValue([
      { versionId: 1, label: 'รายการราคา Q3', status: 'ACTIVE', uploadedAt: '2026-07-01T00:00:00Z' },
    ]);

    await selectFactoryAndAwaitProduct();

    expect(screen.getByText('อัปโหลดรายการราคาหรือแก้ไขรายสินค้าด้วยตนเอง')).not.toBeNull();
    expect(screen.getByText('รายการสินค้าที่ใช้งานอยู่')).not.toBeNull();
    expect(await screen.findByText('ใช้งานอยู่')).not.toBeNull();
    expect(screen.queryByText(/ACTIVE|Commit|price list/i)).toBeNull();
  });
});

// V163 merged sales.factory_config (the RFQ email directory that matched 0% of the real
// factories by name) onto price_catalog.factories, and added a country <select> (was free-text,
// the direct cause of "cannot add a factory" 500ing on a typo) plus an edit action per factory row
// — this is the only place จัดซื้อ can now put a real RFQ email on a real factory.
describe('PriceImportPage factory master data (V163)', () => {
  it('lists every factory with its master data and flags a missing RFQ email', async () => {
    api.priceImport.factories.mockResolvedValue([
      { factoryId: 1, name: 'Panaria SpA', country: 'IT', defaultCurrency: 'EUR', email: null, unit: 'sqm' },
      { factoryId: 2, name: 'SCG Ceramics', country: 'TH', defaultCurrency: 'THB', email: 'buy@scg.example', unit: 'piece' },
    ]);
    renderPage();

    expect(await screen.findByText('ข้อมูลโรงงาน')).not.toBeNull();
    expect(screen.getByText('อิตาลี (IT)')).not.toBeNull();
    expect(screen.getByText('ไทย (TH)')).not.toBeNull();
    expect(screen.getByText('buy@scg.example')).not.toBeNull();
    // Panaria has no email — flagged, not silently blank, and counted in the summary line.
    expect(screen.getByText('ยังไม่ระบุ')).not.toBeNull();
    expect(screen.getByText('1 จาก 2 โรงงานยังไม่มีอีเมลขอราคา — กด "แก้ไข" เพื่อเพิ่ม')).not.toBeNull();
  });

  it('populates the add-factory country select from GET /api/price-import/countries and submits the chosen code', async () => {
    api.priceImport.createFactory.mockResolvedValue({
      factoryId: 9, name: 'New Factory', country: 'IT', defaultCurrency: 'EUR', email: null, unit: 'piece',
    });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มโรงงาน/ }));
    const countrySelect = await screen.findByLabelText(/ประเทศ/);
    // The old free-text "ประเทศ (2 อักษร)" input is gone — this is a real <select> of the fetched roster.
    expect(countrySelect.tagName).toBe('SELECT');
    expect(screen.getByRole('option', { name: 'อิตาลี (IT)' })).not.toBeNull();

    fireEvent.change(screen.getByLabelText(/ชื่อโรงงาน/), { target: { value: 'New Factory' } });
    fireEvent.change(countrySelect, { target: { value: 'IT' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.priceImport.createFactory).toHaveBeenCalledWith(
      'New Factory', 'IT', 'EUR', '', 'piece',
    ));
  });

  it('does not submit the add-factory form without a country, and surfaces the backend 400 message on failure', async () => {
    api.priceImport.createFactory.mockRejectedValue(new Error('ต้องระบุประเทศของโรงงาน'));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มโรงงาน/ }));
    fireEvent.change(await screen.findByLabelText(/ชื่อโรงงาน/), { target: { value: 'No Country Factory' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    // Client-side guard: blank country never even reaches the API.
    expect(api.priceImport.createFactory).not.toHaveBeenCalled();
    expect(await screen.findByText('กรุณาเลือกประเทศ')).not.toBeNull();

    fireEvent.change(screen.getByLabelText(/ประเทศ/), { target: { value: 'IT' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    // Now it does reach the API, and a 400 from the server is shown verbatim, not a generic failure.
    await waitFor(() => expect(api.priceImport.createFactory).toHaveBeenCalled());
    expect(await screen.findByText('ต้องระบุประเทศของโรงงาน')).not.toBeNull();
  });

  it('opens the edit modal pre-filled with the existing factory and saves via PUT', async () => {
    api.priceImport.factories.mockResolvedValue([
      { factoryId: 3, name: 'CDE', country: 'IT', defaultCurrency: 'EUR', email: null, unit: 'piece' },
    ]);
    api.priceImport.updateFactory.mockResolvedValue({
      factoryId: 3, name: 'CDE', country: 'IT', defaultCurrency: 'EUR', email: 'buy@cde.example', unit: 'piece',
    });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'แก้ไข' }));
    expect(await screen.findByRole('heading', { name: 'แก้ไขข้อมูลโรงงาน' })).not.toBeNull();
    // Pre-filled from the row being edited, not blank fields as if this were "add".
    expect(screen.getByLabelText(/ชื่อโรงงาน/).value).toBe('CDE');
    expect(screen.getByLabelText(/ประเทศ/).value).toBe('IT');

    fireEvent.change(screen.getByLabelText('อีเมลขอราคา (ถ้ามี)'), { target: { value: 'buy@cde.example' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.priceImport.updateFactory).toHaveBeenCalledWith(
      3, 'CDE', 'IT', 'EUR', 'buy@cde.example', 'piece',
    ));
    // The list re-renders with the saved email — no page reload needed to see the fix take effect.
    expect(await screen.findByText('buy@cde.example')).not.toBeNull();
  });

  it('surfaces a 409 duplicate-name conflict from updateFactory verbatim', async () => {
    api.priceImport.factories.mockResolvedValue([
      { factoryId: 3, name: 'CDE', country: 'IT', defaultCurrency: 'EUR', email: null, unit: 'piece' },
    ]);
    api.priceImport.updateFactory.mockRejectedValue(new Error('มีโรงงานชื่อนี้อยู่แล้ว: LEA'));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'แก้ไข' }));
    fireEvent.change(await screen.findByLabelText(/ชื่อโรงงาน/), { target: { value: 'LEA' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    expect(await screen.findByText('มีโรงงานชื่อนี้อยู่แล้ว: LEA')).not.toBeNull();
  });
});
