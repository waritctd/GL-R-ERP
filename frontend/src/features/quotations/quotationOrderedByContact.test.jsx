import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';
import { buildQuotationChecklist, orderedByEmailError } from './quotationMeta.js';

globalThis.React = React;

// Whole-editor cases, several of which fill a complete item row and save.
vi.setConfig({ testTimeout: 15000 });

// ผู้ติดต่อ is a picker over the deal customer's contact records (owner rulings 2026-10-18,
// .design/quotation-contact/DESIGN.md): the editor sends the picked `contactId`, and the server
// writes the ผู้สั่งซื้อ signature name from that contact, so the editor sends no `orderedByName`.
//
// Under the picker sit the CUSTOMER's phone and email (`orderedByPhone`, `orderedByEmail`),
// labelled โทร. / อีเมล. They print on the document's customer contact line when filled (owner ruling
// 2026-10-13); a blank one prints nothing.
//
// Evidence note: every server response here is a vi.fn(). What the server stores, refuses and
// prints is DealQuotationOrderedByContactIntegrationTest / …RenderTest (Java, real Postgres);
// this file is what the editor shows, checks and sends.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        approve: vi.fn(), reject: vi.fn(), createRevision: vi.fn(), createReorder: vi.fn(), cancel: vi.fn(),
        downloadPdf: vi.fn(), downloadXlsx: vi.fn(), displayNameOptions: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), update: vi.fn(), projects: vi.fn(), createProject: vi.fn(),
        contacts: vi.fn(), createContact: vi.fn(),
      },
    },
  };
});

const PHONE = '089-765-4321 ต่อ 77';
const EMAIL = 'bid.contact@contractor-b.example';
const PRINTED_WHEN_FILLED = 'พิมพ์ในใบเสนอราคาเมื่อกรอก';
const PHONE_LABEL = 'โทร.';
const EMAIL_LABEL = 'อีเมล';
// โทร. is also the customer card's box and อีเมล the picked contact's edit box: narrow to orderedBy*.
const BOX = { selector: '[id^="orderedBy"]' };
// A contact of the deal's customer (fictional).
const SOMCHAI = { id: 31, customerId: 5, firstName: 'สมชาย', lastName: 'ใจดี', phone: '081-234-5678', email: null };
const BAD_EMAIL = 'รูปแบบอีเมลไม่ถูกต้อง';

