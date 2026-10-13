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

// ผู้ติดต่อ gains a Phone and an Email — first a sales record only (owner, 2026-10-06), then
// reversed by the owner on 2026-10-13: they print on the document's customer contact line when
// filled, and a blank one prints nothing.
//
// ผู้ติดต่อ is the editor's name for `orderedByName`, the free text that prints in the document's
// ผู้สั่งซื้อ signature slot. The two new fields sit directly under it and are saved beside it
// (`orderedByPhone`, `orderedByEmail`). They are NOT the customer-master contact (`contactId` and
// its snapshot), which this editor has not collected since 2026-09-26 and still does not.
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

const controls = () => [...document.querySelectorAll('input, select, textarea')];
/** The next form control in reading order. */
const directlyAfter = (control) => controls()[controls().indexOf(control) + 1];
/** The text of whatever the control names as its description (a FormField hint or error line). */
const describedBy = (control) => (control.getAttribute('aria-describedby') ?? '')
  .split(/\s+/).filter(Boolean)
  .map((id) => document.getElementById(id)?.textContent ?? '')
  .join(' ');
const saveButton = () => screen.getByRole('button', { name: 'บันทึกร่าง' });

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

describe('ผู้ติดต่อ — Phone and Email, directly under the name, in both forms', () => {
  it('a quotation for an existing deal: two inputs labelled exactly "Phone" and "Email", next after ผู้ติดต่อ', async () => {
    renderEditor('/quotations/new?ticket=18');

    const name = await screen.findByLabelText(/^ผู้ติดต่อ/);
    const phone = screen.getByLabelText('Phone');
    const email = screen.getByLabelText('Email');

    expect([name.id, phone.id, email.id]).toEqual(['orderedByNameCard', 'orderedByPhoneCard', 'orderedByEmailCard']);
    expect([phone.tagName, email.tagName]).toEqual(['INPUT', 'INPUT']);
    // Directly under: nothing else to fill in comes between the three.
    expect(directlyAfter(name)).toBe(phone);
    expect(directlyAfter(phone)).toBe(email);
    // The server's own limits, so a value it would refuse for length cannot be typed.
    expect([phone.maxLength, email.maxLength]).toEqual([50, 254]);
    // Both start empty, and neither is required.
    expect([phone.value, email.value, phone.required, email.required]).toEqual(['', '', false, false]);
  });

  it('a quotation that creates its deal inline: the same two inputs, in the same place', async () => {
    renderEditor('/quotations/new');

    await screen.findByLabelText(/^ลูกค้า/);
    const name = screen.getByLabelText(/^ผู้ติดต่อ/);
    const phone = screen.getByLabelText('Phone');
    const email = screen.getByLabelText('Email');

    expect([name.id, phone.id, email.id]).toEqual(['orderedByNameInline', 'orderedByPhoneInline', 'orderedByEmailInline']);
    expect(directlyAfter(name)).toBe(phone);
    expect(directlyAfter(phone)).toBe(email);
    expect([phone.maxLength, email.maxLength]).toEqual([50, 254]);
  });

  // The name beside them prints on the document, and so do these two when filled. The line under
  // each says so, so a rep knows what a phone typed on a quotation does.
  it('each says it prints in the quotation when filled — in both forms', async () => {
    const first = renderEditor('/quotations/new?ticket=18');
    await screen.findByLabelText(/^ผู้ติดต่อ/);
    expect(describedBy(screen.getByLabelText('Phone'))).toContain(PRINTED_WHEN_FILLED);
    expect(describedBy(screen.getByLabelText('Phone'))).not.toContain('ไม่พิมพ์');
    expect(describedBy(screen.getByLabelText('Email'))).toContain(PRINTED_WHEN_FILLED);
    first.unmount();

    renderEditor('/quotations/new');
    await screen.findByLabelText(/^ลูกค้า/);
    expect(describedBy(screen.getByLabelText('Phone'))).toContain(PRINTED_WHEN_FILLED);
    expect(describedBy(screen.getByLabelText('Email'))).toContain(PRINTED_WHEN_FILLED);
  });
});

