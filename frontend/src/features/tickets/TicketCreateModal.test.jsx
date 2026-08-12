import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketCreateModal } from './TicketCreateModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// UX-03 (still true after the section-hub rebuild, handoff 107): every
// invalid field gets its own aria-invalid + aria-describedby + inline
// role="alert" message, the first invalid field is focused on submit
// (jumping to whichever hub section owns it), and the submit payload shape
// is unchanged (plus the new entryChannel/priority fields).

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      customers: {
        search: vi.fn(),
        projects: vi.fn(),
        contacts: vi.fn(),
        createProject: vi.fn(),
        create: vi.fn(),
        createContact: vi.fn(),
      },
      catalog: {
        prices: vi.fn(),
      },
      tickets: {
        list: vi.fn(),
      },
      // FX rates back the "≈ x บาท" companion shown beside a catalog price. Fetched via
      // react-query inside the modal, so every render() needs a QueryClientProvider (see
      // renderModal() below). The CEO markup multiplier used to be fetched here too, for the
      // ราคาตั้ง (ประมาณการ) estimate; that whole feature was removed on the owner's instruction
      // after UAT, so `api.dealEstimateMarkup` is deliberately NOT mocked — if the modal ever
      // starts calling it again these tests will fail loudly rather than silently re-enable it.
      fxRates: {
        list: vi.fn(),
      },
    },
  };
});

const mockCustomer = { id: 1, name: 'บริษัท ทดสอบ จำกัด', taxId: null };
const mockProject = { id: 10, name: 'โครงการ A' };

function validItem(overrides = {}) {
  return {
    brand: 'SCG', model: 'Stone', color: 'ขาว', texture: 'ด้าน', size: '60x60',
    factory: '', unitBasis: 'PIECE', qty: 5, qtySqm: '', sqmPerPiece: null,
    source: 'custom', catalogPrice: null, catalogCurrency: null, catalogPriceUnit: null,
    ...overrides,
  };
}

/** A catalog-sourced item carrying a real catalog price, for the price-display tests. */
function catalogItem(overrides = {}) {
  return validItem({
    source: 'catalog',
    catalogPrice: 43,
    catalogCurrency: 'EUR',
    catalogPriceUnit: 'per_sqm',
    unitBasis: 'SQM',
    qtySqm: 10,
    qty: '',
    sqmPerPiece: 0.72,
    ...overrides,
  });
}

// Deliberately NOT fireEvent.submit(form): that dispatches a plain synthetic Event with no
// `.submitter` property at all under jsdom, and SafeForm's canSubmit gate is a RESTRICTION on
// top of its submitter guard, not a replacement for it (#safe-form-primitive review round) -- a
// submitterless dispatch is blocked on EVERY view regardless of canSubmit, which would make every
// "does NOT submit" test below pass for the wrong reason (no submitter in the DOM at all, not
// because the view gate rejected it -- this repo's own documented vacuous-test shape) and every
// "submits" test fail outright. A manually constructed SubmitEvent with an explicit `submitter`
// is picked up by React's onSubmit exactly like a real click (verified), so every call site below
// now exercises `canSubmit` specifically: HUB lets it through, every other view still blocks it
// even though a submitter is present.
function submitForm() {
  const form = document.getElementById('ticket-create-form');
  form.dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true, submitter: document.createElement('button') }));
}

