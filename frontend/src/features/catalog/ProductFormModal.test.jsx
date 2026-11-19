import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProductFormModal } from './ProductFormModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// UX-03 slice 1/5: ProductFormModal migrated from hand-rolled useState + single bottom-of-form error string to
// react-hook-form + zodResolver + FormField, proving the pattern on the smallest sales-stack surface first.
vi.mock('../../api/index.js', () => ({
  api: {
    catalog: {
      addProduct: vi.fn(),
      updateProduct: vi.fn(),
    },
  },
}));

function renderModal(props = {}) {
  return render(<ProductFormModal onClose={vi.fn()} onSaved={vi.fn()} {...props} />);
}

describe('ProductFormModal validation (UX-03)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.catalog.addProduct.mockResolvedValue({});
    api.catalog.updateProduct.mockResolvedValue({});
  });

  it('shows an inline error on the price field for an empty price and does not call the API', async () => {
    renderModal();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    expect(await screen.findByText('กรุณาใส่ราคา')).not.toBeNull();
    expect(api.catalog.addProduct).not.toHaveBeenCalled();
    expect(api.catalog.updateProduct).not.toHaveBeenCalled();
  });

  it('submits the exact create payload shape, normalising untouched optional fields to null', async () => {
    renderModal({ factoryId: 7 });

    fireEvent.change(screen.getByLabelText(/ราคา/), { target: { value: '350' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.catalog.addProduct).toHaveBeenCalledTimes(1));
    expect(api.catalog.addProduct).toHaveBeenCalledWith({
      productCode: null,
      grade: null,
      collection: null,
      productName: null,
      color: null,
      surface: null,
      sizeRaw: null,
      price: 350,
      currency: 'EUR',
      priceUnit: 'per_sqm',
      factoryId: 7,
    });
    expect(api.catalog.updateProduct).not.toHaveBeenCalled();
  });
});
