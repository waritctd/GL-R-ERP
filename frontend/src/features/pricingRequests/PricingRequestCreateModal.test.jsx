import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// The component fetches Pricing Request attachments (V69) whenever a persisted id is available
// (createdId after save, or initialSummary.id in edit mode), and QuotationItemRow's own รุ่น
// typeahead searches the catalog on typing — neither is under test in most cases here, so both are
// stubbed to resolve emptily rather than hitting the network and polluting assertions.
// (The header-terms "เงื่อนไขสำหรับใบเสนอราคา" box that used to fetch dealQuotations.displayNameOptions
// was removed on 2026-09-30, so that stub is gone with it.)
vi.mock('../../api/index.js', () => ({
  api: {
    catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) },
    pricingRequests: {
      listAttachments: vi.fn().mockResolvedValue({ items: [] }),
      uploadAttachment: vi.fn().mockResolvedValue({ attachment: null }),
      deleteAttachment: vi.fn().mockResolvedValue({ ok: true }),
    },
    // GLA-125 follow-up: ผู้ติดต่อ (QuotationContactPicker) fetches this whenever a customerId
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
function fillRequiredFields(rowIndex = 0) {
  // Fills every V185-required field a fixture might still be missing — safe to call whether the
  // row seeded from a ticket item (color/texture/size already present) or started fully blank.
  // `rowIndex` picks the row on a multi-row form (2026-10-18); the default keeps the one-row tests as they were.
  const field = (re) => screen.getAllByLabelText(re)[rowIndex];
  if (!field(/^สี/).value) fireEvent.change(field(/^สี/), { target: { value: 'ขาว' } });
  if (!field(/^ผิว/).value) fireEvent.change(field(/^ผิว/), { target: { value: 'ด้าน' } });
  if (!field(/^ขนาด \(ซม\.\)/).value) {
    fireEvent.change(field(/^ขนาด \(ซม\.\)/), { target: { value: '60x60' } });
  }
  fireEvent.change(field(/^ความหนา \(มม\.\)/), { target: { value: '10' } });
  fireEvent.change(field(/^แผ่น\/ตร\.ม\./), { target: { value: '2.78' } });
  fireEvent.change(field(/^แผ่น\/กล่อง/), { target: { value: '4' } });
  // GLA-125: ประเทศต้นทาง is required -- picking a fixed-list country (not อื่นๆ) also writes the
  // origin's default lead time onto the row (QuotationItemRow's onOriginChange), which the form
  // no longer shows (owner ruling 2026-10-18: import gives the time).
  if (!field(/^ประเทศต้นทาง/).value) {
    fireEvent.change(field(/^ประเทศต้นทาง/), { target: { value: 'ไทย-สต็อก' } });
  }
  // CR-1 (R1 / F4): สกุลเงิน + หน่วยราคา are required per line too (blank for a hand-typed row).
  const currency = field(/^สกุลเงิน \(ราคาโรงงาน\)/);
  if (!currency.value) fireEvent.change(currency, { target: { value: 'EUR' } });
  const unit = field(/^หน่วยราคา/);
  if (!unit.value) fireEvent.change(unit, { target: { value: 'PER_SQM' } });
}

describe('PricingRequestCreateModal', () => {
  it('seeds the item row quantity mode/value from the deal item\'s unitBasis (PIECE -> PIECES) and its qty', () => {
    renderModal();
    // "จำนวน" combined toggle+input — PIECES mode selected, value 400.
    expect(screen.getByRole('button', { name: 'แผ่น', pressed: true })).not.toBeNull();
    expect(screen.getByDisplayValue('400')).not.toBeNull();
    // A hand-typed deal line (no catalogPriceId) seeds nothing the catalogue would have supplied.
    expect(screen.getByLabelText(/^ความหนา \(มม\.\)/).value).toBe('');
    expect(screen.getByLabelText(/^ประเทศต้นทาง/).value).toBe('');
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

  it('blocks submission with a per-row error when a required field (ความหนา/แผ่น-ตร.ม./แผ่น-กล่อง) is left blank', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    expect(await screen.findByText('กรุณาระบุความหนา (มม.)')).not.toBeNull();
    expect(createFn).not.toHaveBeenCalled();
  });

  // Owner ruling 2026-10-12: any fraction of a piece rounds UP on the server, so a tiny positive
  // area is 1 piece and is no longer warned about; only a zero/blank area derives nothing.
  it('does not warn when a tiny AREA-mode quantity rounds up to 1 piece (server rounds every fraction up)', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    // 0.3 m² at แผ่น/ตร.ม. 1.39 (sqmPerPiece ≈ 0.72) = 0.417 pieces -> 1.
    fireEvent.click(screen.getByRole('button', { name: 'ตร.ม.' }));
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
    fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '0.3' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(screen.queryByText(/0 ชิ้น/)).toBeNull();
  });

  it('still warns when the AREA-mode quantity is zero (nothing to round up)', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: 'ตร.ม.' }));
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
    fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '0' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    expect(createFn).not.toHaveBeenCalled();
  });

  it('clears the row error once the required fields are filled in, and allows submission', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    await screen.findByText('กรุณาระบุความหนา (มม.)');

    fillRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('กรุณาระบุความหนา (มม.)')).toBeNull();
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
  it('shows no ระยะเวลานำเข้า input (import gives the time, owner ruling 2026-10-18), yet picking a fixed-list ประเทศต้นทาง still sends the origin default for import to adjust', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    expect(screen.queryByLabelText(/^ระยะเวลานำเข้า/)).toBeNull();
    fillRequiredFields();
    fireEvent.change(screen.getByLabelText(/^ประเทศต้นทาง/), { target: { value: 'จีน' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    const item = createFn.mock.calls[0][0].items[0];
    expect(item.leadTimeMinDays).toBe(30);
    expect(item.leadTimeMaxDays).toBe(45);
  });

  it('picking อื่นๆ reveals a required typed-name box, and blocks submission until it is filled', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    // fillRequiredFields already picked a fixed-list country -- switch to อื่นๆ instead, which
    // carries no default lead time (ORIGIN_COUNTRY_OPTIONS' own "อื่นๆ" entry).
    fireEvent.change(screen.getByLabelText(/^ประเทศต้นทาง/), { target: { value: 'อื่นๆ' } });
    expect(screen.getByLabelText(/^ระบุประเทศต้นทาง/)).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    expect(await screen.findByText('กรุณาระบุชื่อประเทศต้นทาง')).not.toBeNull();
    expect(createFn).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText(/^ระบุประเทศต้นทาง/), { target: { value: 'เวียดนาม' } });
    // อื่นๆ carries no default lead time and the form no longer asks for one: null is legal
    // (owner ruling 2026-10-18 — import supplies it).
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    const item = createFn.mock.calls[0][0].items[0];
    expect(item.originCountry).toBe('อื่นๆ');
    expect(item.originCountryOther).toBe('เวียดนาม');
    expect(item.leadTimeMinDays).toBeNull();
    expect(item.leadTimeMaxDays).toBeNull();
  });

  // ── Owner request 2026-09-30: the "เงื่อนไขสำหรับใบเสนอราคา" box was removed from the form ──
  // Sales does not set quotation terms at คำขอราคา time. The fields stay on the wire (buildPayload
  // still carries them) so an existing draft's saved terms survive an edit, but there is no input
  // for them here anymore — create mode therefore sends them all as null.
  it('no longer renders the "เงื่อนไขสำหรับใบเสนอราคา" box, and create mode sends its fields as null', async () => {
    const { createFn } = renderModal();
    // The whole box and every input inside it is gone.
    expect(screen.queryByText('เงื่อนไขสำหรับใบเสนอราคา (ไม่บังคับ)')).toBeNull();
    expect(screen.queryByLabelText('เครดิต')).toBeNull();
    expect(screen.queryByLabelText('ระยะเวลาเครดิต (วัน)')).toBeNull();
    expect(screen.queryByLabelText('ยืนราคา (วัน)')).toBeNull();
    expect(screen.queryByLabelText('แสดงชื่อผู้พิมพ์เป็น')).toBeNull();
    expect(screen.queryByLabelText('แสดงชื่อพนักงานขายเป็น')).toBeNull();
    expect(screen.queryByLabelText('ฝ่าย')).toBeNull();
    expect(screen.queryByLabelText('หน่วยงาน / รหัสผู้ออกแบบ')).toBeNull();

    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    // GLA-125 follow-up: this checkbox lives on the reused QuotationContactPicker (its own
    // typographic-quote copy, "ไม่เติม “คุณ”..."), independent of the removed box — still works.
    fireEvent.click(screen.getByLabelText(/ไม่เติม/));

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(createFn.mock.calls[0][0]).toMatchObject({
      paymentTermMode: null,
      creditDays: null,
      validityDays: null,
      printedByDisplayId: null,
      salesRepDisplayId: null,
      deptCode: null,
      unitCode: null,
      omitContactHonorific: true,
    });
  });

  // Owner, 2026-10-06: "remove ฝ่าย field completely". The quotation editor, the คำขอราคา page and
  // every printed form lost it then; this form still collected a value nothing showed or printed,
  // and the quotation's first save wiped it.
  it('has no ฝ่าย field', () => {
    renderModal();
    expect(screen.queryByLabelText('ฝ่าย')).toBeNull();
  });

  // ── GLA-125 follow-up: ผู้ติดต่อ (QuotationContactPicker reuse; labelled ผู้สั่งซื้อ until 2026-10-18) ──
  it('scopes ผู้ติดต่อ to the deal\'s customer, and sends the contact picked from the list as recipientContactId', async () => {
    api.customers.contacts.mockResolvedValueOnce({
      contacts: [{ id: 77, firstName: 'สมชาย', lastName: 'ทดสอบ', phone: '0812345678', email: 'somchai@example.com' }],
    });
    // The deal's stale contact (99, not in the list) must NOT pre-select ผู้ติดต่อ: the deal no
    // longer carries one, and a PCR's ผู้ติดต่อ is the rep's pick on this form.
    const { createFn } = renderModal({ deal: { customerId: 501, customerName: 'บริษัท ทดสอบ จำกัด', contactId: 99, contactName: 'ผู้ติดต่อเก่า ข.' } });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();

    await waitFor(() => expect(api.customers.contacts).toHaveBeenCalledWith(501));
    // Nothing pre-selected: the closed select is showing (no selected card, no เปลี่ยน trigger).
    expect(screen.getByLabelText(/^ผู้ติดต่อ/).textContent).toContain('- เลือกผู้ติดต่อ -');
    expect(screen.queryByRole('button', { name: 'เปลี่ยนผู้ติดต่อ' })).toBeNull();
    fireEvent.click(screen.getByLabelText(/^ผู้ติดต่อ/));
    fireEvent.click(await screen.findByRole('option', { name: /สมชาย ทดสอบ/ }));

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    // The pick, never the deal's stale 99.
    expect(createFn.mock.calls[0][0].recipientContactId).toBe(77);
  });

  it('leaves ผู้ติดต่อ disabled with no customer to scope to, and sends recipientContactId: null', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();

    expect(screen.getByLabelText(/^ผู้ติดต่อ/).disabled).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(createFn.mock.calls[0][0]).toMatchObject({ recipientContactId: null });
  });

  it('a catalogue pick autofills the โรงงาน-labelled brand field from the catalog factory name, and replaces a seeded origin, lead time, thickness and pieces-per-box with the picked product\'s', async () => {
    // Keyed on the query text, not mockResolvedValueOnce -- an earlier test's own debounced
    // (280ms) typeahead call can still be in flight when this test starts (see
    // QuotationItemRow.test.jsx's identical note on its own catalog-pick test).
    api.catalog.prices.mockImplementation(async (q) => (
      (q ?? '').includes('B2')
        ? { items: [{
          priceId: 901, productCode: 'PC-901', factoryName: 'SCG Ceramics',
          collection: 'B2', sizeRaw: '30x30', color: 'ขาว', surface: 'ด้าน',
          price: 120, currency: 'THB', priceUnit: 'per_piece',
          originCountryCode: 'CN', thicknessMm: null, pcsPerBox: 6,
        }] }
        : { items: [] }
    ));
    // Seeded from an Italian catalogue line (อิตาลี, 75-90 days, 9 mm, 2 per box); on this form a pick
    // means "the database has the information" (owner ruling 2026-10-18), so none of that may survive it.
    const { createFn } = renderModal({ ticketItems: [ticketItem({
      catalogPriceId: 777, catalogCurrency: 'EUR', catalogPriceUnit: 'per_sqm',
      catalogThicknessMm: 9, catalogPcsPerBox: 2, catalogOriginCountryCode: 'IT',
    })] });

    fireEvent.change(screen.getByLabelText(/รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'B2' } });
    const option = await waitFor(() => screen.getByRole('option', { name: /B2/ }), { timeout: 1000 });
    fireEvent.mouseDown(option);

    expect(screen.getByDisplayValue('SCG Ceramics')).not.toBeNull();
    expect(screen.getByLabelText(/^ความหนา \(มม\.\)/).value).toBe('');   // the pick knows no thickness
    fireEvent.change(screen.getByLabelText(/^ความหนา \(มม\.\)/), { target: { value: '8' } });
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(createFn.mock.calls[0][0].items[0]).toMatchObject({
      originCountry: 'จีน', leadTimeMinDays: 30, leadTimeMaxDays: 45, piecesPerBox: 6,
      requestedCurrency: 'THB', requestedPriceUnitBasis: 'PER_PIECE',
    });
  });
});