// The same shapes DealQuotationOrderedByContactIntegrationTest refuses on the server. Keep the two
// lists in step: an address the editor lets through and the server then refuses is a save the rep
// cannot explain.
const MALFORMED_EMAILS = [
  'somchai.example.com',        // no @
  'somchai@',                   // nothing after it
  '@example.com',               // nothing before it
  'somchai@example',            // no dot in the domain — the ".com" that was never typed
  'som chai@example.com',       // a space inside
  'somchai@example@gmail.com',  // two @
];
const WELL_FORMED_EMAILS = [EMAIL, 'Somchai+QT@Example.CO.TH', '  padded@example.com  '];

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
const CUSTOMER = { id: 5, name: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', taxId: '0105551234567', address: 'กรุงเทพฯ', phone: '02-000-0000' };
const PROJECT = { id: 3, customerId: 5, name: 'โครงการ A' };

// A COMPLETE stored tile row (validateQuotationItem finds nothing missing).
const TILE_ITEM = {
  id: 101, seq: 1, lineType: 'TILE', locationLabel: null, catalogPriceId: null, productCode: null,
  brand: 'Marazzi', model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
  sqmPerPiece: 0.36, quantityMode: 'AREA', areaSqm: 20, piecesInput: null, wastageMode: 'PERCENT',
  wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0, originCountry: null,
  leadTimeMinDays: 30, leadTimeMaxDays: 45, itemNotes: null,
  piecesPerSqm: 2.78, piecesBeforeWastage: 56, piecesAfterWastage: 56, piecesFinal: 57, boxes: 19,
  netUnitPrice: 850, lineAmount: 48450, descriptionLine: 'Trilogy Ash', sizeLine: '60x60', calculationLine: '(calc)',
  quantity: 57, unit: 'แผ่น', specialPriceSqm: null, adjustmentPct: null, adjustmentDeadline: null,
  specialPriceLine: null, adjustmentAmount: null,
};

function draft(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005', ticketId: 18, docStatus: 'DRAFT', revisionNo: 1, parentQuotationId: null,
    origin: 'DEAL_DIRECT',
    salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000', createdByName: 'คุณสมหมาย ขายดี',
    approvalNote: null, quotationDate: '2026-10-11',
    customerName: CUSTOMER.name, customerAddress: CUSTOMER.address, customerTaxId: CUSTOMER.taxId, customerPhone: CUSTOMER.phone,
    contactId: null, contactName: null, contactPhone: null, contactEmail: null,
    orderedByName: 'คุณวิชัย มั่นคง',
    projectName: 'โครงการ A', deptCode: null, unitCode: 'A001', offerDate: '2026-10-11', depositPercent: 30,
    remainderMode: 'ON_DELIVERY', creditDays: null, validityDays: 30, validityDate: null, customerNotes: null,
    priceMode: 'NET', documentLanguage: 'TH', subtotalAmount: 48450, vatAmount: 3391.5, grandTotal: 51841.5,
    currency: 'THB', approverHasSignature: false, items: [TILE_ITEM],
    createdAt: '2026-10-11T02:00:00Z', updatedAt: '2026-10-11T02:00:00Z',
    ...overrides,
  };
}

function SavedAs() {
  const { id } = useParams();
  return <p>navigated-to-{id}</p>;
}

/** `/quotations/new…` renders the editor and a save lands on a stand-in; `/quotations/5` renders
 * the editor on the stored draft. */
function renderEditor(path) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const opensADraft = !path.startsWith('/quotations/new');
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/quotations/new" element={<QuotationEditorPage user={salesUser} showToast={vi.fn()} />} />
          <Route
            path="/quotations/:id"
            element={opensADraft ? <QuotationEditorPage user={salesUser} showToast={vi.fn()} /> : <SavedAs />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const controls = () => [...document.querySelectorAll('button, input, select, textarea')];
/** The next form control in reading order. */
const directlyAfter = (control) => controls()[controls().indexOf(control) + 1];
/** The text of whatever the control names as its description (a FormField hint or error line). */
const describedBy = (control) => (control.getAttribute('aria-describedby') ?? '')
  .split(/\s+/).filter(Boolean)
  .map((id) => document.getElementById(id)?.textContent ?? '')
  .join(' ');
const saveButton = () => screen.getByRole('button', { name: 'บันทึกร่าง' });

/** Opens the ผู้ติดต่อ picker once it has a customer, and picks the contact named `name`. */
async function pickContact(name) {
  const trigger = await screen.findByLabelText(/^ผู้ติดต่อ/);
  await waitFor(() => expect(trigger.disabled).toBe(false));
  fireEvent.click(trigger);
  fireEvent.click(await screen.findByRole('option', { name: new RegExp(name) }));
}

// Every field validateQuotationItem requires — the same fill QuotationEditorPage.test.jsx uses.
async function fillCompleteQuotationItem() {
  fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy' } });
  fireEvent.change(screen.getByLabelText(/^สี/), { target: { value: 'Ivory' } });
  fireEvent.change(screen.getByLabelText(/^ผิว/), { target: { value: 'Lappato' } });
  fireEvent.change(screen.getByLabelText(/^ขนาด/), { target: { value: '60x120' } });
  fireEvent.change(screen.getByLabelText(/^ความหนา/), { target: { value: '10' } });
  fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
  fireEvent.change(screen.getByLabelText(/^แผ่น\/กล่อง/), { target: { value: '3' } });
  fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '850' } });
  fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '36' } });
}

