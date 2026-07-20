import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';

globalThis.React = React;

// The component fetches Pricing Request attachments (V69) whenever a persisted id is available
// (createdId after save, or initialSummary.id in edit mode) and searches the catalog picker on
// typing — neither is under test here, so both are stubbed to resolve emptily rather than
// hitting the network and polluting assertions that expect exactly one alert.
vi.mock('../../api/index.js', () => ({
  api: {
    catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) },
    pricingRequests: {
      listAttachments: vi.fn().mockResolvedValue({ items: [] }),
      uploadAttachment: vi.fn().mockResolvedValue({ attachment: null }),
      deleteAttachment: vi.fn().mockResolvedValue({ ok: true }),
    },
  },
}));

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

  // Mirrors PricingRequestService.validateItems: a line with no
  // sourceTicketItemId/productId/model/productDescription
  // does not identify a product. Unlike the two tests above, this is reported
  // per-row (attached to the specific item), not as a single form banner.
  it('blocks an item with no identity field and shows the error on that specific row', async () => {
    const { createFn } = renderModal({ ticketItems: [] }); // no deal items to seed from -> one blank row
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));

    const rowError = await screen.findByRole('alert');
    expect(rowError.textContent).toContain('ต้องระบุสินค้าที่ต้องการเสนอราคา');
    expect(createFn).not.toHaveBeenCalled();
  });

  it('keeps specialRequirement separate from product identity', async () => {
    const { createFn } = renderModal({ ticketItems: [] });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.change(screen.getByLabelText('ข้อกำหนดพิเศษ'), { target: { value: 'ส่งด่วน' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));

    const rowError = await screen.findByRole('alert');
    expect(rowError.textContent).toContain('ต้องระบุสินค้าที่ต้องการเสนอราคา');
    expect(createFn).not.toHaveBeenCalled();
  });

  it('clears the row error once productDescription is filled in, and allows submission', async () => {
    const { createFn } = renderModal({ ticketItems: [] });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));
    await screen.findByRole('alert');

    fireEvent.change(screen.getByLabelText('รายละเอียดสินค้า'), { target: { value: 'กระเบื้องพอร์ซเลน 60x60 สีขาว' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(createFn).toHaveBeenCalledWith(expect.objectContaining({
      clientRequestId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
      items: [expect.objectContaining({ productDescription: 'กระเบื้องพอร์ซเลน 60x60 สีขาว' })],
    }));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('reuses the same clientRequestId when a lost create response is retried', async () => {
    const createFn = vi.fn().mockRejectedValue(new Error('lost response'));
    renderModal({ createFn });

    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(2));
    expect(createFn.mock.calls[1][0].clientRequestId).toBe(createFn.mock.calls[0][0].clientRequestId);
  });

  // Fix 1 (review-remediation plan): "Create and submit" used to call createFn
  // unconditionally, so a create-succeeds-then-submit-fails retry produced a
  // second orphaned DRAFT. The retry must reuse the id createFn already
  // returned and push the current form state onto it via updateFn instead.
  it('does not create a second draft when submitFn fails after create succeeds — retry reuses the same id', async () => {
    const createFn = vi.fn().mockResolvedValue({ pricingRequest: { summary: { id: 42 } } });
    const submitFn = vi.fn()
      .mockRejectedValueOnce(new Error('network error'))
      .mockResolvedValueOnce({});
    const updateFn = vi.fn().mockResolvedValue({});
    renderModal({ createFn, submitFn, updateFn });

    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    await screen.findByRole('alert'); // submitFn's rejection surfaces as the error banner

    // Retry: must NOT call createFn again (that would orphan a 2nd DRAFT).
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));

    await waitFor(() => expect(submitFn).toHaveBeenCalledTimes(2));
    expect(createFn).toHaveBeenCalledTimes(1);
    expect(submitFn).toHaveBeenNthCalledWith(2, 42);
    expect(updateFn).toHaveBeenCalledWith(42, expect.any(Object));
  });

  it('shows an informational message on retry so it does not look like a second draft might be created', async () => {
    const createFn = vi.fn().mockResolvedValue({ pricingRequest: { summary: { id: 42 } } });
    const submitFn = vi.fn().mockRejectedValueOnce(new Error('network error')).mockResolvedValueOnce({});
    const updateFn = vi.fn().mockResolvedValue({});
    renderModal({ createFn, submitFn, updateFn });

    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));
    await screen.findByRole('alert');

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ Import/ }));

    expect(await screen.findByRole('status')).not.toBeNull();
  });
});

