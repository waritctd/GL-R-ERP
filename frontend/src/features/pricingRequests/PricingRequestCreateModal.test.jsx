import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';
import { api } from '../../api/index.js';

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

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    expect((await screen.findByRole('alert')).textContent).toContain('กรุณากรอกหน่วยของทุกรายการ');
    expect(createFn).not.toHaveBeenCalled();
  });

  it('blocks submission client-side when quantity is zero', async () => {
    const { createFn } = renderModal();
    const qtyInput = screen.getByDisplayValue('400');
    fireEvent.change(qtyInput, { target: { value: '0' } });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

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

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    const rowError = await screen.findByRole('alert');
    expect(rowError.textContent).toContain('ต้องระบุสินค้าที่ต้องการเสนอราคา');
    expect(createFn).not.toHaveBeenCalled();
  });

  it('keeps specialRequirement separate from product identity', async () => {
    const { createFn } = renderModal({ ticketItems: [] });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.change(screen.getByLabelText('ข้อกำหนดพิเศษ'), { target: { value: 'ส่งด่วน' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    const rowError = await screen.findByRole('alert');
    expect(rowError.textContent).toContain('ต้องระบุสินค้าที่ต้องการเสนอราคา');
    expect(createFn).not.toHaveBeenCalled();
  });

  it('clears the row error once productDescription is filled in, and allows submission', async () => {
    const { createFn } = renderModal({ ticketItems: [] });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    await screen.findByRole('alert');

    fireEvent.change(screen.getByLabelText('รายละเอียดสินค้า'), { target: { value: 'กระเบื้องพอร์ซเลน 60x60 สีขาว' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

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
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    await screen.findByRole('alert'); // submitFn's rejection surfaces as the error banner

    // Retry: must NOT call createFn again (that would orphan a 2nd DRAFT).
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

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
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    await screen.findByRole('alert');

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

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

  it('has no "ส่งให้ฝ่ายนำเข้า"/"บันทึกร่าง" buttons — editing a draft never submits or re-creates it', () => {
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={editInitialValue()}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ })).toBeNull();
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
    const submit = screen.getByRole('button', { name: /สร้างรอบแก้ไข/ });
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

    fireEvent.click(screen.getByRole('button', { name: /สร้างรอบแก้ไข/ }));

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
    fireEvent.click(screen.getByRole('button', { name: /สร้างรอบแก้ไข/ }));

    await waitFor(() => expect(createRevisionFn).toHaveBeenCalledTimes(1));
    expect(createRevisionFn).toHaveBeenCalledWith(88, expect.any(Object));
    expect(updateFn).not.toHaveBeenCalled();
    expect(createFn).not.toHaveBeenCalled();
    expect(submitFn).not.toHaveBeenCalled();
    expect(onCreated).toHaveBeenCalledWith({ pricingRequest: { summary: { id: 999 } } });
  });

  it('has no "ส่งให้ฝ่ายนำเข้า"/"บันทึกร่าง"/"บันทึกการแก้ไข" buttons — only "สร้างรอบแก้ไข"', () => {
    renderRevisionModal();
    expect(screen.queryByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกร่าง' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกการแก้ไข' })).toBeNull();
    expect(screen.getByRole('button', { name: /สร้างรอบแก้ไข/ })).not.toBeNull();
  });
});

// V110 fix ("ทุกอย่างควร autofill ตามข้อมูลขั้นตอนนั้น" / "สร้างคำขอราคาไม่ควรต้องกรอกหาจาก
// catalog ซ้ำ"): this modal now seeds its catalog link, ผู้รับ label, note, and each row's
// delivery location from the deal it was opened from — CREATE MODE ONLY. Placed at the end of
// this file (not interleaved with the suites above) since these tests drive the real (mocked)
// api.catalog.prices call the fuzzy-fallback effect fires on every create-mode render, and there
// is no global beforeEach/afterEach resetting that shared vi.fn() between tests in this file —
// appending here means any queued mockResolvedValueOnce below can only ever affect tests that
// run after it, never the (already-passed) suites above.
describe('PricingRequestCreateModal deal-derived autofill (V110)', () => {
  function dealFixture(overrides = {}) {
    return {
      designerName: 'สมชาย ผู้ออกแบบ',
      ownerName: 'บริษัท เจ้าของ จำกัด',
      buyerName: 'ผู้ซื้อ ข.',
      contactName: 'ผู้ติดต่อ ค.',
      customerName: 'ลูกค้า ง.',
      note: 'โน้ตจากขั้นตอนสร้างดีล',
      projectName: 'โครงการทดสอบ A',
      ...overrides,
    };
  }

  it('prefills the catalog link and code from the ticket item — no re-search needed', () => {
    renderModal({ ticketItems: [ticketItem({ catalogPriceId: 777, catalogProductCode: 'PC-777' })] });

    expect(screen.getByText('Catalog #777')).not.toBeNull();
    expect(screen.getByPlaceholderText('รหัสสินค้า / Collection / โรงงาน').value).toBe('PC-777');
  });

  it("autofills ผู้รับ's label per recipient type, from the deal", () => {
    renderModal({ deal: dealFixture() });
    // Default recipientType is DESIGNER.
    expect(screen.getByDisplayValue('สมชาย ผู้ออกแบบ')).not.toBeNull();
  });

  it("re-fills ผู้รับ's label when the chip switches, but never after the user has typed over it", () => {
    renderModal({ deal: dealFixture() });

    fireEvent.click(screen.getByRole('radio', { name: /เจ้าของโครงการ/ }));
    expect(screen.getByDisplayValue('บริษัท เจ้าของ จำกัด')).not.toBeNull();

    const recipientInput = screen.getByDisplayValue('บริษัท เจ้าของ จำกัด');
    fireEvent.change(recipientInput, { target: { value: 'ชื่อที่พิมพ์เอง' } });

    // Switching the chip again must NOT clobber what the user typed.
    fireEvent.click(screen.getByRole('radio', { name: /ผู้ซื้อ/ }));
    expect(screen.getByDisplayValue('ชื่อที่พิมพ์เอง')).not.toBeNull();
    expect(screen.queryByDisplayValue('ผู้ซื้อ ข.')).toBeNull();
  });

  it('falls back to contactName then customerName when the deal has no per-type recipient name', () => {
    renderModal({ deal: dealFixture({ designerName: null, ownerName: null, buyerName: null }) });
    expect(screen.getByDisplayValue('ผู้ติดต่อ ค.')).not.toBeNull();
  });

  it("seeds note from deal.note and each row's deliveryLocation from deal.projectName, including a row added afterward", () => {
    renderModal({ deal: dealFixture() });

    expect(screen.getByLabelText(/หมายเหตุถึงฝ่ายนำเข้า/).value).toBe('โน้ตจากขั้นตอนสร้างดีล');
    const deliveryInputs = screen.getAllByLabelText('สถานที่ส่งมอบ');
    expect(deliveryInputs).toHaveLength(1);
    expect(deliveryInputs[0].value).toBe('โครงการทดสอบ A');

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    const afterAdd = screen.getAllByLabelText('สถานที่ส่งมอบ');
    expect(afterAdd).toHaveLength(2);
    expect(afterAdd[1].value).toBe('โครงการทดสอบ A');
  });

  it('does NOT autofill requiredDate from the deal (not requested)', () => {
    renderModal({ deal: dealFixture() });
    expect(screen.getByLabelText('วันที่ต้องการราคา').value).toBe('');
  });

  it('edit mode ignores a passed `deal` entirely — every field still seeds from the persisted request', () => {
    const updateFn = vi.fn().mockResolvedValue({});
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={{
          summary: {
            id: 77, recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการ ข.',
            requiredDate: null, customerTargetPrice: null, targetCurrency: 'THB',
            note: 'โน้ตเดิม',
          },
          items: [{
            id: 5, sourceTicketItemId: null, productId: null, brand: 'SCG', model: 'A1',
            color: 'ขาว', productDescription: '', texture: 'ด้าน', size: '60x60', factory: null,
            requestedQty: 20, requestedUnit: 'แผ่น', quantityType: 'CONFIRMED',
            targetDeliveryDate: null, deliveryLocation: 'ที่ส่งมอบเดิม', specialRequirement: null,
          }],
        }}
        deal={dealFixture()}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={updateFn}
      />,
    );

    expect(screen.getByDisplayValue('เจ้าของโครงการ ข.')).not.toBeNull();
    expect(screen.getByDisplayValue('โน้ตเดิม')).not.toBeNull();
    expect(screen.getByDisplayValue('ที่ส่งมอบเดิม')).not.toBeNull();
    // None of the deal's own values leaked in anywhere.
    expect(screen.queryByDisplayValue('บริษัท เจ้าของ จำกัด')).toBeNull();
    expect(screen.queryByDisplayValue('โน้ตจากขั้นตอนสร้างดีล')).toBeNull();
    expect(screen.queryByDisplayValue('โครงการทดสอบ A')).toBeNull();
  });

  it('revision mode ignores a passed `deal` entirely — every field still seeds from the current request', () => {
    const createRevisionFn = vi.fn().mockResolvedValue({ pricingRequest: { summary: { id: 999 } } });
    render(
      <PricingRequestCreateModal
        mode="revision"
        initialValue={{
          summary: {
            id: 88, recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการ ข.',
            requiredDate: null, customerTargetPrice: null, targetCurrency: 'THB',
            note: 'โน้ตเดิม',
          },
          items: [{
            id: 9, sourceTicketItemId: null, productId: null, brand: 'SCG', model: 'A1',
            color: 'ขาว', productDescription: '', texture: 'ด้าน', size: '60x60', factory: null,
            requestedQty: 20, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE',
            quantityType: 'CONFIRMED', targetDeliveryDate: null, deliveryLocation: 'ที่ส่งมอบเดิม',
            specialRequirement: null,
          }],
        }}
        deal={dealFixture()}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        createRevisionFn={createRevisionFn}
        createFn={vi.fn()}
        submitFn={vi.fn()}
        updateFn={vi.fn()}
      />,
    );

    expect(screen.getByDisplayValue('เจ้าของโครงการ ข.')).not.toBeNull();
    expect(screen.getByDisplayValue('โน้ตเดิม')).not.toBeNull();
    expect(screen.getByDisplayValue('ที่ส่งมอบเดิม')).not.toBeNull();
    expect(screen.queryByDisplayValue('บริษัท เจ้าของ จำกัด')).toBeNull();
  });
});