beforeEach(() => {
  vi.clearAllMocks();
  api.tickets.get.mockResolvedValue({
    ticket: {
      summary: {
        id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', customerName: CUSTOMER.name, customerId: 5,
        projectId: 3, projectName: 'โครงการ A', contactId: null, contactName: null,
        // A deal at a quote stage preselects its recipient (slice 2 flow B), so saving is not held back.
        salesStage: 'QUOTE_DESIGN_SIDE',
      },
    },
  });
  api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 42 } } });
  api.customers.search.mockResolvedValue({ customers: [CUSTOMER] });
  api.customers.projects.mockResolvedValue({ projects: [PROJECT] });
  api.customers.contacts.mockResolvedValue({ contacts: [] });
  api.dealQuotations.get.mockResolvedValue({ quotation: draft() });
  api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
  api.dealQuotations.update.mockImplementation(async () => ({ quotation: draft() }));
  api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  api.dealQuotations.displayNameOptions.mockResolvedValue({ items: [] });
});

describe('ผู้ติดต่อ — the customer phone and email, directly under the picker, in both forms', () => {
  it('a quotation for an existing deal: two inputs labelled โทร. and อีเมล, next after the ผู้ติดต่อ picker and its "คุณ" box', async () => {
    renderEditor('/quotations/new?ticket=18');

    const picker = await screen.findByLabelText(/^ผู้ติดต่อ/);
    const honorific = screen.getByRole('checkbox', { name: /ไม่เติม/ });
    const phone = screen.getByLabelText(PHONE_LABEL, BOX);
    const email = screen.getByLabelText(EMAIL_LABEL, BOX);

    expect([picker.tagName, picker.id, phone.id, email.id]).toEqual(['BUTTON', 'quotation-contact', 'orderedByPhoneCard', 'orderedByEmailCard']);
    expect([phone.tagName, email.tagName]).toEqual(['INPUT', 'INPUT']);
    // The bare English labels are gone.
    expect([screen.queryByLabelText('Phone'), screen.queryByLabelText('Email')]).toEqual([null, null]);
    // Directly under: nothing else to fill in comes between them.
    expect(directlyAfter(picker)).toBe(honorific);
    expect(directlyAfter(honorific)).toBe(phone);
    expect(directlyAfter(phone)).toBe(email);
    // The server's own limits, so a value it would refuse for length cannot be typed.
    expect([phone.maxLength, email.maxLength]).toEqual([50, 254]);
    // Both start empty, and neither is required.
    expect([phone.value, email.value, phone.required, email.required]).toEqual(['', '', false, false]);
  });

  it('a quotation that creates its deal inline: the same two inputs, in the same place', async () => {
    renderEditor('/quotations/new');
    // Slice 2 flow A: /quotations/new opens on เลือกดีลที่มีอยู่; the inline card is the other branch.
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));

    await screen.findByLabelText(/^ลูกค้า/);
    const picker = screen.getByLabelText(/^ผู้ติดต่อ/);
    const honorific = screen.getByRole('checkbox', { name: /ไม่เติม/ });
    const phone = screen.getByLabelText(PHONE_LABEL, BOX);
    const email = screen.getByLabelText(EMAIL_LABEL, BOX);

    expect([picker.tagName, phone.id, email.id]).toEqual(['BUTTON', 'orderedByPhoneInline', 'orderedByEmailInline']);
    expect(directlyAfter(picker)).toBe(honorific);
    expect(directlyAfter(honorific)).toBe(phone);
    expect(directlyAfter(phone)).toBe(email);
    expect([phone.maxLength, email.maxLength]).toEqual([50, 254]);
  });

  // Owner ask 2026-10-18: the picker's hint is the ONLY explanatory text on the form, so the
  // line these two used to carry ("…พิมพ์ในใบเสนอราคาเมื่อกรอก") is gone — in both forms.
  it('neither carries a line of its own any more — in both forms', async () => {
    const first = renderEditor('/quotations/new?ticket=18');
    await screen.findByLabelText(/^ผู้ติดต่อ/);
    expect([describedBy(screen.getByLabelText(PHONE_LABEL, BOX)), describedBy(screen.getByLabelText(EMAIL_LABEL, BOX))]).toEqual(['', '']);
    expect(screen.queryByText(new RegExp(PRINTED_WHEN_FILLED))).toBeNull();
    first.unmount();

    renderEditor('/quotations/new');
    // Slice 2 flow A: /quotations/new opens on เลือกดีลที่มีอยู่; the inline card is the other branch.
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));
    await screen.findByLabelText(/^ลูกค้า/);
    expect([describedBy(screen.getByLabelText(PHONE_LABEL, BOX)), describedBy(screen.getByLabelText(EMAIL_LABEL, BOX))]).toEqual(['', '']);
    expect(screen.queryByText(new RegExp(PRINTED_WHEN_FILLED))).toBeNull();
  });
});

