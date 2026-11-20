import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';
import { orderedByEmailError } from './quotationMeta.js';

globalThis.React = React;

// Whole-editor cases, several of which fill a complete item row and save.
vi.setConfig({ testTimeout: 15000 });

// ผู้ติดต่อ is a picker over the deal customer's contact records
// (.design/quotation-contact/DESIGN.md): the editor sends the picked `contactId`, and the server
// writes the ผู้สั่งซื้อ signature name from that contact, so the editor sends no `orderedByName`.
//
// Under the picker sit the CUSTOMER's phone and email (`orderedByPhone`, `orderedByEmail`),
// labelled โทร. / อีเมล. They print on the document's customer contact line when filled; a blank
// one prints nothing.
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
        displayNameOptions: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), update: vi.fn(), projects: vi.fn(),
        contacts: vi.fn(),
      },
    },
  };
});

const PHONE = '089-765-4321 ต่อ 77';
const EMAIL = 'bid.contact@contractor-b.example';
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

beforeEach(() => {
  vi.clearAllMocks();
  api.tickets.get.mockResolvedValue({
    ticket: {
      summary: {
        id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', customerName: CUSTOMER.name, customerId: 5,
        projectId: 3, projectName: 'โครงการ A', contactId: null, contactName: null,
        // A deal at a quote stage preselects its recipient, so saving is not held back.
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
    // The signature name is the server's to write from the contact: not sent.
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
});

