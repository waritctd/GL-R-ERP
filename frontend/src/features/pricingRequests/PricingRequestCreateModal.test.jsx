import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// The component fetches Pricing Request attachments (V69) whenever a persisted id is available
// (createdId after save, or initialSummary.id in edit mode), QuotationItemRow's own รุ่น
// typeahead searches the catalog on typing, and (GLA-125) the header-terms section fetches the
// same eligible-display-name list the direct-deal quotation editor uses — none of these are
// under test in most cases here, so all three are stubbed to resolve emptily rather than hitting
// the network and polluting assertions.
vi.mock('../../api/index.js', () => ({
  api: {
    catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) },
    pricingRequests: {
      listAttachments: vi.fn().mockResolvedValue({ items: [] }),
      uploadAttachment: vi.fn().mockResolvedValue({ attachment: null }),
      deleteAttachment: vi.fn().mockResolvedValue({ ok: true }),
    },
    dealQuotations: {
      displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
    },
    // GLA-125 follow-up: ผู้สั่งซื้อ (QuotationContactPicker) fetches this whenever a customerId
    // is present -- most tests here never set one (deal defaults to null), so it stays unmocked
    // for those; the tests that DO set customerId configure this themselves per-test.
    customers: {
      contacts: vi.fn().mockResolvedValue({ contacts: [] }),
    },
  },
}));

function ticketItem(overrides = {}) {
  return {
    id: 501,
    brand: 'SCG', model: 'A1', color: 'ขาว', texture: 'ด้าน', size: '60x60',
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

// A row that satisfies every V185 required field (owner ruling: the PCR item form is the
// direct-deal quotation's TILE row minus price/discount) — used to fill in the gaps
// emptyItemFromTicketItem's seed leaves blank (thicknessMm has no ticket_item source at all,
// piecesPerBox likewise), so a "happy path" test can reach createFn/updateFn/createRevisionFn.
function fillRequiredFields() {
  // Fills every V185-required field a fixture might still be missing — safe to call whether the
  // row seeded from a ticket item (color/texture/size already present) or started fully blank.
  if (!screen.getByLabelText(/^สี/).value) fireEvent.change(screen.getByLabelText(/^สี/), { target: { value: 'ขาว' } });
  if (!screen.getByLabelText(/^ผิว/).value) fireEvent.change(screen.getByLabelText(/^ผิว/), { target: { value: 'ด้าน' } });
  if (!screen.getByLabelText(/^ขนาด \(ซม\.\)/).value) {
    fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '60x60' } });
  }
  fireEvent.change(screen.getByLabelText(/^ความหนา \(มม\.\)/), { target: { value: '10' } });
  fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '2.78' } });
  fireEvent.change(screen.getByLabelText(/^แผ่น\/กล่อง/), { target: { value: '4' } });
  // GLA-125: ประเทศต้นทาง is now required too -- picking a fixed-list country (not อื่นๆ)
  // auto-fills leadTimeMinDays/leadTimeMaxDays via QuotationItemRow's own onOriginChange, so
  // that satisfies the ระยะเวลานำเข้า requirement as a side effect, exactly as it does for a rep
  // clicking through the real form.
  if (!screen.getByLabelText(/^ประเทศต้นทาง/).value) {
    fireEvent.change(screen.getByLabelText(/^ประเทศต้นทาง/), { target: { value: 'ไทย-สต็อก' } });
  }
}