describe('PricingRequestCreateModal เพิ่มรายการ (UAT item 1, 2026-10-18)', () => {
  it('renders the เพิ่มรายการ button BELOW the item rows, not above them', () => {
    renderModal();
    const button = screen.getByRole('button', { name: /เพิ่มรายการ/ });
    const firstRow = screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/);
    // DOCUMENT_POSITION_FOLLOWING: the button comes after the first row's field in document order.
    expect(firstRow.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('focuses the new row\'s รุ่น field after เพิ่มรายการ, and leaves focus alone on later re-renders', async () => {
    renderModal();
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    const models = screen.getAllByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/);
    expect(models).toHaveLength(2);
    await waitFor(() => expect(document.activeElement).toBe(models[1]));
    // Typing elsewhere re-renders the modal; that must not drag focus back to the added row.
    const firstColor = screen.getAllByLabelText(/^สี/)[0];
    firstColor.focus();
    fireEvent.change(firstColor, { target: { value: 'เทา' } }); // a real change (the fixture is 'ขาว'), so it re-renders
    expect(document.activeElement).toBe(firstColor);
  });

  it('offers ลบรายการ only while there is more than one row', () => {
    renderModal();
    expect(screen.queryByRole('button', { name: 'ลบรายการ' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    expect(screen.getAllByRole('button', { name: 'ลบรายการ' })).toHaveLength(2);
    fireEvent.click(screen.getAllByRole('button', { name: 'ลบรายการ' })[0]);
    expect(screen.getAllByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/)).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'ลบรายการ' })).toBeNull();
  });

  it('after a failed save, ทำซ้ำ and ลบรายการ keep the red marks on the rows they belong to — a copy is clean, whether the original sat mid-list or last', async () => {
    renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));   // rows 0,1,2
    fillRequiredFields(0);                                                 // only row 0 is complete
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    const rowsErrored = () => screen.getAllByLabelText(/^สกุลเงิน \(ราคาโรงงาน\)/)
      .map((el) => el.closest('li').textContent.includes('กรุณาระบุสกุลเงิน'));
    await waitFor(() => expect(rowsErrored()).toEqual([false, true, true]));

    fireEvent.click(screen.getAllByRole('button', { name: 'ทำซ้ำรายการ' })[0]); // copy of row 0 lands at index 1
    expect(rowsErrored()).toEqual([false, false, true, true]);

    fireEvent.click(screen.getAllByRole('button', { name: 'ทำซ้ำรายการ' })[3]); // copy of the LAST (errored) row
    expect(rowsErrored()).toEqual([false, false, true, true, false]);

    fireEvent.click(screen.getAllByRole('button', { name: 'ลบรายการ' })[1]);   // remove the clean copy: marks move back up
    expect(rowsErrored()).toEqual([false, true, true, false]);
  });
});

