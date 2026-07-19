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
vi.mock('../../api/index.js', () => ({
  api: {
    priceImport: {
      factories: vi.fn(),
      versions: vi.fn(),
      createFactory: vi.fn(),
      uploadAndCommit: vi.fn(),
    },
    catalog: {
      prices: vi.fn(),
      deleteProduct: vi.fn(),
    },
  },
}));

function renderPage(props = {}) {
  return render(<PriceImportPage showToast={vi.fn()} {...props} />);
}

async function selectFactoryAndAwaitProduct() {
  renderPage();
  const select = await screen.findByLabelText('โรงงาน');
  fireEvent.change(select, { target: { value: '1' } });
  await screen.findByText('Stone Series');
}

describe('PriceImportPage product delete (UX-07)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.priceImport.factories.mockResolvedValue([{ factoryId: 1, name: 'Rex Ceramics' }]);
    api.priceImport.versions.mockResolvedValue([]);
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
});