describe('PricingRequestCreateModal', () => {
  it('seeds the item row quantity mode/value from the deal item\'s unitBasis (PIECE -> PIECES) and its qty', () => {
    renderModal();
    // "จำนวน" combined toggle+input — PIECES mode selected, value 400.
    expect(screen.getByRole('button', { name: 'แผ่น', pressed: true })).not.toBeNull();
    expect(screen.getByDisplayValue('400')).not.toBeNull();
  });

  it('seeds AREA mode and the sqm quantity for an SQM-basis deal item', () => {
    renderModal({ ticketItems: [ticketItem({ unitBasis: 'SQM', qty: 400, qtySqm: 144 })] });
    expect(screen.getByRole('button', { name: 'ตร.ม.', pressed: true })).not.toBeNull();
    expect(screen.getByDisplayValue('144')).not.toBeNull();
  });

  it('seeds sqmPerPiece from the ticket item when the deal line carries one', () => {
    renderModal({ ticketItems: [ticketItem({ sqmPerPiece: 0.36 })] });
    // แผ่น/ตร.ม. shows the RECIPROCAL (piecesPerSqm), same convention as the direct-deal row.
    expect(screen.getByLabelText(/^แผ่น\/ตร\.ม\./).value).not.toBe('');
  });

  // Owner ruling 2026-09-18 (reversed from an earlier ยี่ห้อ ruling): the OLD free-text factory
  // input is gone, but the field that replaces it (QuotationItemRow's brand field, via
  // `brandLabel="โรงงาน"`) is deliberately LABELLED โรงงาน on this form — see
  // PricingRequestCreateModal.jsx's own module Javadoc. There is exactly one โรงงาน-labelled
  // input, and it holds `brand`, not a separate factory field.
  it('labels the brand field โรงงาน (owner ruling), and renders no unit-basis select or a dedicated รายละเอียดสินค้า box', () => {
    renderModal();
    expect(screen.getAllByLabelText(/^โรงงาน/)).toHaveLength(1);
    expect(screen.queryByLabelText('รายละเอียดสินค้า')).toBeNull();
    expect(screen.queryByText('-- เลือกหน่วย --')).toBeNull();
  });

  it('renders no price/discount input anywhere on the item row (CEO pricing is a later phase)', () => {
    renderModal();
    // Scoped to the per-ITEM price labels specifically -- "ราคาเป้าหมายของลูกค้า" is a top-level
    // form field (the customer's OWN target price, unrelated to the item row's own price/unit).
    expect(screen.queryByLabelText(/^ราคา\/หน่วย/)).toBeNull();
    expect(screen.queryByLabelText(/^ส่วนลด/)).toBeNull();
  });

  it('blocks submission with a per-row error when a required field (แผ่น-ตร.ม./แผ่น-กล่อง) is blank — ความหนา is now optional', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    // A still-required field blocks…
    expect(await screen.findByText('กรุณาระบุแผ่น/ตร.ม.')).not.toBeNull();
    // …but ความหนา no longer does (owner ruling 2026-09-26: a factory may not supply it, and
    // import/CEO fill it before costing — see quotationMeta's thicknessOptional).
    expect(screen.queryByText('กรุณาระบุความหนา (มม.)')).toBeNull();
    expect(createFn).not.toHaveBeenCalled();
  });

  it('shows a client-side hint when an AREA-mode quantity derives to 0 pieces (Opus review #2 -- mirrors the server\'s own zero-piece rejection)', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    // Switch to AREA mode and set a tiny area against a small piece-per-sqm figure -- 0.3 m² at
    // แผ่น/ตร.ม. 1.39 (sqmPerPiece ≈ 0.72) rounds DOWN to 0 pieces, the same fixture
    // mockApi.pricingRequests.test.js's own zero-piece test uses.
    fireEvent.click(screen.getByRole('button', { name: 'ตร.ม.' }));
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
    fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '0.3' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    expect(await screen.findByText(/0 ชิ้น/)).not.toBeNull();
    expect(createFn).not.toHaveBeenCalled();
  });

  it('clears the row error once the required fields are filled in, and allows submission', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    await screen.findByText('กรุณาระบุแผ่น/ตร.ม.');

    fillRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('กรุณาระบุแผ่น/ตร.ม.')).toBeNull();
  });

  it('sends the derived payload shape — no requestedQty/requestedUnit/requestedUnitBasis, notes carried as productDescription', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    fireEvent.change(screen.getByLabelText('หมายเหตุรายการ'), { target: { value: 'ต้องการด่วน' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    const payload = createFn.mock.calls[0][0];
    expect(payload.items).toHaveLength(1);
    const item = payload.items[0];
    expect(item).toMatchObject({
      brand: 'SCG', model: 'A1', color: 'ขาว', texture: 'ด้าน', size: '60x60',
      thicknessMm: 10, piecesPerBox: 4, quantityMode: 'PIECES', piecesInput: 400,
      productDescription: 'ต้องการด่วน',
    });
    expect(item.requestedQty).toBeUndefined();
    expect(item.requestedUnit).toBeUndefined();
    expect(item.requestedUnitBasis).toBeUndefined();
    // The fixture's ticketItem() has no `factory` -- confirms the field IS sent (not omitted, as
    // an earlier version of this form did) but is null when the source ticket item carries none.
    expect(item.factory).toBeNull();
  });

  it('carries a factory already on the ticket item through to the create payload (Opus review #1: Import\'s SetItemFactoryRequest must survive a sales save)', async () => {
    const { createFn } = renderModal({ ticketItems: [ticketItem({ factory: 'โรงงาน A' })] });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    const item = createFn.mock.calls[0][0].items[0];
    expect(item.factory).toBe('โรงงาน A');
  });

  it('blocks an item with no model — the identity/completeness gate reuses รุ่น, not a dedicated identity field', async () => {
    const { createFn } = renderModal({ ticketItems: [] }); // no deal items to seed from -> one blank row
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    expect(await screen.findByText('กรุณาระบุรุ่น')).not.toBeNull();
    expect(createFn).not.toHaveBeenCalled();
  });

  it('reuses the same clientRequestId when a lost create response is retried', async () => {
    const createFn = vi.fn().mockRejectedValue(new Error('lost response'));
    renderModal({ createFn });

    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
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
    fillRequiredFields();
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
    fillRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    await screen.findByRole('alert');

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    expect(await screen.findByRole('status')).not.toBeNull();
  });

  // ── GLA-125 items 1-3 ────────────────────────────────────────────────────────────────────
  it('picking a fixed-list ประเทศต้นทาง auto-fills ระยะเวลานำเข้า, exactly as the direct-deal form does', () => {
    renderModal();

    fireEvent.change(screen.getByLabelText(/^ประเทศต้นทาง/), { target: { value: 'จีน' } });

    expect(screen.getByDisplayValue('30')).not.toBeNull();
    expect(screen.getByDisplayValue('45')).not.toBeNull();
  });

  it('picking อื่นๆ reveals a required typed-name box, and blocks submission until it is filled', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    // fillRequiredFields already picked a fixed-list country -- switch to อื่นๆ instead, which
    // carries no default lead time (ORIGIN_COUNTRY_OPTIONS' own "อื่นๆ" entry), so ระยะเวลานำเข้า
    // needs typing in by hand too.
    fireEvent.change(screen.getByLabelText(/^ประเทศต้นทาง/), { target: { value: 'อื่นๆ' } });
    expect(screen.getByLabelText(/^ระบุประเทศต้นทาง/)).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    expect(await screen.findByText('กรุณาระบุชื่อประเทศต้นทาง')).not.toBeNull();
    expect(createFn).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText(/^ระบุประเทศต้นทาง/), { target: { value: 'เวียดนาม' } });
    fireEvent.change(screen.getByLabelText(/^ระยะเวลานำเข้า/), { target: { value: '20' } });
    fireEvent.change(screen.getByLabelText(/^ถึง \(วัน\)/), { target: { value: '25' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    const item = createFn.mock.calls[0][0].items[0];
    expect(item.originCountry).toBe('อื่นๆ');
    expect(item.originCountryOther).toBe('เวียดนาม');
  });

  // ── GLA-125 item 4 (header terms) ───────────────────────────────────────────────────────
  it('sends the header-terms section (payment term, validity, dept/unit code, omit-honorific) in the create payload', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();

    fireEvent.click(screen.getByLabelText('เครดิต'));
    fireEvent.change(screen.getByLabelText('ระยะเวลาเครดิต (วัน)'), { target: { value: '30' } });
    fireEvent.change(screen.getByLabelText('ยืนราคา (วัน)'), { target: { value: '15' } });
    fireEvent.change(screen.getByLabelText('ฝ่าย'), { target: { value: 'ขาย' } });
    fireEvent.change(screen.getByLabelText('หน่วยงาน / รหัสผู้ออกแบบ'), { target: { value: 'D01' } });
    // GLA-125 follow-up: this checkbox now lives on the reused QuotationContactPicker (its own
    // typographic-quote copy, "ไม่เติม “คุณ”..."), not a standalone PCR-only control.
    fireEvent.click(screen.getByLabelText(/ไม่เติม/));

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(createFn.mock.calls[0][0]).toMatchObject({
      paymentTermMode: 'CREDIT',
      creditDays: 30,
      validityDays: 15,
      deptCode: 'ขาย',
      unitCode: 'D01',
      omitContactHonorific: true,
    });
  });

  it('never sends creditDays when paymentTermMode is ON_DELIVERY, even if a stale value is still in the field', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();

    fireEvent.click(screen.getByLabelText('เครดิต'));
    fireEvent.change(screen.getByLabelText('ระยะเวลาเครดิต (วัน)'), { target: { value: '30' } });
    fireEvent.click(screen.getByLabelText('ชำระเมื่อส่งมอบ'));

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(createFn.mock.calls[0][0]).toMatchObject({ paymentTermMode: 'ON_DELIVERY', creditDays: null });
  });

  // ── GLA-125 follow-up: ผู้สั่งซื้อ (QuotationContactPicker reuse) ──────────────────────────
  it('scopes ผู้สั่งซื้อ to the deal\'s customer, and sends the picked contact\'s id as recipientContactId', async () => {
    api.customers.contacts.mockResolvedValueOnce({
      contacts: [{ id: 77, firstName: 'สมชาย', lastName: 'ทดสอบ', phone: '0812345678', email: 'somchai@example.com' }],
    });
    const { createFn } = renderModal({ deal: { customerId: 501, customerName: 'บริษัท ทดสอบ จำกัด' } });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();

    await waitFor(() => expect(api.customers.contacts).toHaveBeenCalledWith(501));
    fireEvent.change(screen.getByLabelText(/^ผู้สั่งซื้อ/), { target: { value: '77' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(createFn.mock.calls[0][0]).toMatchObject({ recipientContactId: 77 });
  });

  it('leaves ผู้สั่งซื้อ disabled with no customer to scope to, and sends recipientContactId: null', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();

    expect(screen.getByLabelText(/^ผู้สั่งซื้อ/).disabled).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(createFn.mock.calls[0][0]).toMatchObject({ recipientContactId: null });
  });

  it('a catalogue pick autofills the โรงงาน-labelled brand field from the catalog factory name', async () => {
    // Keyed on the query text, not mockResolvedValueOnce -- an earlier test's own debounced
    // (280ms) typeahead call can still be in flight when this test starts (see
    // QuotationItemRow.test.jsx's identical note on its own catalog-pick test).
    api.catalog.prices.mockImplementation(async (q) => (
      (q ?? '').includes('B2')
        ? { items: [{
          priceId: 901, productCode: 'PC-901', factoryName: 'SCG Ceramics',
          collection: 'B2', sizeRaw: '30x30', color: 'ขาว', surface: 'ด้าน',
          price: 120, currency: 'THB', priceUnit: 'per_piece',
        }] }
        : { items: [] }
    ));
    renderModal({ ticketItems: [] });

    fireEvent.change(screen.getByLabelText(/รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'B2' } });
    const option = await waitFor(() => screen.getByRole('option', { name: /B2/ }), { timeout: 1000 });
    fireEvent.mouseDown(option);

    expect(screen.getByDisplayValue('SCG Ceramics')).not.toBeNull();
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
        productDescription: 'กระเบื้องพื้น SCG A1', texture: 'ด้าน', size: '60x60',
        thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 20,
        wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: true,
        // GLA-125: required on this form.
        originCountry: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7,
        quantityType: 'CONFIRMED', targetDeliveryDate: null, deliveryLocation: null, specialRequirement: null,
      }],
      ...overrides,
    };
  }

  it('seeds every field from initialValue (including หมายเหตุรายการ from productDescription) and calls updateFn with the full payload on save', async () => {
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

  it('seeds ผู้สั่งซื้อ from the persisted request\'s recipientContactId/customerId (GLA-125 follow-up), and preserves it on save', async () => {
    api.customers.contacts.mockResolvedValueOnce({
      contacts: [{ id: 42, firstName: 'วิภา', lastName: 'ทดสอบ', phone: '0898765432', email: 'wipa@example.com' }],
    });
    const updateFn = vi.fn().mockResolvedValue({});
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={editInitialValue({
          summary: {
            ...editInitialValue().summary,
            recipientContactId: 42,
            customerId: 501,
            customerName: 'บริษัท ทดสอบ จำกัด',
          },
        })}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={updateFn}
      />,
    );

    // Seeded immediately as the "stand-in" ({ id, firstName: recipientLabel }) even before the
    // real contact list resolves -- see the `contact` state's own comment.
    expect(screen.getByLabelText(/^ผู้สั่งซื้อ/).value).toBe('42');
    await waitFor(() => expect(api.customers.contacts).toHaveBeenCalledWith(501));
    // Once resolved, the SELECT shows the real contact's name, not the stand-in recipientLabel.
    await waitFor(() => expect(screen.getByDisplayValue('วิภา ทดสอบ')).not.toBeNull());

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

    await waitFor(() => expect(updateFn).toHaveBeenCalledWith(77, expect.objectContaining({ recipientContactId: 42 })));
  });

  it('preserves a persisted factory/variantId through an edit save (Opus review #1: this modal has no โรงงาน/variant input, so it must round-trip whatever Import/the catalog snapshot already set instead of nulling it)', async () => {
    const updateFn = vi.fn().mockResolvedValue({});
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={editInitialValue({
          items: [{
            ...editInitialValue().items[0],
            factory: 'โรงงาน A (Import-assigned)',
            variantId: 909,
          }],
        })}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={updateFn}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

    await waitFor(() => expect(updateFn).toHaveBeenCalledWith(77, expect.objectContaining({
      items: [expect.objectContaining({ factory: 'โรงงาน A (Import-assigned)', variantId: 909 })],
    })));
  });

  it('seeds a legacy PER_SQM line (no quantityMode at all -- pre-V185 shape) into AREA mode with its stored quantity (Opus review #3)', () => {
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={editInitialValue({
          items: [{
            id: 9, sourceTicketItemId: null, productId: null, brand: 'SCG', model: 'A1', color: 'ขาว',
            productDescription: 'กระเบื้องพื้น SCG A1 (เดิม)', texture: 'ด้าน', size: '60x60',
            // Deliberately NO quantityMode/areaSqm/piecesInput/thicknessMm/sqmPerPiece/piecesPerBox
            // at all -- exactly the shape a row saved by the OLD (pre-V185) form has. Only the
            // legacy wire fields survive.
            requestedUnitBasis: 'PER_SQM', requestedQty: 144, requestedQtySqm: 144,
            requestedUnit: 'ตร.ม.',
            wastageMode: null, roundToFullBox: true,
            quantityType: 'CONFIRMED', targetDeliveryDate: null, deliveryLocation: null, specialRequirement: null,
          }],
        })}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={vi.fn()}
      />,
    );

    // AREA mode selected (not the blank-default's own AREA-with-no-value -- this asserts the
    // VALUE too), and the จำนวน field shows the legacy quantity, not empty.
    expect(screen.getByRole('button', { name: 'ตร.ม.', pressed: true })).not.toBeNull();
    expect(screen.getByDisplayValue('144')).not.toBeNull();
    // Second-pass review finding N1: a PER_SQM row's requestedQtySqm/requestedQty pair is NOT
    // pieces × sqmPerPiece (it's the same area counted twice, often exactly equal, as this
    // fixture's own 144/144 shows) -- deriving "sqmPerPiece" from it would give a fake ~1.0
    // ตร.ม./แผ่น and have the server order 144 pieces instead of the ~400 a real ~0.36 ตร.ม./แผ่น
    // tile needs. แผ่น/ตร.ม. must stay BLANK for the rep to fill in, never silently populated.
    expect(screen.getByLabelText(/^แผ่น\/ตร\.ม\./).value).toBe('');
  });

  it('DOES derive แผ่น/ตร.ม. for a legacy PER_PIECE line, where requestedQtySqm really was pieces × sqmPerPiece (N1 counterpart)', () => {
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={editInitialValue({
          items: [{
            id: 10, sourceTicketItemId: null, productId: null, brand: 'SCG', model: 'A1', color: 'ขาว',
            productDescription: 'กระเบื้องพื้น SCG A1 (เดิม)', texture: 'ด้าน', size: '60x60',
            // 400 pieces at a real sqmPerPiece of 0.36 -> requestedQtySqm = 144 (400 * 0.36).
            // Dividing back out (144 / 400 = 0.36) recovers the REAL figure here, unlike PER_SQM.
            requestedUnitBasis: 'PER_PIECE', requestedQty: 400, requestedQtySqm: 144,
            requestedUnit: 'แผ่น',
            wastageMode: null, roundToFullBox: true,
            quantityType: 'CONFIRMED', targetDeliveryDate: null, deliveryLocation: null, specialRequirement: null,
          }],
        })}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: 'แผ่น', pressed: true })).not.toBeNull();
    expect(screen.getByDisplayValue('400')).not.toBeNull();
    // แผ่น/ตร.ม. displays the RECIPROCAL of sqmPerPiece (piecesPerSqm convention) — 1/0.36 ≈ 2.78.
    expect(screen.getByLabelText(/^แผ่น\/ตร\.ม\./).value).toBe('2.78');
  });

  it('shows a read-only note (not a blank field) for a legacy PER_BOX line, whose quantity has no AREA/PIECES equivalent', () => {
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={editInitialValue({
          items: [{
            id: 10, sourceTicketItemId: null, productId: null, brand: 'SCG', model: 'A1', color: 'ขาว',
            productDescription: null, texture: 'ด้าน', size: '60x60',
            requestedUnitBasis: 'PER_BOX', requestedQty: 30, requestedUnit: 'กล่อง',
            wastageMode: null, roundToFullBox: true,
            quantityType: 'CONFIRMED', targetDeliveryDate: null, deliveryLocation: null, specialRequirement: null,
          }],
        })}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={vi.fn()}
      />,
    );

    expect(screen.getByText(/30 กล่อง/)).not.toBeNull();
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
// reuses this same modal (seeding, item rows, attachment uploader) instead.
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
        productDescription: 'กระเบื้องพื้น SCG A1', texture: 'ด้าน', size: '60x60',
        thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 20,
        wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: true,
        // GLA-125: required on this form.
        originCountry: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7,
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

  it('edits to quantity, recipient, and product notes actually reach the payload sent to createRevisionFn', async () => {
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
        piecesInput: 35,
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
// catalog ซ้ำ"): this modal seeds its catalog link, ผู้รับ label, and note from the deal it was
// opened from — CREATE MODE ONLY.
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
    // QuotationItemRow's own provenance badge ("จาก catalog · <code>"), not a bespoke one.
    expect(screen.getByText(/จาก catalog/)).not.toBeNull();
    expect(screen.getByText(/PC-777/)).not.toBeNull();
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

  it('seeds note from deal.note', () => {
    renderModal({ deal: dealFixture() });
    expect(screen.getByLabelText(/หมายเหตุถึงฝ่ายนำเข้า/).value).toBe('โน้ตจากขั้นตอนสร้างดีล');
    // Wrong-way-round: the project name must not have leaked into any surviving input.
    expect(screen.queryByDisplayValue('โครงการทดสอบ A')).toBeNull();
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
            color: 'ขาว', productDescription: '', texture: 'ด้าน', size: '60x60',
            thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 20,
            piecesPerBox: 4, quantityType: 'CONFIRMED',
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
    // None of the deal's own values leaked in anywhere.
    expect(screen.queryByDisplayValue('บริษัท เจ้าของ จำกัด')).toBeNull();
    expect(screen.queryByDisplayValue('โน้ตจากขั้นตอนสร้างดีล')).toBeNull();
    expect(screen.queryByDisplayValue('โครงการทดสอบ A')).toBeNull();
  });
});

// Removing the four inputs on 2026-08-11 created a data-loss trap that no other test covers:
// buildPayload writes the FULL item representation, so if the modal had simply stopped tracking
// those fields, opening any draft created BEFORE the removal and pressing บันทึกการแก้ไข would
// have silently nulled four persisted columns. The fields are therefore still seeded from the
// persisted request and echoed straight back.
describe('PricingRequestCreateModal preserves the removed per-item fields through an edit', () => {
  it('echoes back a persisted quantityType/targetDeliveryDate/deliveryLocation/specialRequirement instead of nulling them', async () => {
    const updateFn = vi.fn().mockResolvedValue({});
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={{
          summary: {
            id: 77, recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการ ข.',
            requiredDate: null, customerTargetPrice: null, targetCurrency: 'THB', note: null,
          },
          items: [{
            id: 5, sourceTicketItemId: null, productId: null, brand: 'SCG', model: 'A1',
            color: 'ขาว', productDescription: '', texture: 'ด้าน', size: '60x60',
            thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 20,
            piecesPerBox: 4, quantityType: 'CONFIRMED', targetDeliveryDate: '2026-09-30',
            deliveryLocation: 'ที่ส่งมอบเดิม', specialRequirement: 'ส่งด่วน',
            // GLA-125: required on this form.
            originCountry: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7,
          }],
        }}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={updateFn}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /บันทึกการแก้ไข/ }));

    await waitFor(() => expect(updateFn).toHaveBeenCalled());
    expect(updateFn.mock.calls[0][1].items[0]).toMatchObject({
      quantityType: 'CONFIRMED',
      targetDeliveryDate: '2026-09-30',
      deliveryLocation: 'ที่ส่งมอบเดิม',
      specialRequirement: 'ส่งด่วน',
    });
  });

  it('sends the ESTIMATE default and three nulls for a brand-new create-mode row', async () => {
    const { createFn } = renderModal({ ticketItems: [] });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.change(screen.getByLabelText(/รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'สินค้าใหม่ยังไม่มีใน catalog' } });
    fillRequiredFields();
    // "จำนวน"'s FormField htmlFor points straight at the numeric input (id `qty-0`) — PIECES
    // mode by default on a blank row, same as ticketItem()'s own PIECE default.
    fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '10' } });

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(createFn).toHaveBeenCalled());
    expect(createFn.mock.calls[0][0].items[0]).toMatchObject({
      quantityType: 'ESTIMATE',
      targetDeliveryDate: null,
      deliveryLocation: null,
      specialRequirement: null,
    });
  });
});