// Anchored to the start of the accessible name: a hub row's name is its
// title + subtitle concatenated (e.g. "โครงการจำเป็นเลือกลูกค้าก่อน"), so an
// unanchored search for "ลูกค้า" would also match the โครงการ row while it's
// waiting on a customer.
function goToSection(name) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${name}`) }));
}

// Every render needs a QueryClientProvider — the modal fetches fxRates via react-query. A fresh
// QueryClient per render keeps tests isolated from each other.
function renderModal(props = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <TicketCreateModal onClose={() => {}} {...props} />
    </QueryClientProvider>,
  );
}

// Drives the real customer/project pickers (not stubbed — this is the exact
// flow the finding is about) via the ลูกค้า then โครงการ hub sections, so
// tests that need a valid customer+project exercise the real SearchSelect +
// pill-button UI. Customer must be picked before project — projects are
// nested under a customer via api.customers.projects(customerId).
async function selectCustomerAndProject() {
  goToSection('ลูกค้า');
  const searchInput = screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…');
  fireEvent.change(searchInput, { target: { value: 'บริษัท' } });
  const option = await screen.findByText(mockCustomer.name);
  fireEvent.mouseDown(option);

  goToSection('กลับ');
  goToSection('โครงการ');
  const projectButton = await screen.findByRole('button', { name: mockProject.name });
  fireEvent.click(projectButton);
  goToSection('กลับ');
}

/** The channel is required and has no default, so most create-path tests must state one. */
function chooseEntryChannel(name = /ผู้ออกแบบนำ/) {
  goToSection('ผู้ติดต่อ & ช่องทางดีล');
  fireEvent.click(screen.getByRole('radio', { name }));
  goToSection('กลับ');
}

beforeEach(() => {
  vi.clearAllMocks();
  api.customers.search.mockResolvedValue({ customers: [mockCustomer] });
  api.customers.projects.mockResolvedValue({ projects: [mockProject] });
  api.customers.contacts.mockResolvedValue({ contacts: [] });
  api.tickets.list.mockResolvedValue({ tickets: [] });
  // FX rates covering the catalogItem() fixture's EUR price. Individual tests override this to
  // exercise the "no rate for this currency" path.
  api.fxRates.list.mockResolvedValue({
    fxRates: [
      { currency: 'THB', rateToThb: 1 },
      { currency: 'EUR', rateToThb: 38.5 },
      { currency: 'USD', rateToThb: 35.2 },
    ],
  });
  // Best-effort: none of these tests exercise the "บันทึกร่าง" save-draft
  // action, so nothing here actually depends on a clean localStorage — this
  // is just hygiene. Guarded because some Node versions ship a broken
  // built-in global `localStorage` (needs --localstorage-file) that this
  // repo's engines range doesn't target; the component's own loadDraft/
  // saveDraft/clearDraft already tolerate that (try/catch, see
  // TicketCreateModal.jsx), so a real browser or CI's pinned Node 22 is
  // unaffected either way.
  try { localStorage.clear(); } catch { /* see comment above */ }
});

describe('TicketCreateModal validation', () => {
  it('marks the customer field invalid when none is selected, and does not submit', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    submitForm();

    const customerInput = await screen.findByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…');
    await waitFor(() => expect(customerInput.getAttribute('aria-invalid')).toBe('true'));
    expect(customerInput.getAttribute('aria-describedby')).toBe('customer-select-error');
    expect(screen.getByText('กรุณาเลือกบริษัท/ลูกค้า')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('marks a specific blank field (ขนาด), not just a generic row message', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit, initialItems: [validItem({ size: '' })] });

    await selectCustomerAndProject();
    // entryChannel now sits earlier than items in on-screen order (ลูกค้า → โครงการ → ผู้ติดต่อ &
    // ช่องทางดีล → รายการสินค้า), so it must be chosen or the failed-submit jump lands on the
    // channel picker instead of this item's ขนาด field — this test would otherwise time out
    // waiting on a placeholder that never mounts.
    chooseEntryChannel();
    submitForm();

    const sizeInput = await screen.findByPlaceholderText('เช่น 600x1200');
    await waitFor(() => expect(sizeInput.getAttribute('aria-invalid')).toBe('true'));
    expect(sizeInput.id).toBe('item-0-size');
    expect(sizeInput.getAttribute('aria-describedby')).toBe('item-0-size-error');
    expect(screen.getByText('กรุณากรอกขนาด')).toBeTruthy();

    // The old generic string must not appear — the whole point of the fix.
    expect(screen.queryByText(/กรุณากรอกข้อมูลสินค้าให้ครบทุกช่องในรายการที่/)).toBeNull();
    // Other fields in the same row stay untouched.
    expect(document.getElementById('item-0-brand').getAttribute('aria-invalid')).toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  // The live price catalog fills `color` on 21% of its active rows and `surface` on 22%, so
  // requiring either made a rep invent a value on most catalog picks. Asserted wrong-way-round:
  // what matters is that a blank สี/เนื้อผิว does NOT block, and that it reaches the payload as
  // null rather than as an empty string someone appears to have typed.
  it('creates a deal with สี and เนื้อผิว left blank, sending them as null', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, initialItems: [validItem({ color: '', texture: '' })] });

    await selectCustomerAndProject();
    chooseEntryChannel();
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items[0]).toMatchObject({ color: null, texture: null });

    goToSection('รายการสินค้า');
    fireEvent.click(screen.getByRole('button', { name: /^แก้ไขรายการที่ 1/ }));
    const colorInput = await screen.findByPlaceholderText('เช่น ขาว, เทา, ครีม');
    expect(colorInput.getAttribute('aria-invalid')).toBeNull();
    expect(colorInput.getAttribute('aria-required')).toBeNull();
    expect(screen.queryByText('กรุณากรอกสี')).toBeNull();
  });

  it('marks the qty field for a PIECE row with qty 0', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit, initialItems: [validItem({ unitBasis: 'PIECE', qty: 0 })] });

    await selectCustomerAndProject();
    // See the ขนาด test above: entryChannel precedes items in on-screen order, so it must be
    // chosen or the failed-submit jump goes to the channel picker instead of this item field.
    chooseEntryChannel();
    submitForm();

    const qtyInput = await screen.findByPlaceholderText('จำนวนแผ่น');
    await waitFor(() => expect(qtyInput.getAttribute('aria-invalid')).toBe('true'));
    expect(qtyInput.id).toBe('item-0-qty');
    expect(qtyInput.getAttribute('aria-describedby')).toBe('item-0-qty-error');
    expect(screen.getByText('กรุณากรอกจำนวน (แผ่น) ในรายการที่ 1')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('marks the qtySqm field for an SQM row with an empty area', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit, initialItems: [validItem({ unitBasis: 'SQM', qtySqm: '' })] });

    await selectCustomerAndProject();
    // See the ขนาด test above: entryChannel precedes items in on-screen order, so it must be
    // chosen or the failed-submit jump goes to the channel picker instead of this item field.
    chooseEntryChannel();
    submitForm();

    const sqmInput = await screen.findByPlaceholderText('เช่น 120.500');
    await waitFor(() => expect(sqmInput.getAttribute('aria-invalid')).toBe('true'));
    expect(sqmInput.id).toBe('item-0-qtySqm');
    expect(sqmInput.getAttribute('aria-describedby')).toBe('item-0-qtySqm-error');
    expect(screen.getByText('กรุณากรอกพื้นที่ (ตร.ม.) ในรายการที่ 1')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits the exact existing payload shape (plus entryChannel/priority) once every field is valid', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, initialItems: [validItem()] });

    await selectCustomerAndProject();
    chooseEntryChannel();
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      title: mockCustomer.name,
      customerName: mockCustomer.name,
      customerId: mockCustomer.id,
      projectId: mockProject.id,
      contactId: null,
      note: null,
      entryChannel: 'DESIGNER_LED',
      priority: 'NORMAL',
      items: [{
        brand: 'SCG',
        model: 'Stone',
        color: 'ขาว',
        texture: 'ด้าน',
        size: '60x60',
        // ยี่ห้อ and โรงงาน are one field now, so factory follows brand rather than staying null.
        factory: 'SCG',
        unitBasis: 'PIECE',
        qty: 5,
        qtySqm: null,
        catalogPriceId: null,
        catalogProductCode: null,
      }],
    });
  });

  it('clears a field error once the user fixes it', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit, initialItems: [validItem({ size: '' })] });

    await selectCustomerAndProject();
    // See the ขนาด test above: entryChannel precedes items in on-screen order, so it must be
    // chosen or the failed-submit jump goes to the channel picker instead of this item field.
    chooseEntryChannel();
    submitForm();

    const sizeInput = await screen.findByPlaceholderText('เช่น 600x1200');
    await waitFor(() => expect(sizeInput.getAttribute('aria-invalid')).toBe('true'));

    fireEvent.change(sizeInput, { target: { value: '600x1200' } });

    expect(sizeInput.getAttribute('aria-invalid')).toBeNull();
    expect(sizeInput.getAttribute('aria-describedby')).toBeNull();
    expect(screen.queryByText('กรุณากรอกขนาด')).toBeNull();
  });

  it('carries the chosen entry channel into the create payload', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    await selectCustomerAndProject();

    goToSection('ผู้ติดต่อ & ช่องทางดีล');
    fireEvent.click(screen.getByRole('radio', { name: /เจ้าของตรง/ }));
    goToSection('กลับ');

    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ entryChannel: 'OWNER_DIRECT', items: [] });
  });

  it('blocks creation until both customer and project are set, but never requires items', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    // Hub's "สร้างดีล" stays disabled with nothing selected yet.
    expect(screen.getByRole('button', { name: /^สร้างดีล/ }).disabled).toBe(true);

    await selectCustomerAndProject();

    // Zero items — creation must still be allowed (items are optional, V50).
    expect(screen.getByRole('button', { name: /^สร้างดีล/ }).disabled).toBe(false);

    chooseEntryChannel();
    submitForm();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items).toEqual([]);
  });

  // The bug this branch fixes: the picker used to arrive pre-selected as DESIGNER_LED, so a rep
  // who never opened this section silently recorded "designer-led". A value was ALWAYS sent, so
  // no backend default could tell that apart from a real choice.
  it('does not submit when the rep never touched the entry-channel picker', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    submitForm();

    await waitFor(() => expect(screen.getByText('กรุณาเลือกช่องทางดีล (ระบุว่าดีลนี้เข้ามาทางไหน)')).toBeTruthy());
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('starts with NO entry channel selected', async () => {
    renderModal({ onSubmit: vi.fn() });
    goToSection('ผู้ติดต่อ & ช่องทางดีล');
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(3);
    // Wrong-way-round: assert none is checked, not that a particular one is.
    expect(radios.filter((r) => r.getAttribute('aria-checked') === 'true')).toHaveLength(0);
  });

  it('wires the entry-channel error to the radiogroup and clears it once a channel is picked', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    submitForm();

    // No explicit goToSection here: entryChannel is the only invalid field once
    // customer+project are set and there are no items, so the failed submit's own
    // jumpToField('entryChannel') (viewForFieldKey → 'contact') already lands us here. Deviation
    // from the plan's literal test code — a goToSection('ผู้ติดต่อ & ช่องทางดีล') call at this point
    // tries to click a hub-row button that is no longer on screen once the jump has fired, and
    // throws "Unable to find role=button" instead of testing anything.
    const group = await screen.findByRole('radiogroup', { name: 'ช่องทางดีล' });
    await waitFor(() => expect(group.getAttribute('aria-invalid')).toBe('true'));
    expect(group.getAttribute('aria-describedby')).toBe('entry-channel-error');

    fireEvent.click(screen.getByRole('radio', { name: /ผู้ซื้อตรง/ }));
    expect(group.getAttribute('aria-invalid')).toBeNull();
    expect(screen.queryByText('กรุณาเลือกช่องทางดีล (ระบุว่าดีลนี้เข้ามาทางไหน)')).toBeNull();
  });

  it('sends exactly the channel the rep picked, not a default', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    chooseEntryChannel(/ผู้ซื้อตรง/);
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].entryChannel).toBe('BUYER_DIRECT');
  });

  // Restoring a draft is NOT the same as creating fresh: a channel the rep already stated must
  // come back selected, or the no-default rule above would quietly discard their answer.
  it('pre-fills the channel from a restored draft', async () => {
    // This file's `beforeEach` notes that some Node versions ship a broken built-in `localStorage`
    // global. That is exactly this env: `typeof localStorage === 'object'` but setItem/getItem are
    // NOT functions, so the component's own loadDraft() try/catch always yields null. A version of
    // this test that merely try/catch-guarded the setItem and bailed out passed even with draft
    // restoration deleted outright (mutation-checked — it asserted nothing). Stubbing a working
    // localStorage is what makes the assertion below real.
    const store = new Map();
    vi.stubGlobal('localStorage', {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => { store.set(k, String(v)); },
      removeItem: (k) => { store.delete(k); },
      clear: () => { store.clear(); },
    });
    try {
      // DRAFT_KEY is a module-private const in TicketCreateModal.jsx (not exported); its real
      // value, read from source, is 'glr:draft-deal'.
      localStorage.setItem('glr:draft-deal', JSON.stringify({ entryChannel: 'OWNER_DIRECT' }));
      renderModal({ onSubmit: vi.fn() });
      goToSection('ผู้ติดต่อ & ช่องทางดีล');
      expect(screen.getByRole('radio', { name: /เจ้าของตรง/ }).getAttribute('aria-checked')).toBe('true');
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

// HTML implicit submission (fix/form-enter-submits-real-records): a <form> with no submit button
// but exactly ONE field that blocks implicit submission fires a real 'submit' event on Enter, no
// button ever pressed. jsdom does not implement that algorithm at all (pressing "Enter" via
// fireEvent does nothing special) -- e2e/implicit-submission.spec.js is the layer that reproduces
// the real thing. What `submitForm()` proves here is narrower and complementary: given a genuine
// 'submit' event WITH a submitter attached (deliberately, not the submitter-less dispatch a real
// Enter-on-a-buttonless-view would actually produce -- see the helper's own comment), does
// SafeForm's `canSubmit` gate on `<TicketCreateModal>`'s form still reject it on every view except
// HUB? A submitter-less dispatch would pass every "does NOT submit" case below for free
// (SafeForm's separate submitter guard would block it regardless of canSubmit), which would prove
// nothing about `canSubmit` specifically -- this repo's own documented vacuous-test shape. Mirrors
// TaxAllowanceForm.test.jsx's "form-level submit gate blocks Enter-key implicit submission"
// describe block. HUB's own positive case (a real submit event with view==='hub') is already
// exercised by every test above that calls `selectCustomerAndProject()` then `submitForm()`
// directly — see `handleFormSubmit`'s own comment in TicketCreateModal.jsx for why HUB, unlike the
// three views below, is deliberately left able to submit.
describe('TicketCreateModal implicit submission (Enter-key) safety', () => {
  it('does not submit a real submit event from the DETAILS view, even with a valid customer+project', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit, initialItems: [validItem()] });

    await selectCustomerAndProject();
    goToSection('รายละเอียดดีล');
    // ชื่อดีล is DETAILS' only Enter-blocking field (renderDetailsView) — the textarea and
    // role="radio" priority chips don't count toward HTML's implicit-submission rule.
    fireEvent.change(screen.getByPlaceholderText(mockCustomer.name), { target: { value: 'ดีลทดสอบ Enter' } });

    submitForm();

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('does not submit a real submit event from the CUSTOMER view before a customer is chosen', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    goToSection('ลูกค้า');
    // SearchSelect's own search input is the CUSTOMER view's only field while nothing is selected.
    fireEvent.change(screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…'), { target: { value: 'บริษัท' } });

    submitForm();

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('does not submit a real submit event from the PROJECT view while creating a new project', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    goToSection('ลูกค้า');
    fireEvent.change(screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…'), { target: { value: 'บริษัท' } });
    fireEvent.mouseDown(await screen.findByText(mockCustomer.name));
    goToSection('กลับ');

    goToSection('โครงการ');
    await screen.findByRole('button', { name: mockProject.name });
    // Once "สร้างโครงการใหม่" is open, ชื่อโครงการ is the PROJECT view's only field —
    // `selectedProject` is still null, so validateTicketForm() would fail regardless, but the
    // implicit-submission gate must refuse to even try before that check ever runs.
    fireEvent.click(screen.getByRole('button', { name: /สร้างโครงการใหม่/ }));
    fireEvent.change(screen.getByPlaceholderText('ชื่อโครงการ'), { target: { value: 'โครงการทดสอบ' } });

    submitForm();

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('does not submit a real submit event from the ITEMS view', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit, initialItems: [validItem()] });

    await selectCustomerAndProject();
    goToSection('รายการสินค้า');

    submitForm();

    expect(onSubmit).not.toHaveBeenCalled();
  });

  // The positive half of the gate. HUB is the ONLY view that may submit now that ตรวจสอบ & บันทึก
  // is gone, so this is what would catch `canSubmit` being narrowed to nothing — which would turn
  // the footer's real สร้างดีล button into a dead click rather than closing any hole.
  it('submits a real submit event dispatched from the HUB view', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, initialItems: [validItem()] });

    await selectCustomerAndProject();
    chooseEntryChannel();

    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });
});

// V110: the catalog product picked here is persisted as catalogPriceId/catalogProductCode so
// PricingRequestCreateModal need not re-search it.
//
// The brand mapping was rewritten on 2026-08-10 after UAT. It used to read the catalog's `grade`
// as the brand; the live catalog shows `grade` holds only 'A01'/'A02' and only for factory Padana
// (9,076 of 22,455 active rows), so that rule wrote a quality code into ยี่ห้อ or, for the other
// 60%, left it blank — reported as "รายการสินค้า doesn't autofill everything". ยี่ห้อ and โรงงาน
// are now one field carrying the factory name, which is what a rep means by the brand here
// (Padana, Vives, LEA, Bode…).
describe('TicketCreateModal catalog picker (catalog link + factory-as-brand mapping)', () => {
  function mockCatalogProduct(overrides = {}) {
    return {
      priceId: 501,
      productCode: 'BNFJ30126CA',
      factoryName: 'Bode',
      grade: null,
      collection: 'Stone gallary',
      productName: null,
      color: null,
      surface: 'MATT',
      sizeRaw: '600x1200',
      price: 8.8,
      currency: 'USD',
      priceUnit: 'per_sqm',
      sqmPerPiece: null,
      ...overrides,
    };
  }

  // Opens the item editor for a fresh row and types into the ยี่ห้อ/โรงงาน field to trigger the
  // (debounced, real-timer) catalog search — the actual user flow, not a shortcut around it, since
  // the mapping under test lives inside applyCatalogItem and is reached only via a real pick.
  async function addItemAndSearchBrand(query) {
    goToSection('รายการสินค้า');
    fireEvent.click(screen.getByRole('button', { name: /^เพิ่มรายการสินค้า/ }));
    const brandInput = await screen.findByPlaceholderText('เช่น Panaria, LEA, Bode');
    fireEvent.change(brandInput, { target: { value: query } });
    return brandInput;
  }

  /** Clicks the dropdown row whose <strong> leaf is exactly the factory name. */
  async function pickFromDropdown(factoryName) {
    const leaf = await screen.findByText(factoryName);
    fireEvent.mouseDown(leaf.parentElement);
  }

  it('fills ยี่ห้อ/โรงงาน from the factory name and carries the catalog link into the payload', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct()] });
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    chooseEntryChannel();
    const brandInput = await addItemAndSearchBrand('Bode');
    await pickFromDropdown('Bode');

    await waitFor(() => expect(brandInput.value).toBe('Bode'));

    fireEvent.click(screen.getByRole('button', { name: /บันทึกรายการ/ }));
    goToSection('กลับ');
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items[0]).toMatchObject({
      brand: 'Bode',
      factory: 'Bode',
      model: 'Stone gallary',
      size: '600x1200',
      texture: 'MATT',
      // The catalog row has no colour — it must stay absent, not be back-filled from the code.
      color: null,
      catalogPriceId: 501,
      catalogProductCode: 'BNFJ30126CA',
    });
  });

  // The regression that motivated the rewrite: a Padana row's grade is 'A01'.
  it('never writes the catalog grade into ยี่ห้อ, showing it as a เกรด badge instead', async () => {
    api.catalog.prices.mockResolvedValue({
      items: [mockCatalogProduct({ factoryName: 'Padana', grade: 'A01', collection: 'Nuances' })],
    });
    renderModal({ onSubmit: vi.fn() });

    await selectCustomerAndProject();
    const brandInput = await addItemAndSearchBrand('Padana');
    await pickFromDropdown('Padana');

    await waitFor(() => expect(brandInput.value).toBe('Padana'));
    expect(brandInput.value).not.toBe('A01');
    expect(screen.getByText('เกรด A01')).toBeTruthy();
  });

  // ยี่ห้อ doubles as the catalog search box, so a partial query left in it after a pick is a
  // QUERY, not a deliberate brand — keeping it would leave junk ("Bod") in the field.
  it('replaces a partial search query with the factory name', async () => {
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct()] });
    renderModal({ onSubmit: vi.fn() });

    await selectCustomerAndProject();
    const brandInput = await addItemAndSearchBrand('Bod');
    await pickFromDropdown('Bode');

    await waitFor(() => expect(brandInput.value).toBe('Bode'));
    expect(brandInput.value).not.toBe('Bod');
  });

  it('clears the catalog link when the user hand-edits a field after a catalog pick', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct()] });
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    chooseEntryChannel();
    const brandInput = await addItemAndSearchBrand('Bode');
    await pickFromDropdown('Bode');
    await waitFor(() => expect(brandInput.value).toBe('Bode'));

    // Hand-edit colour after the pick — the link no longer reliably describes this row.
    fireEvent.change(screen.getByPlaceholderText('เช่น ขาว, เทา, ครีม'), { target: { value: 'เทาเข้ม' } });

    fireEvent.click(screen.getByRole('button', { name: /บันทึกรายการ/ }));
    goToSection('กลับ');
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items[0]).toMatchObject({
      color: 'เทาเข้ม',
      catalogPriceId: null,
      catalogProductCode: null,
    });
  });

  // All 215 Bode rows are priced per_sqm yet carry no sqm_per_piece, which left the แผ่น↔ตร.ม.
  // toggle unable to cross-fill — the stranded-quantity state reported from UAT.
  it('derives ตร.ม./แผ่น from a millimetre size when the catalog has no factor', async () => {
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct({ sqmPerPiece: null })] });
    renderModal({ onSubmit: vi.fn() });

    await selectCustomerAndProject();
    await addItemAndSearchBrand('Bode');
    await pickFromDropdown('Bode');

    // 600mm × 1200mm = 0.72 ตร.ม., and the UI says so is derived rather than catalog fact.
    expect(await screen.findByText(/1 แผ่น = 0.72 ตร.ม./)).toBeTruthy();
    expect(screen.getByText(/คำนวณจากขนาด 600x1200/)).toBeTruthy();

    // Cross-fill now works: 10 แผ่น → 7.200 ตร.ม.
    fireEvent.change(screen.getByPlaceholderText('จำนวนแผ่น'), { target: { value: '10' } });
    expect(screen.getByPlaceholderText('เช่น 120.500').value).toBe('7.200');
  });

  it('leaves BOTH quantity boxes editable when no ตร.ม./แผ่น factor can be established', async () => {
    // A size that cannot be read as millimetres ("598X598X18" carries a thickness) — the catalog
    // has no factor and none can be derived, so neither box may be locked.
    api.catalog.prices.mockResolvedValue({
      items: [mockCatalogProduct({ sizeRaw: '598X598X18', sqmPerPiece: null })],
    });
    renderModal({ onSubmit: vi.fn() });

    await selectCustomerAndProject();
    await addItemAndSearchBrand('Bode');
    await pickFromDropdown('Bode');

    expect(await screen.findByText(/ไม่มีค่า ตร.ม./)).toBeTruthy();
    expect(screen.getByPlaceholderText('จำนวนแผ่น').readOnly).toBe(false);
    expect(screen.getByPlaceholderText('เช่น 120.500').readOnly).toBe(false);
  });
});

// The catalog's own price, shown in the currency it is quoted in with a baht companion. This
// replaced the ราคาตั้ง (ประมาณการ) estimate (catalog price × FX × a CEO markup), removed on the
// owner's instruction after UAT because reps read its output as a selling price. Nothing here
// multiplies by anything except an exchange rate — see catalogPriceDisplay.js.
describe('TicketCreateModal catalog price display', () => {
  it('shows the catalog price in its own currency and in baht, with the price unit', async () => {
    const item = catalogItem({ catalogPrice: 8.8, catalogCurrency: 'USD', catalogPriceUnit: 'per_sqm' });
    renderModal({ onSubmit: vi.fn(), initialItems: [item] });

    goToSection('รายการสินค้า');

    // 8.80 USD/ตร.ม. at 35.20 THB/USD = 309.76 บาท/ตร.ม. — a pure conversion, no markup.
    // The row renders from local state immediately; the baht line waits on the FX query, so it
    // must be awaited rather than asserted on the first paint.
    const row = await screen.findByTestId('item-catalog-price-0');
    expect(within(row).getByText('8.80 USD/ตร.ม.')).toBeTruthy();
    expect(await within(row).findByText('≈ 309.76 บาท/ตร.ม.')).toBeTruthy();
  });

  it('omits the baht line entirely when no FX rate exists for that currency', async () => {
    api.fxRates.list.mockResolvedValue({ fxRates: [{ currency: 'THB', rateToThb: 1 }] });
    const item = catalogItem({ catalogPrice: 8.8, catalogCurrency: 'USD', catalogPriceUnit: 'per_sqm' });
    renderModal({ onSubmit: vi.fn(), initialItems: [item] });

    goToSection('รายการสินค้า');

    const row = await screen.findByTestId('item-catalog-price-0');
    expect(within(row).getByText('8.80 USD/ตร.ม.')).toBeTruthy();
    // Never a 1:1 fallback: 8.80 USD must not appear as "8.80 บาท".
    expect(within(row).queryByText(/บาท/)).toBeNull();
  });

  it('still renders the deal form when the FX fetch fails outright', async () => {
    api.fxRates.list.mockRejectedValue(new Error('fx down'));
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, initialItems: [catalogItem()] });

    await selectCustomerAndProject();
    chooseEntryChannel();
    goToSection('รายการสินค้า');

    const row = await screen.findByTestId('item-catalog-price-0');
    expect(within(row).getByText('43.00 EUR/ตร.ม.')).toBeTruthy();
    expect(within(row).queryByText(/บาท/)).toBeNull();

    // A deal must stay creatable with no prices at all (V50).
    goToSection('กลับ');
    submitForm();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });

  it('shows no ราคาตั้ง (ประมาณการ) figure or total anywhere', async () => {
    renderModal({ onSubmit: vi.fn(), initialItems: [catalogItem(), catalogItem()] });

    goToSection('รายการสินค้า');
    await screen.findByTestId('item-catalog-price-0');

    expect(screen.queryByText(/ราคาตั้ง/)).toBeNull();
    expect(screen.queryByTestId('items-estimate-total')).toBeNull();
    expect(screen.queryByTestId('item-estimate-0')).toBeNull();
  });
});