describe('ผู้ติดต่อ — what the editor saves', () => {
  // A draft with no contact: picking one sends its id, and nothing asks the server to clear.
  // A draft WITH a contact: ล้าง sends `clearContact: true` beside `contactId: null`, because a
  // null contactId alone means "keep the draft's contact" (DealQuotationService#resolveContact).
  it('picks คุณสมชาย from the list → sends his contactId and no orderedByName; ล้าง on a draft that had a contact sends clearContact: true; the customer phone/email typed beside go as orderedByPhone / orderedByEmail, trimmed', async () => {
    api.customers.contacts.mockResolvedValue({ contacts: [SOMCHAI] });
    const picking = renderEditor('/quotations/5'); // draft(): no contact
    await screen.findByDisplayValue('Trilogy');

    await pickContact('สมชาย');
    fireEvent.change(screen.getByLabelText(PHONE_LABEL, BOX), { target: { value: `  ${PHONE} ` } });
    fireEvent.change(screen.getByLabelText(EMAIL_LABEL, BOX), { target: { value: ` ${EMAIL}  ` } });
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const picked = api.dealQuotations.update.mock.calls[0][1];
    expect(picked).toMatchObject({ contactId: SOMCHAI.id, orderedByPhone: PHONE, orderedByEmail: EMAIL });
    // The signature name is the server's to write from the contact (ruling 2026-10-18): not sent.
    expect(picked).not.toHaveProperty('orderedByName');
    expect(picked).not.toHaveProperty('clearContact');
    picking.unmount();
    api.dealQuotations.update.mockClear();

    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ contactId: SOMCHAI.id, contactName: 'สมชาย ใจดี', contactPhone: SOMCHAI.phone, contactEmail: null }),
    });
    renderEditor('/quotations/5');
    const clear = await screen.findByRole('button', { name: 'ล้างผู้ติดต่อ' });
    await waitFor(() => expect(clear.disabled).toBe(false));
    fireEvent.click(clear);
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const cleared = api.dealQuotations.update.mock.calls[0][1];
    expect(cleared).toMatchObject({ contactId: null, clearContact: true });
    expect(cleared).not.toHaveProperty('orderedByName');
  });

  // Optional, both of them: an untouched pair never holds the save back, and is sent as an
  // explicit null — every save states every header field, so nothing is left for the server to
  // guess from a missing key.
  it('left empty, both are sent as null and the save goes through', async () => {
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.create.mock.calls[0][1]).toMatchObject({ orderedByPhone: null, orderedByEmail: null });
  });

  it('the inline form saves them too, with the picked contact, on the quotation it creates with its new deal', async () => {
    api.customers.contacts.mockResolvedValue({ contacts: [SOMCHAI] });
    renderEditor('/quotations/new');
    // Slice 2 flow A: /quotations/new opens on เลือกดีลที่มีอยู่; the inline card is the other branch.
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));

    fireEvent.change(await screen.findByLabelText(/^ลูกค้า/), { target: { value: 'แฟชั่น' } });
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(CUSTOMER.name) }));
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(CUSTOMER.id));
    fireEvent.focus(screen.getByLabelText(/^โครงการ/));
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(PROJECT.name) }));
    // Slice 2 (S2-B1) + #1085: a new deal's recipient and a real ช่องทางรับงาน gate บันทึกร่าง.
    fireEvent.click(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }));
    fireEvent.click(screen.getByRole('button', { name: /ผู้ออกแบบนำดีล/ }));
    // The picker lists the contacts of the customer just chosen, before any deal exists.
    await pickContact('สมชาย');
    expect(api.customers.contacts).toHaveBeenCalledWith(CUSTOMER.id);
    fireEvent.change(screen.getByLabelText(PHONE_LABEL, BOX), { target: { value: PHONE } });
    fireEvent.change(screen.getByLabelText(EMAIL_LABEL, BOX), { target: { value: EMAIL } });
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.create.mock.calls[0][0]).toBe(42);
    expect(api.dealQuotations.create.mock.calls[0][1]).toMatchObject({ contactId: SOMCHAI.id, orderedByPhone: PHONE, orderedByEmail: EMAIL });
    // The deal itself is given no contact (ruling 2026-10-18): the quotation carries it.
    expect(api.tickets.create.mock.calls[0][0].contactId).toBeNull();
    expect(JSON.stringify(api.tickets.create.mock.calls[0][0])).not.toContain(EMAIL);
  });

  it('a stored draft opens with its Phone and Email in the boxes; one from before they existed opens with both empty', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ orderedByPhone: PHONE, orderedByEmail: EMAIL }) });
    const withBoth = renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByLabelText(PHONE_LABEL, BOX).value).toBe(PHONE));
    expect(screen.getByLabelText(EMAIL_LABEL, BOX).value).toBe(EMAIL);
    withBoth.unmount();

    api.dealQuotations.get.mockResolvedValue({ quotation: draft() }); // no such keys on the DTO
    renderEditor('/quotations/5');
    await screen.findByDisplayValue('Trilogy'); // the stored draft has been read into the form
    expect([screen.getByLabelText(PHONE_LABEL, BOX).value, screen.getByLabelText(EMAIL_LABEL, BOX).value]).toEqual(['', '']);
  });

  it('changing one and clearing the other on a stored draft sends the new value and a null', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ orderedByPhone: PHONE, orderedByEmail: EMAIL }) });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByLabelText(PHONE_LABEL, BOX).value).toBe(PHONE));

    fireEvent.change(screen.getByLabelText(PHONE_LABEL, BOX), { target: { value: '02-123-4567' } });
    fireEvent.change(screen.getByLabelText(EMAIL_LABEL, BOX), { target: { value: '' } });
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const sent = api.dealQuotations.update.mock.calls[0][1];
    expect(sent).toMatchObject({ orderedByPhone: '02-123-4567', orderedByEmail: null, contactId: null });
    // The stored name ('คุณวิชัย มั่นคง') is not sent back either: the server owns it now.
    expect(sent).not.toHaveProperty('orderedByName');
    // No contact before, none now: nothing to clear, so the key is not sent at all.
    expect(sent).not.toHaveProperty('clearContact');
  });
});

