import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';

globalThis.React = React;

function ticketItem(overrides = {}) {
  return {
    id: 501,
    brand: 'SCG', model: 'A1', color: 'ขาว', texture: 'ด้าน', size: '60x60',
    factory: 'SCG Ceramics',
    unitBasis: 'PIECE',
    qty: 400,
    qtySqm: null,
    ...overrides,
  };
}

function renderModal(overrides = {}) {
  const createFn = vi.fn().mockResolvedValue({ pricingRequest: { summary: { id: 1 } } });
  const submitFn = vi.fn().mockResolvedValue({});
  const onClose = vi.fn();
  const onCreated = vi.fn();
  render(
    <PricingRequestCreateModal
      ticketItems={[ticketItem()]}
      onClose={onClose}
      onCreated={onCreated}
      createFn={createFn}
      submitFn={submitFn}
      {...overrides}
    />,
  );
  return { createFn, submitFn, onClose, onCreated };
}

describe('PricingRequestCreateModal', () => {
  it('seeds the item row unit from the deal item\'s unitBasis (PIECE -> แผ่น) and its qty', () => {
    renderModal();
    // "หน่วย *" input — pre-filled, not left blank, unlike the pre-fix behaviour.
    expect(screen.getByDisplayValue('แผ่น')).not.toBeNull();
    expect(screen.getByDisplayValue('400')).not.toBeNull();
  });

  it('seeds ตร.ม. and the sqm quantity for an SQM-basis deal item', () => {
    renderModal({ ticketItems: [ticketItem({ unitBasis: 'SQM', qty: 400, qtySqm: 144 })] });
    expect(screen.getByDisplayValue('ตร.ม.')).not.toBeNull();
    expect(screen.getByDisplayValue('144')).not.toBeNull();
  });

  it('blocks submission client-side when a unit is cleared, instead of sending a blank unit', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    const unitInput = screen.getByDisplayValue('แผ่น');
    fireEvent.change(unitInput, { target: { value: '' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));

    expect((await screen.findByRole('alert')).textContent).toContain('กรุณากรอกหน่วยของทุกรายการ');
    expect(createFn).not.toHaveBeenCalled();
  });

  it('blocks submission client-side when quantity is zero', async () => {
    const { createFn } = renderModal();
    const qtyInput = screen.getByDisplayValue('400');
    fireEvent.change(qtyInput, { target: { value: '0' } });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));

    expect((await screen.findByRole('alert')).textContent).toContain('กรุณากรอกจำนวนของทุกรายการให้ถูกต้อง');
    expect(createFn).not.toHaveBeenCalled();
  });
});
