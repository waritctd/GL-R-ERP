import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProductFormModal } from './ProductFormModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// UX-03 slice 1/5: ProductFormModal migrated from hand-rolled useState +
// single bottom-of-form error string to react-hook-form + zodResolver +
// FormField, proving the pattern on the smallest sales-stack surface first.
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

  it('wires aria-invalid and aria-describedby on the price input to the real error element', async () => {
    renderModal();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));
    await screen.findByText('กรุณาใส่ราคา');

    const priceInput = screen.getByLabelText(/ราคา/);
    expect(priceInput.getAttribute('aria-invalid')).toBe('true');

    const describedBy = priceInput.getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    const describedIds = describedBy.split(' ');
    describedIds.forEach((id) => {
      expect(document.getElementById(id)).not.toBeNull();
    });
    // The error paragraph itself must be one of the referenced ids and carry
    // the actual error text (proving real FormField <-> input association,
    // not just a coincidentally-present aria attribute).
    const errorEl = document.getElementById('pf-price-error');
    expect(errorEl).not.toBeNull();
    expect(errorEl.textContent).toBe('กรุณาใส่ราคา');
    expect(describedIds).toContain('pf-price-error');
  });

  it('also rejects a zero/negative price with the same inline error', async () => {
    renderModal();

    fireEvent.change(screen.getByLabelText(/ราคา/), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    expect(await screen.findByText('กรุณาใส่ราคา')).not.toBeNull();
    expect(api.catalog.addProduct).not.toHaveBeenCalled();
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

  it('submits every filled-in field verbatim (no null normalisation for non-empty values)', async () => {
    renderModal({ factoryId: 3 });

    fireEvent.change(screen.getByLabelText('รหัสสินค้า'), { target: { value: 'STN-01' } });
    fireEvent.change(screen.getByLabelText('Grade'), { target: { value: 'A' } });
    fireEvent.change(screen.getByLabelText('Collection'), { target: { value: 'Stone Series' } });
    fireEvent.change(screen.getByLabelText('ชื่อสินค้า'), { target: { value: 'Grey Stone' } });
    fireEvent.change(screen.getByLabelText('สี'), { target: { value: 'เทา' } });
    fireEvent.change(screen.getByLabelText('ผิว'), { target: { value: 'ด้าน' } });
    fireEvent.change(screen.getByLabelText('ขนาด'), { target: { value: '60x60' } });
    fireEvent.change(screen.getByLabelText(/ราคา/), { target: { value: '350.5' } });
    fireEvent.change(screen.getByLabelText('สกุลเงิน'), { target: { value: 'THB' } });
    fireEvent.change(screen.getByLabelText('หน่วย'), { target: { value: 'per_piece' } });

    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.catalog.addProduct).toHaveBeenCalledTimes(1));
    expect(api.catalog.addProduct).toHaveBeenCalledWith({
      productCode: 'STN-01',
      grade: 'A',
      collection: 'Stone Series',
      productName: 'Grey Stone',
      color: 'เทา',
      surface: 'ด้าน',
      sizeRaw: '60x60',
      price: 350.5,
      currency: 'THB',
      priceUnit: 'per_piece',
      factoryId: 3,
    });
  });

  it('edit mode calls updateProduct with the priceId and does not send factoryId', async () => {
    const product = {
      priceId: 501,
      productCode: 'STN-01',
      grade: 'A',
      collection: 'Stone Series',
      productName: 'Grey Stone',
      color: 'เทา',
      surface: 'ด้าน',
      sizeRaw: '60x60',
      price: 350,
      currency: 'THB',
      priceUnit: 'per_sqm',
    };
    renderModal({ product, factoryId: 7 });

    expect(screen.getByLabelText(/ราคา/).value).toBe('350');

    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.catalog.updateProduct).toHaveBeenCalledTimes(1));
    expect(api.catalog.updateProduct).toHaveBeenCalledWith(501, {
      productCode: 'STN-01',
      grade: 'A',
      collection: 'Stone Series',
      productName: 'Grey Stone',
      color: 'เทา',
      surface: 'ด้าน',
      sizeRaw: '60x60',
      price: 350,
      currency: 'THB',
      priceUnit: 'per_sqm',
    });
    expect(api.catalog.addProduct).not.toHaveBeenCalled();
  });

  it('shows a form-level error (not a field error) when the API call fails, and re-enables the button', async () => {
    api.catalog.addProduct.mockRejectedValue(new Error('เซิร์ฟเวอร์ขัดข้อง'));
    const onSaved = vi.fn();
    renderModal({ onSaved });

    fireEvent.change(screen.getByLabelText(/ราคา/), { target: { value: '100' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    expect(await screen.findByText('เซิร์ฟเวอร์ขัดข้อง')).not.toBeNull();
    expect(onSaved).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'บันทึก' }).disabled).toBe(false);
  });
});