describe('ผู้ติดต่อ — the Email is checked before the save, not by it', () => {
  it('a malformed address is flagged under the field and holds the save back; correcting it lets the save through', async () => {
    renderEditor('/quotations/5');
    const email = await screen.findByLabelText(EMAIL_LABEL, BOX);
    await waitFor(() => expect(saveButton().disabled).toBe(false)); // a complete draft, saveable as it stands

    fireEvent.change(email, { target: { value: 'somchai@example' } });
    fireEvent.blur(email);

    expect(describedBy(screen.getByLabelText(EMAIL_LABEL, BOX))).toContain(BAD_EMAIL);
    expect(screen.getByLabelText(EMAIL_LABEL, BOX).getAttribute('aria-invalid')).toBe('true');
    expect(saveButton().disabled).toBe(true);
    expect(saveButton().title).toContain('Email');
    expect(api.dealQuotations.update).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText(EMAIL_LABEL, BOX), { target: { value: 'somchai@example.com' } });
    fireEvent.blur(screen.getByLabelText(EMAIL_LABEL, BOX));

    expect(describedBy(screen.getByLabelText(EMAIL_LABEL, BOX))).not.toContain(BAD_EMAIL);
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    expect(api.dealQuotations.update.mock.calls[0][1].orderedByEmail).toBe('somchai@example.com');
  });

  // Phone is free text — an extension, several numbers — so nothing about its shape is checked.
  it('a Phone is never flagged, whatever its shape', async () => {
    renderEditor('/quotations/5');
    const phone = await screen.findByLabelText(PHONE_LABEL, BOX);
    await waitFor(() => expect(saveButton().disabled).toBe(false));

    fireEvent.change(phone, { target: { value: '02-123-4567 ต่อ 12, 081-234-5678 (คุณวิชัย)' } });
    fireEvent.blur(phone);

    expect(screen.getByLabelText(PHONE_LABEL, BOX).getAttribute('aria-invalid')).not.toBe('true');
    expect(saveButton().disabled).toBe(false);
  });
});