describe('PricingRequestCreateModal edit mode (Fix 2)', () => {
  function editInitialValue(overrides = {}) {
    return {
      summary: {
        id: 77,
        recipientType: 'OWNER',
        recipientLabel: 'เจ้าของโครงการ ข.',
        requiredDate: '2026-08-01',
        customerTargetPrice: 500,
        targetCurrency: 'USD',
        note: 'โน้ตเดิม',
      },
      items: [{
        id: 5, sourceTicketItemId: null, productId: null, brand: 'SCG', model: 'A1', color: 'ขาว',
        productDescription: 'กระเบื้องพื้น SCG A1', texture: 'ด้าน', size: '60x60', factory: 'SCG Ceramics',
        requestedQty: 20, requestedUnit: 'แผ่น',
        quantityType: 'CONFIRMED', targetDeliveryDate: null, deliveryLocation: null, specialRequirement: null,
      }],
      ...overrides,
    };
  }

  it('seeds every field from initialValue and calls updateFn with the full payload on save', async () => {
    const updateFn = vi.fn().mockResolvedValue({});
    const onCreated = vi.fn();
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={editInitialValue()}
        onClose={vi.fn()}
        onCreated={onCreated}
        updateFn={updateFn}
      />,
    );

    expect(screen.getByDisplayValue('เจ้าของโครงการ ข.')).not.toBeNull();
    expect(screen.getByDisplayValue('20')).not.toBeNull();
    expect(screen.getByDisplayValue('โน้ตเดิม')).not.toBeNull();
    expect(screen.getByDisplayValue('กระเบื้องพื้น SCG A1')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

    await waitFor(() => expect(updateFn).toHaveBeenCalledWith(77, expect.objectContaining({
      recipientType: 'OWNER',
      recipientLabel: 'เจ้าของโครงการ ข.',
      note: 'โน้ตเดิม',
      items: [expect.objectContaining({ productDescription: 'กระเบื้องพื้น SCG A1' })],
    })));
    expect(onCreated).toHaveBeenCalledTimes(1);
  });

  it('has no "ส่งให้ Import"/"บันทึกร่าง" buttons — editing a draft never submits or re-creates it', () => {
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={editInitialValue()}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: /ส่งให้ Import/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกร่าง' })).toBeNull();
  });
});