describe('PricingRequestCreateModal typed โรงงาน → สกุลเงิน/หน่วยราคา default (UAT item 3, owner ruling 2026-10-18)', () => {
  // "automatic if the database has the information, otherwise sales fills it by hand": a typed factory
  // name that matches catalogue rows (GET /api/catalog/prices, open to every authenticated role) is
  // "the database has the information".
  const row = (factoryName, currency, priceUnit) => ({ factoryName, currency, priceUnit });
  const currency = () => screen.getByLabelText(/^สกุลเงิน \(ราคาโรงงาน\)/);
  const unit = () => screen.getByLabelText(/^หน่วยราคา/);
  async function typeFactory(name) {
    fireEvent.change(screen.getByLabelText('โรงงาน'), { target: { value: name } });
    await act(async () => { await vi.advanceTimersByTimeAsync(350); });
  }
  beforeEach(() => { vi.useFakeTimers(); api.catalog.prices.mockClear(); });
  afterEach(() => { vi.useRealTimers(); api.catalog.prices.mockResolvedValue({ items: [] }); });

  it('fills สกุลเงิน and หน่วยราคา from the matched factory\'s catalogue rows, case-insensitively — a hand-typed deal line looks itself up on open, no typing', async () => {
    api.catalog.prices.mockResolvedValue({ items: [row('Bode', 'USD', 'per_piece'), row('Bode', 'USD', 'per_piece')] });
    renderModal({ ticketItems: [ticketItem({ catalogPriceId: null, brand: 'bode' })] });
    await act(async () => { await vi.advanceTimersByTimeAsync(350); });
    expect(api.catalog.prices).toHaveBeenCalledWith('bode', null, 200);
    expect(currency().value).toBe('USD');
    expect(unit().value).toBe('PER_PIECE');
  });

  it('re-derives what it filled when the rep corrects โรงงาน: blank where the new factory disagrees with itself, blank where nothing matches', async () => {
    // Every query answers with the whole catalogue; only rows whose factory IS the typed name count.
    api.catalog.prices.mockResolvedValue({ items: [row('Vives', 'EUR', 'per_sqm'), row('Padana', 'EUR', 'per_sqm'), row('Padana', 'USD', 'per_sqm')] });
    renderModal({ ticketItems: [ticketItem({ catalogPriceId: null })] });
    await typeFactory('Vives');
    expect([currency().value, unit().value]).toEqual(['EUR', 'PER_SQM']);
    await typeFactory('Padana');                                   // quotes in EUR and USD
    expect([currency().value, unit().value]).toEqual(['', 'PER_SQM']);
    await typeFactory('Nobody Tiles');
    expect([currency().value, unit().value]).toEqual(['', '']);
  });

  it('drops an answer that arrives after the โรงงาน changed, and never overwrites a สกุลเงิน the rep already chose', async () => {
    api.catalog.prices.mockResolvedValue({ items: [row('Bode', 'USD', 'per_piece')] });
    let answerBode;
    api.catalog.prices.mockImplementationOnce(() => new Promise((resolve) => { answerBode = resolve; }));
    renderModal({ ticketItems: [ticketItem({ catalogPriceId: null })] });
    await typeFactory('Bode');                                    // request fires, still unanswered
    fireEvent.change(screen.getByLabelText('โรงงาน'), { target: { value: 'Bodex' } });
    await act(async () => { answerBode({ items: [row('Bode', 'USD', 'per_piece')] }); });
    expect(currency().value).toBe('');                            // the answer was for 'Bode', not 'Bodex'
    expect(unit().value).toBe('');

    fireEvent.change(currency(), { target: { value: 'THB' } });
    await typeFactory('Bode');
    expect(currency().value).toBe('THB');
    expect(unit().value).toBe('PER_PIECE');
  });

  it('does not look anything up for a catalogue-linked row — its terms came from the pick', async () => {
    renderModal({ ticketItems: [ticketItem({ catalogPriceId: 777, catalogCurrency: 'EUR', catalogPriceUnit: 'per_sqm' })] });
    await typeFactory('Bode');
    expect(api.catalog.prices).not.toHaveBeenCalledWith('Bode', null, 200);
    expect(currency().value).toBe('EUR');
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
        // GLA-125: required on this form. A min of 0 fails the lead-time range check, whose error has
        // no visible field here (hideLeadTime), so it must never block a save.
        originCountry: 'ไทย-สต็อก', leadTimeMinDays: 0, leadTimeMaxDays: 7,
        // CR-1: currency + price unit are required on every line now.
        requestedCurrency: 'THB', requestedPriceUnitBasis: 'PER_PIECE',
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

  // Owner request 2026-09-30: the "เงื่อนไขสำหรับใบเสนอราคา" box is gone, but the header terms it
  // used to edit are still persisted on the wire — editing a draft that already carries them must
  // NOT null them just because there is no input anymore.
  it('preserves the persisted header terms (payment/validity/display-name/dept-unit) on save even with the box removed', async () => {
    const updateFn = vi.fn().mockResolvedValue({});
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={editInitialValue({
          summary: {
            id: 77,
            recipientType: 'OWNER',
            recipientLabel: 'เจ้าของโครงการ ข.',
            paymentTermMode: 'CREDIT',
            creditDays: 30,
            validityDays: 15,
            printedByDisplayId: 8,
            salesRepDisplayId: 9,
            deptCode: 'ขาย',
            unitCode: 'D01',
          },
        })}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={updateFn}
      />,
    );

    // Control: the stored ฝ่าย is shown nowhere.
    expect(screen.queryByDisplayValue('ขาย')).toBeNull();
    // Confirm there is genuinely no box to re-enter these in.
    expect(screen.queryByText('เงื่อนไขสำหรับใบเสนอราคา (ไม่บังคับ)')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

    await waitFor(() => expect(updateFn).toHaveBeenCalledWith(77, expect.objectContaining({
      paymentTermMode: 'CREDIT',
      creditDays: 30,
      validityDays: 15,
      printedByDisplayId: 8,
      salesRepDisplayId: 9,
      // ฝ่าย has no field any more (owner 2026-10-06): a stored one is cleared, not preserved.
      deptCode: null,
      unitCode: 'D01',
    })));
  });

  // The wrong-way-round case for the removed ฝ่าย field: a draft saved before the removal still
  // carries one, and with no field to show or edit it, it must not ride along on the next save.
  it('saves a draft that still carries a ฝ่าย WITHOUT it, and shows it nowhere', async () => {
    const updateFn = vi.fn().mockResolvedValue({});
    const stored = editInitialValue();
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={{ ...stored, summary: { ...stored.summary, deptCode: 'P003' } }}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={updateFn}
      />,
    );

    // Control: the form did seed from this draft.
    expect(screen.getByDisplayValue('โน้ตเดิม')).not.toBeNull();
    expect(screen.queryByDisplayValue('P003')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

    await waitFor(() => expect(updateFn).toHaveBeenCalledTimes(1));
    expect(updateFn.mock.calls[0][1].deptCode).toBeNull();
  });

  it('seeds ผู้ติดต่อ from the persisted request\'s recipientContactId/customerId (GLA-125 follow-up), and preserves it on save', async () => {
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
    // real contact list resolves -- see the `contact` state's own comment. The selected card shows it.
    expect(screen.getByText('เจ้าของโครงการ ข.')).not.toBeNull();
    await waitFor(() => expect(api.customers.contacts).toHaveBeenCalledWith(501));
    // Once resolved, the card shows the real contact's name, not the stand-in recipientLabel.
    await waitFor(() => expect(screen.getByText('วิภา ทดสอบ')).not.toBeNull());
    expect(screen.queryByText('เจ้าของโครงการ ข.')).toBeNull();

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
        // CR-1: currency + price unit are required on every line now.
        requestedCurrency: 'THB', requestedPriceUnitBasis: 'PER_PIECE',
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

  it('prefills the catalog link and code from the ticket item — no re-search needed — plus ความหนา, แผ่น/กล่อง, ประเทศต้นทาง and the origin\'s lead time (owner ruling 2026-10-18)', async () => {
    const { createFn } = renderModal({ ticketItems: [ticketItem({
      catalogPriceId: 777, catalogProductCode: 'PC-777',
      catalogThicknessMm: 9, catalogPcsPerBox: 2, catalogOriginCountryCode: 'IT',
    })] });
    // QuotationItemRow's own provenance badge ("จาก catalog · <code>"), not a bespoke one.
    expect(screen.getByText(/จาก catalog/)).not.toBeNull();
    expect(screen.getByText(/PC-777/)).not.toBeNull();
    expect(screen.getByLabelText(/^ความหนา \(มม\.\)/).value).toBe('9');
    expect(screen.getByLabelText(/^แผ่น\/กล่อง/).value).toBe('2');
    expect(screen.getByLabelText(/^ประเทศต้นทาง/).value).toBe('อิตาลี');
    // The origin's default lead time rides along for import (no input for it on this form).
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '2.78' } });
    fireEvent.change(screen.getByLabelText(/^สกุลเงิน \(ราคาโรงงาน\)/), { target: { value: 'EUR' } });
    fireEvent.change(screen.getByLabelText(/^หน่วยราคา/), { target: { value: 'PER_SQM' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    const item = createFn.mock.calls[0][0].items[0];
    expect([item.thicknessMm, item.piecesPerBox, item.originCountry, item.leadTimeMinDays, item.leadTimeMaxDays]).toEqual([9, 2, 'อิตาลี', 75, 90]);
  });

  it('seeds blanks — never 0 or a raw ISO code — when the catalogue row lacks thickness/pieces-per-box and its origin is outside the four options', async () => {
    renderModal({ ticketItems: [ticketItem({
      catalogPriceId: 778, catalogThicknessMm: null, catalogPcsPerBox: null, catalogOriginCountryCode: 'PT',
    })] });
    expect(screen.getByLabelText(/^ความหนา \(มม\.\)/).value).toBe('');
    expect(screen.getByLabelText(/^แผ่น\/กล่อง/).value).toBe('');
    expect(screen.getByLabelText(/^ประเทศต้นทาง/).value).toBe('');
    // A select shows '' for a value it has no option for, so prove the row holds nothing: a raw 'PT'
    // would pass the required-origin check and this error would not appear.
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    expect(await screen.findByText('กรุณาระบุประเทศต้นทาง')).not.toBeNull();
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

  it('falls back to customerName — never the deal\'s old ผู้ติดต่อ — when the deal has no per-type recipient name', () => {
    renderModal({ deal: dealFixture({ designerName: null, ownerName: null, buyerName: null }) });
    expect(screen.getByDisplayValue('ลูกค้า ง.')).not.toBeNull();
    expect(screen.queryByDisplayValue('ผู้ติดต่อ ค.')).toBeNull();
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
        // CR-1: currency + price unit are required on every line now.
        requestedCurrency: 'THB', requestedPriceUnitBasis: 'PER_PIECE',
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

// CR-1 (GLA-167), rulings R1 + F4: sales fixes the currency and price unit on every line; import
// cannot change them later. Auto-filled from the catalogue pick; blank + required for a hand-typed
// line (the factory master's defaults are import/CEO-only, so sales has no source for them).
describe('PricingRequestCreateModal — สกุลเงิน + หน่วยราคา per line (R1, F4)', () => {
  const RECIPIENT = 'เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ';
  const currencySelect = () => screen.getByLabelText(/^สกุลเงิน \(ราคาโรงงาน\)/);
  const unitSelect = () => screen.getByLabelText(/^หน่วยราคา/);

  it('shows both selects on each line, blank for a hand-typed line', () => {
    renderModal({ ticketItems: [] });
    expect(currencySelect().value).toBe('');
    expect(unitSelect().value).toBe('');
  });

  it('blocks submission with per-row errors while either is blank, and sends nothing', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText(RECIPIENT), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    fireEvent.change(currencySelect(), { target: { value: '' } });
    fireEvent.change(unitSelect(), { target: { value: '' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    expect(await screen.findByText('กรุณาระบุสกุลเงิน')).not.toBeNull();
    expect(screen.getByText('กรุณาระบุหน่วยราคา')).not.toBeNull();
    expect(createFn).not.toHaveBeenCalled();
  });

  it('a catalogue pick auto-fills the currency and unit from the catalogue row, and sales may still change them', async () => {
    api.catalog.prices.mockImplementation(async (q) => (
      (q ?? '').includes('B2')
        ? { items: [{
          priceId: 901, productCode: 'PC-901', factoryName: 'Panaria', collection: 'B2', sizeRaw: '60x60',
          color: 'ขาว', surface: 'ด้าน', price: 12, currency: 'EUR', priceUnit: 'per_sqm',
        }] }
        : { items: [] }
    ));
    const { createFn } = renderModal({ ticketItems: [] });
    fireEvent.change(screen.getByPlaceholderText(RECIPIENT), { target: { value: 'ผู้ออกแบบ ก.' } });
    fireEvent.change(screen.getByLabelText(/รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'B2' } });
    fireEvent.mouseDown(await waitFor(() => screen.getByRole('option', { name: /B2/ }), { timeout: 1000 }));

    expect(currencySelect().value).toBe('EUR');
    expect(unitSelect().value).toBe('PER_SQM');

    fireEvent.change(currencySelect(), { target: { value: 'USD' } });
    fillRequiredFields();
    fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(createFn).toHaveBeenCalled());
    expect(createFn.mock.calls[0][0].items[0]).toMatchObject({
      requestedCurrency: 'USD', requestedPriceUnitBasis: 'PER_SQM',
    });
  });

  it('a catalogue row with an unknown price unit leaves the unit blank (required) instead of guessing', async () => {
    api.catalog.prices.mockImplementation(async (q) => (
      (q ?? '').includes('B3')
        ? { items: [{
          priceId: 902, productCode: 'PC-902', factoryName: 'Panaria', collection: 'B3', sizeRaw: '60x60',
          color: 'ขาว', surface: 'ด้าน', price: 12, currency: 'EUR', priceUnit: 'unknown',
        }] }
        : { items: [] }
    ));
    renderModal({ ticketItems: [] });
    fireEvent.change(screen.getByLabelText(/รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'B3' } });
    fireEvent.mouseDown(await waitFor(() => screen.getByRole('option', { name: /B3/ }), { timeout: 1000 }));
    expect(currencySelect().value).toBe('EUR');
    expect(unitSelect().value).toBe('');
  });

  it('a hand-typed line sends the currency/unit sales chose', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText(RECIPIENT), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    fireEvent.change(currencySelect(), { target: { value: 'USD' } });
    fireEvent.change(unitSelect(), { target: { value: 'PER_BOX' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    expect(createFn.mock.calls[0][0].items[0]).toMatchObject({ requestedCurrency: 'USD', requestedPriceUnitBasis: 'PER_BOX' });
  });

  it('edit mode seeds them from the persisted item and sends them back on save', async () => {
    const updateFn = vi.fn().mockResolvedValue({});
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={{
          summary: { id: 77, recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการ ข.' },
          items: [{
            id: 5, brand: 'SCG', model: 'A1', color: 'ขาว', texture: 'ด้าน', size: '60x60',
            thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 20,
            wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: true,
            originCountry: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7,
            requestedCurrency: 'EUR', requestedPriceUnitBasis: 'PER_SQM',
          }],
        }}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={updateFn}
      />,
    );
    expect(currencySelect().value).toBe('EUR');
    expect(unitSelect().value).toBe('PER_SQM');
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));
    await waitFor(() => expect(updateFn).toHaveBeenCalled());
    expect(updateFn.mock.calls[0][1].items[0]).toMatchObject({ requestedCurrency: 'EUR', requestedPriceUnitBasis: 'PER_SQM' });
  });

  it('a legacy persisted line with no terms must be completed before it can be saved again', async () => {
    const updateFn = vi.fn().mockResolvedValue({});
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialValue={{
          summary: { id: 77, recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการ ข.' },
          items: [{
            id: 5, brand: 'SCG', model: 'A1', color: 'ขาว', texture: 'ด้าน', size: '60x60',
            thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 20,
            wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: true,
            originCountry: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7,
          }],
        }}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={updateFn}
      />,
    );
    expect(currencySelect().value).toBe('');
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));
    expect(await screen.findByText('กรุณาระบุสกุลเงิน')).not.toBeNull();
    expect(updateFn).not.toHaveBeenCalled();
  });
});

// Owner ruling 2026-10-22: the deal page's เสนอราคา menu opens this modal with ผู้รับ
// already set to the next party on the route. `initialRecipientType` seeds the chip AND the ผู้รับ
// label (from the deal's per-type name) — CREATE MODE ONLY; edit/revision keep the persisted recipient.
describe('PricingRequestCreateModal initialRecipientType (owner ruling 2026-10-22)', () => {
  const deal = {
    designerName: 'สมชาย ผู้ออกแบบ',
    ownerName: 'บริษัท เจ้าของ จำกัด',
    buyerName: 'ผู้ซื้อ ข.',
    customerName: 'ลูกค้า ง.',
  };
  const chip = (name) => screen.getByRole('radio', { name });

  it('create mode: pre-selects เจ้าของโครงการ and pre-fills the ผู้รับ label from deal.ownerName', () => {
    renderModal({ deal, initialRecipientType: 'OWNER' });
    expect(chip(/เจ้าของโครงการ/).getAttribute('aria-checked')).toBe('true');
    expect(chip(/ผู้ออกแบบ/).getAttribute('aria-checked')).toBe('false');
    expect(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ').value).toBe('บริษัท เจ้าของ จำกัด');
  });

  it('create mode: BUYER pre-selects ผู้ซื้อ and fills deal.buyerName', () => {
    renderModal({ deal, initialRecipientType: 'BUYER' });
    expect(chip(/ผู้ซื้อ/).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ').value).toBe('ผู้ซื้อ ข.');
  });

  it('create mode: the seeded recipient is what gets sent', async () => {
    const { createFn } = renderModal({ deal, initialRecipientType: 'OWNER' });
    fillRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    await waitFor(() => expect(createFn).toHaveBeenCalledWith(expect.objectContaining({
      recipientType: 'OWNER', recipientLabel: 'บริษัท เจ้าของ จำกัด',
    })));
  });

  it('without the prop, create mode still defaults to ผู้ออกแบบ (unchanged)', () => {
    renderModal({ deal });
    expect(chip(/ผู้ออกแบบ/).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ').value).toBe('สมชาย ผู้ออกแบบ');
  });

  it('edit mode ignores it: the persisted recipient and label win', () => {
    render(
      <PricingRequestCreateModal
        mode="edit"
        initialRecipientType="BUYER"
        deal={deal}
        initialValue={{
          summary: { id: 77, recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการ ข.', targetCurrency: 'THB' },
          items: [],
        }}
        onClose={vi.fn()}
        onCreated={vi.fn()}
        updateFn={vi.fn()}
      />,
    );
    expect(chip(/เจ้าของโครงการ/).getAttribute('aria-checked')).toBe('true');
    expect(chip(/ผู้ซื้อ/).getAttribute('aria-checked')).toBe('false');
    expect(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ').value).toBe('เจ้าของโครงการ ข.');
  });
});