describe('orderedByEmailError — the shape check itself', () => {
  const verdict = (value) => (orderedByEmailError(value) ? 'refused' : 'accepted');

  it('refuses exactly the shapes the server refuses, and accepts an address typed with any case or padding', () => {
    expect(Object.fromEntries([...MALFORMED_EMAILS, ...WELL_FORMED_EMAILS].map((value) => [value, verdict(value)])))
      .toEqual({
        ...Object.fromEntries(MALFORMED_EMAILS.map((value) => [value, 'refused'])),
        ...Object.fromEntries(WELL_FORMED_EMAILS.map((value) => [value, 'accepted'])),
      });
  });

  it('says what is wrong in Thai, in the words the server uses', () => {
    expect(String(orderedByEmailError('somchai@'))).toBe(BAD_EMAIL);
  });

  // REGRESSION GUARD — green before the check exists (a check that accepts everything accepts a
  // blank). It is here for the day the check is tightened: the field is optional.
  it('has nothing to say about an empty field', () => {
    expect(['', '   ', null, undefined].map(verdict)).toEqual(['accepted', 'accepted', 'accepted', 'accepted']);
  });
});

describe('buildQuotationChecklist — a malformed Email is a blocking entry that points at its field', () => {
  const complete = {
    customer: { id: 5, name: CUSTOMER.name, address: 'กรุงเทพฯ', taxId: CUSTOMER.taxId, phone: CUSTOMER.phone },
    projectName: 'โครงการ A', hasProject: true, items: [TILE_ITEM], itemErrorsByRow: [{}],
  };
  const emailEntry = (args) => buildQuotationChecklist({ ...complete, ...args })
    .find((entry) => entry.check === 'orderedByEmail');

  it('blocks, with the field of whichever form is on screen as its target', () => {
    // The box is the CUSTOMER's email (labelled อีเมล), and the entry says so.
    expect(emailEntry({ orderedByEmail: 'somchai@' }))
      .toMatchObject({ blocking: true, targetId: 'orderedByEmailCard', message: `Email ของลูกค้า: ${BAD_EMAIL}` });
    expect(emailEntry({ orderedByEmail: 'somchai@', isInlineCreate: true }))
      .toMatchObject({ blocking: true, targetId: 'orderedByEmailInline' });
    // …and says nothing when there is nothing wrong.
    expect(emailEntry({ orderedByEmail: '' })).toBeUndefined();
    expect(emailEntry({ orderedByEmail: EMAIL })).toBeUndefined();
    expect(emailEntry({})).toBeUndefined();
  });
});
