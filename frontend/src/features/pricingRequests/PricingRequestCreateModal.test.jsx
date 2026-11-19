import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// The component fetches attachments whenever a persisted id is available (createdId after save, or
// initialSummary.id in edit mode), and QuotationItemRow's รุ่น typeahead searches the catalog on
// typing. Neither is under test in most cases, so both are stubbed to resolve emptily.
vi.mock('../../api/index.js', () => ({
  api: {
    catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) },
    pricingRequests: {
      listAttachments: vi.fn().mockResolvedValue({ items: [] }),
      uploadAttachment: vi.fn().mockResolvedValue({ attachment: null }),
      deleteAttachment: vi.fn().mockResolvedValue({ ok: true }),
    },
    // ผู้ติดต่อ (QuotationContactPicker) fetches this whenever a customerId is present. Most tests
    // never set one (deal defaults to null); those that do configure this per-test.
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

// A row that satisfies every required field (the PCR item form is the direct-deal quotation's
// TILE row minus price/discount): fills the gaps emptyItemFromTicketItem's seed leaves blank
// (thicknessMm and piecesPerBox have no ticket_item source), so a happy-path test can reach
// createFn/updateFn/createRevisionFn.
function fillRequiredFields(rowIndex = 0) {
  // Fills every required field a fixture might be missing, whether the row seeded from a ticket
  // item (color/texture/size present) or started blank. `rowIndex` picks the row on a multi-row form.
  const field = (re) => screen.getAllByLabelText(re)[rowIndex];
  if (!field(/^สี/).value) fireEvent.change(field(/^สี/), { target: { value: 'ขาว' } });
  if (!field(/^ผิว/).value) fireEvent.change(field(/^ผิว/), { target: { value: 'ด้าน' } });
  if (!field(/^ขนาด \(ซม\.\)/).value) {
    fireEvent.change(field(/^ขนาด \(ซม\.\)/), { target: { value: '60x60' } });
  }
  fireEvent.change(field(/^ความหนา \(มม\.\)/), { target: { value: '10' } });
  fireEvent.change(field(/^แผ่น\/ตร\.ม\./), { target: { value: '2.78' } });
  fireEvent.change(field(/^แผ่น\/กล่อง/), { target: { value: '4' } });
  // ประเทศต้นทาง is required; picking a fixed-list country (not อื่นๆ) also writes the origin's
  // default lead time onto the row (QuotationItemRow's onOriginChange), which the form does not
  // show (import gives the time).
  if (!field(/^ประเทศต้นทาง/).value) {
    fireEvent.change(field(/^ประเทศต้นทาง/), { target: { value: 'ไทย-สต็อก' } });
  }
  // สกุลเงิน + หน่วยราคา are required per line too (blank for a hand-typed row).
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

  it('blocks submission with a per-row error when a required field (ความหนา/แผ่น-ตร.ม./แผ่น-กล่อง) is left blank', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    expect(await screen.findByText('กรุณาระบุความหนา (มม.)')).not.toBeNull();
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
    // The fixture's ticketItem() has no `factory` -- confirms the field IS sent (not omitted, as an earlier
    // version of this form did) but is null when the source ticket item carries none.
    expect(item.factory).toBeNull();
  });

  // SALES flags stock per line when creating the PCR (the CEO types the price, sales does not).
  // The deal line's sourcedFromStock seeds the flag; the payload carries stockSource
  // (backend PricingRequestItemRequest: IN_THAILAND | IN_TRANSIT; null = import).
  describe('stock flag per line (QA-04)', () => {
    const stockCheckbox = () => screen.getByRole('checkbox', { name: /จากสต็อก/ });
    const submitWith = async (overrides) => {
      const { createFn } = renderModal(overrides);
      fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
      fillRequiredFields();
      return { createFn, send: async () => {
        fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
        await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
        return createFn.mock.calls[0][0].items;
      } };
    };

    it('seeds the flag from the deal item sourcedFromStock, and the create payload carries stockSource', async () => {
      const { send } = await submitWith({ ticketItems: [ticketItem({ sourcedFromStock: true })] });
      expect(stockCheckbox().checked).toBe(true);
      const [item] = await send();
      expect(item.stockSource).toBe('IN_THAILAND');
    });

    it('a stock line needs no ประเทศต้นทาง (backend exempts it); an import line still does', async () => {
      const { createFn } = renderModal({ ticketItems: [ticketItem({ sourcedFromStock: true })] });
      fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
      fillRequiredFields();
      fireEvent.change(screen.getByLabelText(/^ประเทศต้นทาง/), { target: { value: '' } });
      fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
      await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
      expect(createFn.mock.calls[0][0].items[0].originCountry).toBeNull();

      fireEvent.click(stockCheckbox()); // back to an import line -> origin required again
      fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
      expect(await screen.findByText('กรุณาระบุประเทศต้นทาง')).not.toBeNull();
      expect(createFn).toHaveBeenCalledTimes(1);
    });
  });

  it('picking อื่นๆ reveals a required typed-name box, and blocks submission until it is filled', async () => {
    const { createFn } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ'), { target: { value: 'ผู้ออกแบบ ก.' } });
    fillRequiredFields();
    // fillRequiredFields already picked a fixed-list country; switch to อื่นๆ, which carries no
    // default lead time (ORIGIN_COUNTRY_OPTIONS' "อื่นๆ" entry).
    fireEvent.change(screen.getByLabelText(/^ประเทศต้นทาง/), { target: { value: 'อื่นๆ' } });
    expect(screen.getByLabelText(/^ระบุประเทศต้นทาง/)).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));
    expect(await screen.findByText('กรุณาระบุชื่อประเทศต้นทาง')).not.toBeNull();
    expect(createFn).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText(/^ระบุประเทศต้นทาง/), { target: { value: 'เวียดนาม' } });
    // อื่นๆ carries no default lead time and the form does not ask for one: null is legal (import
    // supplies it).
    fireEvent.click(screen.getByRole('button', { name: /ส่งให้ฝ่ายนำเข้า/ }));

    await waitFor(() => expect(createFn).toHaveBeenCalledTimes(1));
    const item = createFn.mock.calls[0][0].items[0];
    expect(item.originCountry).toBe('อื่นๆ');
    expect(item.originCountryOther).toBe('เวียดนาม');
    expect(item.leadTimeMinDays).toBeNull();
    expect(item.leadTimeMaxDays).toBeNull();
  });

  // ── ผู้ติดต่อ (QuotationContactPicker reuse) ──
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
});

// The customer-change revision UI must let Sales edit the revision: copying the current request
// verbatim and collecting only a reason would leave the new DRAFT commercially identical to its
// parent. Revision mode reuses this modal (seeding, item rows, attachment uploader).
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
        // Required on this form.
        originCountry: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7,
        // Currency + price unit are required on every line.
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
});

// Sales fixes the currency and price unit on every line; import cannot change them later.
// Auto-filled from the catalogue pick; blank + required for a hand-typed line (the factory
// master's defaults are import/CEO-only, so sales has no source for them).
describe('PricingRequestCreateModal — สกุลเงิน + หน่วยราคา per line (R1, F4)', () => {
  const RECIPIENT = 'เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ';
  const currencySelect = () => screen.getByLabelText(/^สกุลเงิน \(ราคาโรงงาน\)/);
  const unitSelect = () => screen.getByLabelText(/^หน่วยราคา/);

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
});