describe('ผู้ติดต่อ — what the editor saves', () => {
  it('sends what was typed as orderedByPhone / orderedByEmail, trimmed — beside the name, never as a contact', async () => {
    renderEditor('/quotations/new?ticket=18');

    fireEvent.change(await screen.findByLabelText(/^ผู้ติดต่อ/), { target: { value: 'คุณวิชัย มั่นคง' } });
    fireEvent.change(screen.getByLabelText('Phone'), { target: { value: `  ${PHONE} ` } });
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: ` ${EMAIL}  ` } });
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.create.mock.calls[0][1]).toMatchObject({
      orderedByName: 'คุณวิชัย มั่นคง', orderedByPhone: PHONE, orderedByEmail: EMAIL, contactId: null,
    });
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

  it('the inline form saves them too, on the quotation it creates with its new deal', async () => {
    renderEditor('/quotations/new');

    fireEvent.change(await screen.findByLabelText(/^ลูกค้า/), { target: { value: 'แฟชั่น' } });
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(CUSTOMER.name) }));
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(CUSTOMER.id));
    fireEvent.focus(screen.getByLabelText(/^โครงการ/));
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(PROJECT.name) }));
    fireEvent.change(screen.getByLabelText('Phone'), { target: { value: PHONE } });
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: EMAIL } });
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.create.mock.calls[0][0]).toBe(42);
    expect(api.dealQuotations.create.mock.calls[0][1]).toMatchObject({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
    // The deal itself is given no contact: these are the quotation's own record.
    expect(api.tickets.create.mock.calls[0][0].contactId).toBeNull();
    expect(JSON.stringify(api.tickets.create.mock.calls[0][0])).not.toContain(EMAIL);
  });

  it('a stored draft opens with its Phone and Email in the boxes; one from before they existed opens with both empty', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ orderedByPhone: PHONE, orderedByEmail: EMAIL }) });
    const withBoth = renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByLabelText('Phone').value).toBe(PHONE));
    expect(screen.getByLabelText('Email').value).toBe(EMAIL);
    withBoth.unmount();

    api.dealQuotations.get.mockResolvedValue({ quotation: draft() }); // no such keys on the DTO
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByLabelText(/^ผู้ติดต่อ/).value).toBe('คุณวิชัย มั่นคง'));
    expect([screen.getByLabelText('Phone').value, screen.getByLabelText('Email').value]).toEqual(['', '']);
  });

  it('changing one and clearing the other on a stored draft sends the new value and a null', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ orderedByPhone: PHONE, orderedByEmail: EMAIL }) });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByLabelText('Phone').value).toBe(PHONE));

    fireEvent.change(screen.getByLabelText('Phone'), { target: { value: '02-123-4567' } });
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: '' } });
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    expect(api.dealQuotations.update.mock.calls[0][1]).toMatchObject({
      orderedByPhone: '02-123-4567', orderedByEmail: null, orderedByName: 'คุณวิชัย มั่นคง',
    });
  });
});

describe('ผู้ติดต่อ — the Email is checked before the save, not by it', () => {
  it('a malformed address is flagged under the field and holds the save back; correcting it lets the save through', async () => {
    renderEditor('/quotations/5');
    const email = await screen.findByLabelText('Email');
    await waitFor(() => expect(saveButton().disabled).toBe(false)); // a complete draft, saveable as it stands

    fireEvent.change(email, { target: { value: 'somchai@example' } });
    fireEvent.blur(email);

    expect(describedBy(screen.getByLabelText('Email'))).toContain(BAD_EMAIL);
    expect(screen.getByLabelText('Email').getAttribute('aria-invalid')).toBe('true');
    expect(saveButton().disabled).toBe(true);
    expect(saveButton().title).toContain('Email');
    expect(api.dealQuotations.update).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'somchai@example.com' } });
    fireEvent.blur(screen.getByLabelText('Email'));

    expect(describedBy(screen.getByLabelText('Email'))).not.toContain(BAD_EMAIL);
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    expect(api.dealQuotations.update.mock.calls[0][1].orderedByEmail).toBe('somchai@example.com');
  });

  // Phone is free text — an extension, several numbers — so nothing about its shape is checked.
  it('a Phone is never flagged, whatever its shape', async () => {
    renderEditor('/quotations/5');
    const phone = await screen.findByLabelText('Phone');
    await waitFor(() => expect(saveButton().disabled).toBe(false));

    fireEvent.change(phone, { target: { value: '02-123-4567 ต่อ 12, 081-234-5678 (คุณวิชัย)' } });
    fireEvent.blur(phone);

    expect(screen.getByLabelText('Phone').getAttribute('aria-invalid')).not.toBe('true');
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
    expect(emailEntry({ orderedByEmail: 'somchai@' }))
      .toMatchObject({ blocking: true, targetId: 'orderedByEmailCard' });
    expect(emailEntry({ orderedByEmail: 'somchai@', isInlineCreate: true }))
      .toMatchObject({ blocking: true, targetId: 'orderedByEmailInline' });
    // …and says nothing when there is nothing wrong.
    expect(emailEntry({ orderedByEmail: '' })).toBeUndefined();
    expect(emailEntry({ orderedByEmail: EMAIL })).toBeUndefined();
    expect(emailEntry({})).toBeUndefined();
  });
});