// Review remediation (COMMIT 5, P1 finding 3): the customer-change revision UI used to copy the
// current request verbatim (PricingRequestDetailPage's now-deleted revisionPayload()) and only
// collect a revision reason, so a customer changing product/quantity/recipient/date could never
// express it — the new DRAFT was always commercially identical to its parent. Revision mode
// reuses this same modal (seeding, catalog picker, unit select, attachment uploader) instead.
describe('PricingRequestCreateModal revision mode (COMMIT 5, P1 finding 3)', () => {
  function revisionInitialValue(overrides = {}) {
    return {
      summary: {
        id: 88,
        recipientType: 'OWNER',
        recipientLabel: 'เจ้าของโครงการ ข.',
        requiredDate: '2026-08-01',
        customerTargetPrice: 500,
        targetCurrency: 'USD',
        note: 'โน้ตเดิม',
      },
      items: [{
        id: 9, sourceTicketItemId: null, productId: null, brand: 'SCG', model: 'A1', color: 'ขาว',
        productDescription: 'กระเบื้องพื้น SCG A1', texture: 'ด้าน', size: '60x60', factory: 'SCG Ceramics',
        requestedQty: 20, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE',
        quantityType: 'CONFIRMED', targetDeliveryDate: null, deliveryLocation: null, specialRequirement: null,
      }],
      ...overrides,
    };
  }

  function renderRevisionModal(overrides = {}) {
    const createRevisionFn = vi.fn().mockResolvedValue({ pricingRequest: { summary: { id: 999 } } });
    const createFn = vi.fn();
    const submitFn = vi.fn();
    const updateFn = vi.fn();
    const onClose = vi.fn();
    const onCreated = vi.fn();
    render(
      <PricingRequestCreateModal
        mode="revision"
        initialValue={revisionInitialValue()}
        onClose={onClose}
        onCreated={onCreated}
        createRevisionFn={createRevisionFn}
        createFn={createFn}
        submitFn={submitFn}
        updateFn={updateFn}
        {...overrides}
      />,
    );
    return { createRevisionFn, createFn, submitFn, updateFn, onClose, onCreated };
  }

  it('seeds every field from the CURRENT request (initialValue), same as edit mode', () => {
    renderRevisionModal();
    expect(screen.getByDisplayValue('เจ้าของโครงการ ข.')).not.toBeNull();
    expect(screen.getByDisplayValue('20')).not.toBeNull();
    expect(screen.getByDisplayValue('โน้ตเดิม')).not.toBeNull();
    expect(screen.getByDisplayValue('กระเบื้องพื้น SCG A1')).not.toBeNull();
  });

  it('requires a revision reason — the create button stays disabled until one is entered', async () => {
    const { createRevisionFn } = renderRevisionModal();
    const submit = screen.getByRole('button', { name: /สร้าง revision/ });
    expect(submit.disabled).toBe(true);

    fireEvent.click(submit); // disabled: must be a no-op, not a silent success
    expect(createRevisionFn).not.toHaveBeenCalled();

    fireEvent.change(screen.getByPlaceholderText('เช่น ลูกค้าเปลี่ยนสินค้า/จำนวน/ขนาด'), {
      target: { value: 'ลูกค้าเปลี่ยนจำนวนและผู้รับ' },
    });
    expect(submit.disabled).toBe(false);
  });

  it('edits to quantity, recipient, and product description actually reach the payload sent to createRevisionFn', async () => {
    const { createRevisionFn } = renderRevisionModal();

    fireEvent.change(screen.getByPlaceholderText('เช่น ลูกค้าเปลี่ยนสินค้า/จำนวน/ขนาด'), {
      target: { value: 'ลูกค้าเปลี่ยนจำนวนและผู้รับ' },
    });
    fireEvent.change(screen.getByDisplayValue('เจ้าของโครงการ ข.'), { target: { value: 'เจ้าของโครงการ ค. (เปลี่ยนใหม่)' } });
    fireEvent.change(screen.getByDisplayValue('20'), { target: { value: '35' } });
    fireEvent.change(screen.getByDisplayValue('กระเบื้องพื้น SCG A1'), { target: { value: 'กระเบื้องพื้น SCG A1 รุ่นใหม่' } });

    fireEvent.click(screen.getByRole('button', { name: /สร้าง revision/ }));

    await waitFor(() => expect(createRevisionFn).toHaveBeenCalledTimes(1));
    expect(createRevisionFn).toHaveBeenCalledWith(88, expect.objectContaining({
      revisionReason: 'ลูกค้าเปลี่ยนจำนวนและผู้รับ',
      recipientLabel: 'เจ้าของโครงการ ค. (เปลี่ยนใหม่)',
      items: [expect.objectContaining({
        requestedQty: 35,
        productDescription: 'กระเบื้องพื้น SCG A1 รุ่นใหม่',
      })],
    }));
  });

  it('never calls updateFn/createFn/submitFn against the parent request — the prior request stays untouched', async () => {
    const { createRevisionFn, createFn, submitFn, updateFn, onCreated } = renderRevisionModal();

    fireEvent.change(screen.getByPlaceholderText('เช่น ลูกค้าเปลี่ยนสินค้า/จำนวน/ขนาด'), {
      target: { value: 'ลูกค้าเปลี่ยนใจ' },
    });
    fireEvent.click(screen.getByRole('button', { name: /สร้าง revision/ }));

    await waitFor(() => expect(createRevisionFn).toHaveBeenCalledTimes(1));
    expect(createRevisionFn).toHaveBeenCalledWith(88, expect.any(Object));
    expect(updateFn).not.toHaveBeenCalled();
    expect(createFn).not.toHaveBeenCalled();
    expect(submitFn).not.toHaveBeenCalled();
    expect(onCreated).toHaveBeenCalledWith({ pricingRequest: { summary: { id: 999 } } });
  });

  it('has no "ส่งให้ Import"/"บันทึกร่าง"/"บันทึกการแก้ไข" buttons — only "สร้าง revision"', () => {
    renderRevisionModal();
    expect(screen.queryByRole('button', { name: /ส่งให้ Import/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกร่าง' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกการแก้ไข' })).toBeNull();
    expect(screen.getByRole('button', { name: /สร้าง revision/ })).not.toBeNull();
  });
});