describe('PricingRequestCreateModal fuzzy catalog fallback (V110 follow-up)', () => {
  it('auto-applies when the search returns exactly one normalized match, and badges the row as unconfirmed', async () => {
    api.catalog.prices.mockResolvedValueOnce({
      items: [{
        priceId: 901, productCode: 'PC-901', factoryName: 'SCG Ceramics',
        grade: 'SCG', collection: 'A1', sizeRaw: '60x60', color: 'ขาว', surface: 'ด้าน',
        price: 120, currency: 'THB', priceUnit: 'per_piece',
      }],
    });
    // Legacy line: no catalogPriceId (pre-V110 deal), so productId seeds null and the fallback
    // effect gets a chance to run — model/size deliberately match the mocked candidate above.
    renderModal({ ticketItems: [ticketItem({ model: 'A1', size: '60x60' })] });

    await screen.findByText(/จับคู่สินค้าจาก Catalog/);
    expect(screen.getByText(/Catalog #901/)).not.toBeNull();
  });

  it('does NOT auto-apply when the search returns more than one normalized match — leaves the row blank for manual search', async () => {
    api.catalog.prices.mockResolvedValueOnce({
      items: [
        { priceId: 901, productCode: 'PC-901', collection: 'A1', sizeRaw: '60x60' },
        { priceId: 902, productCode: 'PC-902', collection: 'A1', sizeRaw: '60x60' },
      ],
    });
    renderModal({ ticketItems: [ticketItem({ model: 'A1', size: '60x60' })] });

    await waitFor(() => expect(api.catalog.prices).toHaveBeenCalled());
    // Give the (already-resolved) ambiguous response a tick to flow through the effect and
    // confirm it deliberately does nothing, rather than asserting absence before it had a
    // chance to (wrongly) apply.
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(screen.queryByText(/จับคู่สินค้าจาก Catalog/)).toBeNull();
    expect(screen.queryByText('Catalog #901')).toBeNull();
    expect(screen.queryByText('Catalog #902')).toBeNull();
  });

  it('does NOT auto-apply when the row already has a productId (never overwrites a real link)', () => {
    api.catalog.prices.mockClear();
    renderModal({ ticketItems: [ticketItem({ catalogPriceId: 777, catalogProductCode: 'PC-777', model: 'A1', size: '60x60' })] });

    // The row already has a link — the fallback must skip it entirely, never even searching.
    expect(api.catalog.prices).not.toHaveBeenCalled();
  });

  // Review finding F1. GET /catalog/prices is `ORDER BY f.name, ... LIMIT :limit` — alphabetical
  // by FACTORY, not relevance-ranked. So a candidate agreeing on model+size can still be the
  // wrong product, and deriveItemFromCatalogProduct would then overwrite the row's factory/
  // color/texture from it. The predicate must therefore use every descriptive field the row has.
  it('does NOT auto-apply a model+size match that disagrees with the row on factory', async () => {
    api.catalog.prices.mockClear();
    api.catalog.prices.mockResolvedValueOnce({
      items: [{
        priceId: 903, productCode: 'PC-903', factoryName: 'Aaa Ceramics',
        grade: 'Aaa', collection: 'A1', sizeRaw: '60x60', color: 'ขาว', surface: 'ด้าน',
        price: 99, currency: 'THB', priceUnit: 'per_piece',
      }],
    });
    // The deal line names a DIFFERENT factory. Matching on model+size alone would have applied
    // this candidate and silently rewritten the row's factory to "Aaa Ceramics".
    renderModal({
      ticketItems: [ticketItem({ model: 'A1', size: '60x60', factory: 'Zzz Ceramica' })],
    });

    await waitFor(() => expect(api.catalog.prices).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(screen.queryByText(/จับคู่สินค้าจาก Catalog/)).toBeNull();
    expect(screen.queryByText(/Catalog #903/)).toBeNull();
    expect(screen.getByDisplayValue('Zzz Ceramica')).not.toBeNull();
  });

  // Review finding F1, second half: a FULL page back from the server means the result set was
  // truncated, so anything past the cut is invisible. "Exactly one match" is then an artifact of
  // truncation, not evidence of uniqueness — ambiguity is UNKNOWN, so decline to guess.
  it('declines to auto-apply when the result set is a full page (truncation hides ambiguity)', async () => {
    api.catalog.prices.mockClear();
    // 50 rows = FUZZY_MATCH_LIMIT. Exactly one agrees with the row, but the page is full — so
    // the ONLY thing that can reject it is the full-page guard. The row blanks factory/color/
    // texture so the field predicate has no opinion and cannot mask what is under test.
    const filler = Array.from({ length: 49 }, (_, n) => ({
      priceId: 1000 + n, productCode: `F-${n}`, factoryName: `Factory ${n}`,
      collection: 'A1', sizeRaw: 'other-size', color: '', surface: '',
    }));
    api.catalog.prices.mockResolvedValueOnce({
      items: [...filler, {
        priceId: 904, productCode: 'PC-904', factoryName: 'SCG Ceramics',
        grade: 'SCG', collection: 'A1', sizeRaw: '60x60', color: '', surface: '',
        price: 120, currency: 'THB',
      }],
    });
    renderModal({
      ticketItems: [ticketItem({ model: 'A1', size: '60x60', factory: '', color: '', texture: '' })],
    });

    await waitFor(() => expect(api.catalog.prices).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(screen.queryByText(/จับคู่สินค้าจาก Catalog/)).toBeNull();
    expect(screen.queryByText(/Catalog #904/)).toBeNull();
  });

  // Review finding F2. The match is computed from the row as it looked when the search STARTED.
  // If the user retypes the row while it is in flight, applying the old match would destroy what
  // they just typed — and productId is already null on exactly these rows, so the productId
  // guard cannot catch it.
  it('drops the match when the user has retyped the row while the search was in flight', async () => {
    api.catalog.prices.mockClear();
    let release;
    const held = new Promise((resolve) => { release = resolve; });
    api.catalog.prices.mockImplementationOnce(() => held);

    // Row blanks factory/color/texture so the field predicate has no opinion — the returned
    // candidate is a genuine match for the row AS SEARCHED, leaving the staleness re-check as
    // the only thing that can reject it.
    renderModal({
      ticketItems: [ticketItem({ model: 'A1', size: '60x60', factory: '', color: '', texture: '' })],
    });
    await waitFor(() => expect(api.catalog.prices).toHaveBeenCalled());

    // User corrects the model by hand while the search for "A1" is still open.
    const modelInputs = screen.getAllByDisplayValue('A1');
    fireEvent.change(modelInputs[modelInputs.length - 1], { target: { value: 'B9' } });

    release({
      items: [{
        priceId: 905, productCode: 'PC-905', factoryName: 'SCG Ceramics',
        grade: 'SCG', collection: 'A1', sizeRaw: '60x60', color: '', surface: '',
        price: 120, currency: 'THB',
      }],
    });
    await new Promise((resolve) => setTimeout(resolve, 20));

    // What the user typed survives; the stale match is discarded.
    expect(screen.getByDisplayValue('B9')).not.toBeNull();
    expect(screen.queryByText(/Catalog #905/)).toBeNull();
    expect(screen.queryByText(/จับคู่สินค้าจาก Catalog/)).toBeNull();
  });

  // Regression guard: these searches run SEQUENTIALLY, so the in-flight window across a
  // many-line deal is seconds long. Deleting a row during it re-indexes every row after the
  // deleted one. An apply keyed on array index would land row 2's match on row 3 — silently
  // overwriting a DIFFERENT product's fields and badging the wrong row as auto-matched, which
  // is exactly the wrong-product-into-costing outcome the badge exists to prevent. The apply is
  // therefore keyed on sourceTicketItemId.
  it('applies a match to the row it searched for, even when an earlier row is deleted mid-flight', async () => {
    api.catalog.prices.mockClear();
    // Row 1 (id 601, model B2) resolves only after we delete row 0, so by apply time every
    // surviving row has shifted down one index.
    let releaseSecondSearch;
    const secondSearch = new Promise((resolve) => { releaseSecondSearch = resolve; });
    api.catalog.prices
      .mockResolvedValueOnce({ items: [] })          // row 0 (id 600) — no match, moves on
      .mockImplementationOnce(() => secondSearch);   // row 1 (id 601) — held open

    renderModal({
      ticketItems: [
        ticketItem({ id: 600, model: 'A1', size: '60x60' }),
        ticketItem({ id: 601, model: 'B2', size: '30x30' }),
        ticketItem({ id: 602, model: 'C3', size: '80x80' }),
      ],
    });

    await waitFor(() => expect(api.catalog.prices).toHaveBeenCalledTimes(2));

    // Delete row 0 while row 1's search is still open — row 601 is now at index 0, 602 at 1.
    fireEvent.click(screen.getByRole('button', { name: 'ลบรายการที่ 1' }));

    // The candidate must agree with the row on every descriptive field the row has (F1's
    // predicate), so colour/surface/factory mirror ticketItem()'s defaults here — otherwise the
    // match is correctly rejected and this test would be asserting the wrong thing.
    releaseSecondSearch({
      items: [{
        priceId: 902, productCode: 'PC-902', factoryName: 'SCG Ceramics',
        grade: 'SCG', collection: 'B2', sizeRaw: '30x30', color: 'ขาว', surface: 'ด้าน',
        price: 250, currency: 'THB', priceUnit: 'per_piece',
      }],
    });

    await screen.findByText(/Catalog #902/);

    // Exactly one row auto-matched, and it is the B2 row the search was actually for — not the
    // C3 row that shifted into B2's old index.
    expect(screen.getAllByText(/จับคู่สินค้าจาก Catalog/)).toHaveLength(1);
    expect(screen.getByDisplayValue('B2')).not.toBeNull();
    expect(screen.getByDisplayValue('C3')).not.toBeNull();
  });
});
