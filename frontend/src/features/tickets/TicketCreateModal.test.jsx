import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketCreateModal } from './TicketCreateModal.jsx';
import { api } from '../../api/index.js';
import { listDrafts, loadDraft, saveDraft } from './dealDrafts.js';

globalThis.React = React;

// UX-03: every invalid field gets its own aria-invalid + aria-describedby +
// inline role="alert" message, and the first invalid field is focused on submit
// (opening whichever accordion section owns it).

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      customers: {
        search: vi.fn(),
        projects: vi.fn(),
        createProject: vi.fn(),
        create: vi.fn(),
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
// now exercises `canSubmit` specifically: the one-page form lets it through, the draft picker
// still blocks it even though a submitter is present.
function submitForm() {
  const form = document.getElementById('ticket-create-form');
  form.dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true, submitter: document.createElement('button') }));
}

/** The accordion header button of one section, by section id (customer | project | entryChannel |
 * items | details). Its name also carries the summary + badge, and a project pill can start with
 * "โครงการ" too, so look it up by id, not by name. */
function sectionHeader(id) {
  return document.getElementById(`deal-section-${id}-header`);
}
// Opens an accordion section by id (idempotent — the open section stays open).
function openSection(id) {
  fireEvent.click(sectionHeader(id));
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
// flow the finding is about) via the ลูกค้า then โครงการ accordion sections, so
// tests that need a valid customer+project exercise the real SearchSelect +
// pill-button UI. Customer must be picked before project — projects are
// nested under a customer via api.customers.projects(customerId).
async function selectCustomerAndProject() {
  openSection('customer');
  const searchInput = screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…');
  fireEvent.change(searchInput, { target: { value: 'บริษัท' } });
  const option = await screen.findByText(mockCustomer.name);
  fireEvent.mouseDown(option);

  openSection('project');
  const projectButton = await screen.findByRole('button', { name: mockProject.name });
  fireEvent.click(projectButton);
}

/** The channel is required and has no default, so most create-path tests must state one. */
function chooseEntryChannel(name = /ผู้ออกแบบนำ/) {
  openSection('entryChannel');
  fireEvent.click(screen.getByRole('radio', { name }));
}

/** Real wall-clock wait, for the handful of GLA-20 autosave tests where mixing fake timers with
 * an already-in-flight real one (scheduled by an earlier real-timer interaction in the same test)
 * would be non-deterministic — see those tests' own comments. */
function sleep(ms) {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

/**
 * A real, full Storage implementation (getItem/setItem/removeItem/clear/key/length), not a
 * partial mock. Some Node versions ship a broken built-in global `localStorage` where
 * `typeof localStorage === 'object'` but the methods are not functions at all (needs
 * `--localstorage-file`, which this repo's engines range doesn't target) — a version of the
 * "restored draft" test below that merely try/catch-guarded a broken localStorage passed even with
 * draft restoration deleted outright (mutation-checked — it asserted nothing; see git history).
 * Every draft/autosave test in this file now gets a WORKING store via the beforeEach below, so
 * assertions about what got persisted are real. `.key`/`.length` matter here specifically because
 * dealDrafts.js's listDrafts() rebuild path scans them when the index is missing/corrupt.
 */
function createMemoryStorage() {
  const store = new Map();
  return {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
    removeItem: (k) => { store.delete(k); },
    clear: () => { store.clear(); },
    key: (i) => Array.from(store.keys())[i] ?? null,
    get length() { return store.size; },
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('localStorage', createMemoryStorage());
  api.customers.search.mockResolvedValue({ customers: [mockCustomer] });
  api.customers.projects.mockResolvedValue({ projects: [mockProject] });
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
    // entryChannel sits earlier than items in on-screen order (ลูกค้า → โครงการ → เสนอแก่ →
    // รายการสินค้า), so it must be chosen or the failed-submit jump lands on the
    // channel picker instead of this item's ขนาด field — this test would otherwise time out
    // waiting on a placeholder that never mounts.
    chooseEntryChannel();
    // Walk away to another section first: the jump must close it and open the one owning ขนาด.
    openSection('details');
    // jsdom has no scrollIntoView, so record who scrolls, in order.
    const scrolled = [];
    Element.prototype.scrollIntoView = function recordScroll() { scrolled.push(this.id); };
    try {
      submitForm();
      await waitFor(() => expect(document.activeElement?.id).toBe('item-0-size'));
      // Header first, then the field: the other way round, the header's instant scroll would
      // cancel the field's smooth one and could leave the focused field off screen.
      expect(scrolled).toEqual(['deal-section-items-header', 'item-0-size']);
    } finally {
      delete Element.prototype.scrollIntoView;
    }
    expect(sectionHeader('details').getAttribute('aria-expanded')).toBe('false');

    const sizeInput = screen.getByPlaceholderText('เช่น 60x120');
    expect(sizeInput.getAttribute('aria-invalid')).toBe('true');
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

    openSection('items');
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
      note: null,
      entryChannel: 'DESIGNER_LED',
      priority: 'NORMAL',
      // Defaulted to +14 days at mount, so the value moves with the clock — the shape is what this
      // test pins. It is REQUIRED in the payload: without a next_follow_up_at the stage-advance
      // readiness gate refuses every forward move, which is what made a UI-created deal unmovable.
      nextFollowUpAt: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
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
        // validItem() defaults sourcedFromStock to false, so this pins the "not from stock" payload
        // shape (and that no stockSalePrice is sent at all - walk 4).
        sourcedFromStock: false,
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

    const sizeInput = await screen.findByPlaceholderText('เช่น 60x120');
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

    openSection('entryChannel');
    fireEvent.click(screen.getByRole('radio', { name: /เจ้าของตรง/ }));

    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ entryChannel: 'OWNER_DIRECT', items: [] });
  });

  it('blocks creation until both customer and project are set, but never requires items', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    // "สร้างดีล" stays disabled with nothing selected yet.
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

    await waitFor(() => expect(screen.getByText('กรุณาเลือกว่าดีลนี้เสนอแก่ใคร')).toBeTruthy());
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('starts with NO entry channel selected', async () => {
    renderModal({ onSubmit: vi.fn() });
    openSection('entryChannel');
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(3);
    // Wrong-way-round: assert none is checked, not that a particular one is.
    expect(radios.filter((r) => r.getAttribute('aria-checked') === 'true')).toHaveLength(0);
  });

  it('a failed submit opens เสนอแก่ from another section, focuses its first option and wires the error to the radiogroup', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    // Picking the project opened เสนอแก่, the one required section still missing.
    expect(sectionHeader('entryChannel').getAttribute('aria-expanded')).toBe('true');
    // Walk away first. With customer+project set and no items, the channel is the only failure a
    // real สร้างดีล click can produce (the button is disabled without customer+project, and Enter
    // is swallowed), so this is the reachable form of the jump.
    openSection('details');
    expect(sectionHeader('entryChannel').getAttribute('aria-expanded')).toBe('false');

    submitForm();

    await waitFor(() => expect(sectionHeader('entryChannel').getAttribute('aria-expanded')).toBe('true'));
    expect(sectionHeader('details').getAttribute('aria-expanded')).toBe('false');
    const group = screen.getByRole('radiogroup', { name: 'เสนอแก่' });
    await waitFor(() => expect(document.activeElement).toBe(within(group).getAllByRole('radio')[0]));
    expect(group.getAttribute('aria-invalid')).toBe('true');
    expect(group.getAttribute('aria-describedby')).toBe('entry-channel-error');
    expect(onSubmit).not.toHaveBeenCalled();

    // Picking collapses the section, so re-open it to read the group's state again.
    fireEvent.click(screen.getByRole('radio', { name: /ผู้ซื้อตรง/ }));
    openSection('entryChannel');
    expect(screen.getByRole('radiogroup', { name: 'เสนอแก่' }).getAttribute('aria-invalid')).toBeNull();
  });

  it('a finished section collapses to its value with a tick, and only a still-missing required one opens next', async () => {
    api.customers.search.mockResolvedValue({ customers: [{ ...mockCustomer, taxId: '0105551234567' }] });
    renderModal({ onSubmit: vi.fn() });
    const customerHeader = sectionHeader('customer');
    expect(customerHeader.getAttribute('aria-expanded')).toBe('true');
    expect(customerHeader.textContent).toContain('จำเป็น');

    // Channel first: picking it collapses เสนอแก่ and opens the first required section still
    // missing, which is ลูกค้า.
    chooseEntryChannel();
    expect(sectionHeader('entryChannel').getAttribute('aria-expanded')).toBe('false');
    expect(within(sectionHeader('entryChannel')).getByLabelText('เสร็จแล้ว')).not.toBeNull();
    expect(customerHeader.getAttribute('aria-expanded')).toBe('true');

    fireEvent.change(screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…'), { target: { value: 'บริษัท' } });
    fireEvent.mouseDown(await screen.findByText(mockCustomer.name));

    await waitFor(() => expect(customerHeader.getAttribute('aria-expanded')).toBe('false'));
    expect(customerHeader.textContent).toContain(mockCustomer.name);
    expect(customerHeader.textContent).toContain('เลขภาษี 0105551234567');
    expect(customerHeader.textContent).not.toContain('จำเป็น');
    expect(within(customerHeader).getByLabelText('เสร็จแล้ว')).not.toBeNull();
    expect(sectionHeader('project').getAttribute('aria-expanded')).toBe('true');

    // เสนอแก่ is already done, so picking the project forces nothing open.
    fireEvent.click(await screen.findByRole('button', { name: mockProject.name }));
    expect(sectionHeader('project').getAttribute('aria-expanded')).toBe('false');
    expect(document.querySelectorAll('[id^="deal-section-"][id$="-header"][aria-expanded="true"]')).toHaveLength(0);
  });

  // Picking the project collapses โครงการ, and the duplicate check only answers after that
  // (api.tickets.list resolves later), so the warning cannot live inside the โครงการ body.
  it('shows the duplicate-deal warning while โครงการ is collapsed', async () => {
    api.tickets.list.mockResolvedValue({ tickets: [{ id: 7, code: 'TK-0007', projectId: mockProject.id, status: 'OPEN' }] });
    renderModal({ onSubmit: vi.fn() });

    await selectCustomerAndProject();

    expect((await screen.findByRole('status')).textContent).toMatch(/พบดีลที่ผูกกับโครงการนี้อยู่แล้ว/);
    expect(screen.getByText('TK-0007')).toBeTruthy();
    expect(sectionHeader('project').getAttribute('aria-expanded')).toBe('false');
  });

  // A create request can resolve after the rep has moved on; its auto-advance must judge what is on
  // screen by then, not what was there at click time: a section finished meanwhile is not reopened.
  // (Walk 4: the old Phase 2 - an item editor held open by a จากสต็อก row with no price - went with the
  // sales-typed stock price; leaving the item editor no longer validates anything.)
  it('a project created late does not reopen a section the rep finished meanwhile', async () => {
    const resolveCreate = [];
    api.customers.createProject.mockImplementation(() => new Promise((resolve) => { resolveCreate.push(resolve); }));
    renderModal({ onSubmit: vi.fn(), initialItems: [validItem()] });
    fireEvent.change(screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…'), { target: { value: 'บริษัท' } });
    fireEvent.mouseDown(await screen.findByText(mockCustomer.name));
    async function startProjectCreate(name) {
      fireEvent.click(await screen.findByRole('button', { name: /เพิ่มโครงการใหม่/ }));
      fireEvent.change(screen.getByPlaceholderText('ชื่อโครงการ'), { target: { value: name } });
      fireEvent.click(screen.getByRole('button', { name: 'เพิ่ม' }));
    }

    await startProjectCreate('โครงการใหม่');
    // While the request is in flight: เสนอแก่ is picked, and โครงการ (still missing) opens again.
    chooseEntryChannel();
    expect(sectionHeader('project').getAttribute('aria-expanded')).toBe('true');
    await act(async () => { resolveCreate[0]({ project: { id: 11, name: 'โครงการใหม่' } }); });
    expect(sectionHeader('project').textContent).toContain('โครงการใหม่');
    expect(document.querySelectorAll('[id^="deal-section-"][id$="-header"][aria-expanded="true"]')).toHaveLength(0);
  });

  it('the customer dropdown and the project list both lead with their "+ เพิ่ม…" row', async () => {
    renderModal({ onSubmit: vi.fn() });

    fireEvent.change(screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…'), { target: { value: 'บริษัท' } });
    const customerOption = await screen.findByText(mockCustomer.name);
    const createCustomerRow = screen.getByText('เพิ่มลูกค้าใหม่');
    expect(createCustomerRow.parentElement.contains(customerOption)).toBe(true);
    expect(createCustomerRow.parentElement.firstElementChild).toBe(createCustomerRow);

    fireEvent.mouseDown(customerOption);
    const projectPill = await screen.findByRole('button', { name: mockProject.name });
    const createProjectPill = screen.getByRole('button', { name: /เพิ่มโครงการใหม่/ });
    expect(createProjectPill.parentElement).toBe(projectPill.parentElement);
    expect(createProjectPill.parentElement.firstElementChild).toBe(createProjectPill);
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
  // come back selected, or the no-default rule above would quietly discard their answer. Updated
  // for GLA-19's multi-draft picker: a saved draft no longer auto-restores on open — the modal
  // shows the picker first, and "เปิดต่อ" is what applies it onto the form.
  it('pre-fills the channel from a restored draft, via the draft picker', async () => {
    saveDraft(null, { entryChannel: 'OWNER_DIRECT', dealTitle: 'ดีลค้างไว้' });
    renderModal({ onSubmit: vi.fn() });

    // Picker shows first because a draft exists.
    const restoreButton = await screen.findByRole('button', { name: 'เปิดต่อ' });
    fireEvent.click(restoreButton);

    openSection('entryChannel');
    expect(screen.getByRole('radio', { name: /เจ้าของตรง/ }).getAttribute('aria-checked')).toBe('true');
  });
});

describe('TicketCreateModal draft picker (GLA-19)', () => {
  it('goes straight to a blank form when there are no saved drafts', () => {
    renderModal({ onSubmit: vi.fn() });
    expect(screen.queryByRole('button', { name: 'เปิดต่อ' })).toBeNull();
    expect(screen.getByRole('button', { name: /^ลูกค้า/ })).not.toBeNull();
  });

  it('lists every saved draft, restores the chosen one, and autosaves further changes into its id', async () => {
    const a = saveDraft(null, { dealTitle: 'ดีล A', customer: mockCustomer });
    const b = saveDraft(null, { dealTitle: 'ดีล B', customer: mockCustomer });
    renderModal({ onSubmit: vi.fn() });

    expect(await screen.findByText('ดีล A')).not.toBeNull();
    expect(screen.getByText('ดีล B')).not.toBeNull();
    const restoreButtons = screen.getAllByRole('button', { name: 'เปิดต่อ' });
    expect(restoreButtons).toHaveLength(2);

    // Restore "ดีล B" specifically -- the row order isn't asserted, so find it by its ancestor row.
    const rowB = screen.getByText('ดีล B').closest('div.rounded-xl');
    fireEvent.click(within(rowB).getByRole('button', { name: 'เปิดต่อ' }));

    // The picker is gone; the form shows the restored title.
    expect(screen.queryByText('ดีล A')).toBeNull();
    openSection('details');
    expect(screen.getByDisplayValue('ดีล B')).not.toBeNull();

    // A further edit autosaves into `b`'s id, not a new draft.
    vi.useFakeTimers();
    try {
      fireEvent.change(screen.getByDisplayValue('ดีล B'), { target: { value: 'ดีล B แก้ไข' } });
      await act(async () => { await vi.advanceTimersByTimeAsync(1100); });
    } finally {
      vi.useRealTimers();
    }
    expect(listDrafts()).toHaveLength(2); // still 2, not 3 -- updated in place
    expect(loadDraft(b.id).dealTitle).toBe('ดีล B แก้ไข');
    expect(loadDraft(a.id).dealTitle).toBe('ดีล A'); // untouched
  });

  it('เริ่มดีลใหม่ opens a blank form even when drafts exist', async () => {
    saveDraft(null, { dealTitle: 'ดีลเก่า' });
    renderModal({ onSubmit: vi.fn() });

    fireEvent.click(await screen.findByRole('button', { name: /เริ่มดีลใหม่/ }));
    openSection('details');
    expect(screen.queryByDisplayValue('ดีลเก่า')).toBeNull();
  });

  it('ลบ deletes one draft after confirmation, leaving the other', async () => {
    saveDraft(null, { dealTitle: 'ดีล A' });
    saveDraft(null, { dealTitle: 'ดีล B' });
    renderModal({ onSubmit: vi.fn() });

    await screen.findByText('ดีล A');
    const rowA = screen.getByText('ดีล A').closest('div.rounded-xl');
    fireEvent.click(within(rowA).getByRole('button', { name: /ลบร่าง ดีล A/ }));

    // Confirmation required -- not deleted yet.
    expect(listDrafts()).toHaveLength(2);
    fireEvent.click(await screen.findByRole('button', { name: 'ลบร่าง' }));

    await waitFor(() => expect(listDrafts()).toHaveLength(1));
    expect(screen.queryByText('ดีล A')).toBeNull();
    expect(screen.getByText('ดีล B')).not.toBeNull();
  });
});

// Walk 4 (W4-09, owner ruling 2026-10-29): sales may still flag a deal line "จากสต็อก" (from
// warehouse), but no longer types a selling price for it - the deal shows the CEO-approved price.
// Ticking the box shows no price input, never blocks leaving the item editor or submitting, and the
// create request carries sourcedFromStock with no stockSalePrice.
describe('TicketCreateModal stock-sourced lines (no sales-typed price)', () => {
  it('ticking จากสต็อก shows no price input, and the create request carries sourcedFromStock with no stockSalePrice', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, initialItems: [validItem()] });

    await selectCustomerAndProject();
    chooseEntryChannel();

    openSection('items');
    fireEvent.click(screen.getByRole('button', { name: /^แก้ไขรายการที่ 1/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /จากสต็อก/ }));
    expect(screen.queryByPlaceholderText('เช่น 350.00')).toBeNull();
    expect(screen.queryByText(/ราคาขาย \(สต็อก\)/)).toBeNull();
    // Leaving the editor is never blocked for want of a price.
    fireEvent.click(screen.getByRole('button', { name: 'กลับไปรายการสินค้า' }));
    expect(screen.queryByRole('button', { name: 'กลับไปรายการสินค้า' })).toBeNull();

    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const sent = onSubmit.mock.calls[0][0].items[0];
    expect(sent).toMatchObject({ sourcedFromStock: true });
    expect(sent.stockSalePrice ?? null).toBeNull();
  });

  it('a row restored with sourcedFromStock and a stray price still submits, carrying no price', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, initialItems: [validItem({ sourcedFromStock: true, stockSalePrice: '420.50' })] });

    await selectCustomerAndProject();
    chooseEntryChannel();
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const sent = onSubmit.mock.calls[0][0].items[0];
    expect(sent).toMatchObject({ sourcedFromStock: true });
    expect(sent.stockSalePrice ?? null).toBeNull();
  });

  it('unticking จากสต็อก sends sourcedFromStock:false and no price', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, initialItems: [validItem()] });

    openSection('items');
    fireEvent.click(screen.getByRole('button', { name: /^แก้ไขรายการที่ 1/ }));
    const checkbox = screen.getByRole('checkbox', { name: /จากสต็อก/ });
    fireEvent.click(checkbox);
    fireEvent.click(checkbox);
    fireEvent.click(screen.getByRole('button', { name: 'กลับไปรายการสินค้า' }));

    await selectCustomerAndProject();
    chooseEntryChannel();
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const sent = onSubmit.mock.calls[0][0].items[0];
    expect(sent).toMatchObject({ sourcedFromStock: false });
    expect(sent.stockSalePrice ?? null).toBeNull();
  });
});

// HTML implicit submission: Enter in a text input clicks the form's default button — here the
// footer's real สร้างดีล — and would file a deal nobody asked for. The guard is a form-level keydown
// that swallows Enter on <input>s and must leave every other control's keyboard use alone.
describe('TicketCreateModal implicit submission (Enter-key) safety', () => {
  it('swallows Enter in text inputs only — the textarea, section headers and เสนอแก่ radios still work', async () => {
    renderModal({ onSubmit: vi.fn(), initialItems: [validItem()] });
    const entryChannelSummary = (label) => () => sectionHeader('entryChannel').textContent.includes(label);
    const rows = [
      // [target, key, expected, reach it, what a button's click must do]
      ['ลูกค้า search', 'Enter', 'swallowed', () => screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…')],
      ['ชื่อโครงการ', 'Enter', 'swallowed', async () => {
        fireEvent.change(screen.getByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…'), { target: { value: 'บริษัท' } });
        fireEvent.mouseDown(await screen.findByText(mockCustomer.name));
        fireEvent.click(await screen.findByRole('button', { name: /เพิ่มโครงการใหม่/ }));
        return screen.getByPlaceholderText('ชื่อโครงการ');
      }],
      ['เสนอแก่ header', 'Enter', 'works', () => sectionHeader('entryChannel'),
        () => sectionHeader('entryChannel').getAttribute('aria-expanded') === 'true'],
      ['เสนอแก่ radio', 'Enter', 'works', () => { openSection('entryChannel'); return screen.getByRole('radio', { name: /ผู้ออกแบบนำ/ }); },
        entryChannelSummary('ผู้ออกแบบนำ')],
      ['เสนอแก่ radio', ' ', 'works', () => { openSection('entryChannel'); return screen.getByRole('radio', { name: /เจ้าของตรง/ }); },
        entryChannelSummary('เจ้าของตรง')],
      ['ชื่อดีล', 'Enter', 'swallowed', () => { openSection('details'); return screen.getByPlaceholderText(mockCustomer.name); }],
      // A keydown left alone on the textarea is its newline.
      ['หมายเหตุ', 'Enter', 'works', () => screen.getByPlaceholderText('ข้อมูลเพิ่มเติม (ถ้ามี)')],
      ['item editor ขนาด', 'Enter', 'swallowed', () => {
        openSection('items');
        fireEvent.click(screen.getByRole('button', { name: /^แก้ไขรายการที่ 1/ }));
        return screen.getByPlaceholderText('เช่น 60x120');
      }],
    ];

    const observed = [];
    for (const [target, key, , reach, clickDoes] of rows) {
      const el = await reach();
      // fireEvent returns false when a handler called preventDefault. jsdom performs no default
      // actions, so a button's keyboard activation is done here, as a browser would.
      const kept = fireEvent.keyDown(el, { key });
      if (kept && el.tagName === 'BUTTON') fireEvent.click(el);
      observed.push([target, key, !kept ? 'swallowed' : (!clickDoes || clickDoes()) ? 'works' : 'broken']);
    }
    expect(observed).toEqual(rows.map(([target, key, expected]) => [target, key, expected]));
  });

  // The positive half of the gate: the footer's real สร้างดีล button still submits — this is what
  // would catch `canSubmit` being narrowed to nothing.
  it('submits a real submit event from the สร้างดีล button', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, initialItems: [validItem()] });

    await selectCustomerAndProject();
    chooseEntryChannel();

    fireEvent.click(screen.getByTestId('ticket-create-submit'));

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
    openSection('items');
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
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items[0]).toMatchObject({
      color: 'เทาเข้ม',
      catalogPriceId: null,
      catalogProductCode: null,
    });
  });

  // Review round 2: onFocusSearch used to call the SAME mutating path a real keystroke does
  // (onBrandInput/onModelInput -> updateItem), so merely re-focusing an already-picked ยี่ห้อ/รุ่น
  // box — no retyping, value unchanged — silently re-ran the brand->factory lockstep and cleared
  // the catalog link, exactly as if the rep had hand-edited it. Proved with a real pick then a
  // real focus event, not a shortcut around either.
  it('re-focusing ยี่ห้อ/รุ่น after a catalog pick does NOT clear the catalog link (search-only focus, no row mutation)', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct()] });
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    chooseEntryChannel();
    const brandInput = await addItemAndSearchBrand('Bode');
    await pickFromDropdown('Bode');
    await waitFor(() => expect(brandInput.value).toBe('Bode'));

    // Re-focus both fields with no keystroke in between — simulates tabbing back through the row.
    const modelInput = screen.getByPlaceholderText('เช่น Stone Villa, Eco stone');
    fireEvent.focus(brandInput);
    fireEvent.focus(modelInput);

    fireEvent.click(screen.getByRole('button', { name: /บันทึกรายการ/ }));
    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items[0]).toMatchObject({
      brand: 'Bode',
      factory: 'Bode',
      catalogPriceId: 501,
      catalogProductCode: 'BNFJ30126CA',
    });
  });

  // Owner ruling 2026-09-12 ("แก้ด้วย — ใช้ catalog เหมือนกัน"): resolves ตร.ม./แผ่น the same way
  // the quotation item editor does (resolveTileSqmPerPiece) — the catalog's own sqm_per_piece,
  // nothing guessed from the free-text size.
  it('fills ตร.ม./แผ่น straight from the catalog row when one is present', async () => {
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct({ sqmPerPiece: 0.72 })] });
    renderModal({ onSubmit: vi.fn() });

    await selectCustomerAndProject();
    await addItemAndSearchBrand('Bode');
    await pickFromDropdown('Bode');

    expect(await screen.findByText(/1 แผ่น = 0.72 ตร.ม./)).toBeTruthy();

    // Cross-fill works from the catalog figure: 10 แผ่น → 7.200 ตร.ม.
    fireEvent.change(screen.getByPlaceholderText('จำนวนแผ่น'), { target: { value: '10' } });
    expect(screen.getByPlaceholderText('เช่น 120.500').value).toBe('7.200');
  });

  // REGRESSION (owner ruling 2026-09-12, "2) ไม่มีค่อยคำนวนเอง"): this modal used to carry its own
  // deriveSqmPerPiece(sizeRaw), which read "600x1200" as MILLIMETRES (both dimensions >= 100) and
  // silently resolved 0.72 ตร.ม./แผ่น. All 215 Bode rows are priced per_sqm yet carry no
  // sqm_per_piece of their own, which is exactly the shape that used to trigger the guess. The
  // owner's ruling forbids inferring a unit from the free-text size at all: with no catalog
  // sqm_per_piece, nothing may be invented, so the rep enters both quantities by hand instead —
  // the SAME "no factor" state as any other catalog row with nothing to resolve from.
  it('never derives ตร.ม./แผ่น from the size string, even one the deleted heuristic read as millimetres', async () => {
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct({ sqmPerPiece: null })] });
    renderModal({ onSubmit: vi.fn() });

    await selectCustomerAndProject();
    await addItemAndSearchBrand('Bode');
    await pickFromDropdown('Bode');

    expect(await screen.findByText(/ไม่มีค่า ตร.ม./)).toBeTruthy();
    expect(screen.queryByText(/คำนวณจากขนาด/)).toBeNull();
    expect(screen.getByPlaceholderText('จำนวนแผ่น').readOnly).toBe(false);
    expect(screen.getByPlaceholderText('เช่น 120.500').readOnly).toBe(false);
  });

  it('leaves BOTH quantity boxes editable when no ตร.ม./แผ่น factor can be established', async () => {
    // A size string that carries a thickness suffix ("598X598X18") -- the catalog has no factor
    // and none is derived, so neither box may be locked.
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

  // V153: a per_linear_m row's own sqm_per_piece is LINEAR METRES per piece, not area (the same
  // rule QuotationItemRow's resolveTileSqmPerPiece pins) -- reading it as ตร.ม./แผ่น here would be
  // 14x off, so it must resolve to "no factor" exactly like an absent one.
  it('never reads a per_linear_m catalog row\'s sqm_per_piece as an area', async () => {
    api.catalog.prices.mockResolvedValue({
      items: [mockCatalogProduct({ priceUnit: 'per_linear_m', sqmPerPiece: 0.36 })],
    });
    renderModal({ onSubmit: vi.fn() });

    await selectCustomerAndProject();
    await addItemAndSearchBrand('Bode');
    await pickFromDropdown('Bode');

    expect(await screen.findByText(/ไม่มีค่า ตร.ม./)).toBeTruthy();
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

    openSection('items');

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

    openSection('items');

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
    openSection('items');

    const row = await screen.findByTestId('item-catalog-price-0');
    expect(within(row).getByText('43.00 EUR/ตร.ม.')).toBeTruthy();
    expect(within(row).queryByText(/บาท/)).toBeNull();

    // A deal must stay creatable with no prices at all (V50).
    submitForm();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });

  it('shows no ราคาตั้ง (ประมาณการ) figure or total anywhere', async () => {
    renderModal({ onSubmit: vi.fn(), initialItems: [catalogItem(), catalogItem()] });

    openSection('items');
    await screen.findByTestId('item-catalog-price-0');

    expect(screen.queryByText(/ราคาตั้ง/)).toBeNull();
    expect(screen.queryByTestId('items-estimate-total')).toBeNull();
    expect(screen.queryByTestId('item-estimate-0')).toBeNull();
  });
});

describe('TicketCreateModal autosave debounce (GLA-20)', () => {
  it('does not write before 1000ms, and coalesces rapid edits into exactly one write', () => {
    renderModal({ onSubmit: vi.fn() });
    openSection('details');
    const titleInput = screen.getByPlaceholderText('ชื่อดีล'); // no customer selected -> fallback placeholder

    vi.useFakeTimers();
    try {
      fireEvent.change(titleInput, { target: { value: 'ก' } });
      act(() => { vi.advanceTimersByTime(400); });
      expect(listDrafts()).toHaveLength(0);

      // A second edit inside the debounce window resets it -- this is the "coalesce" half.
      fireEvent.change(titleInput, { target: { value: 'ก ข' } });
      act(() => { vi.advanceTimersByTime(400); }); // only 400ms since THIS edit
      expect(listDrafts()).toHaveLength(0);

      act(() => { vi.advanceTimersByTime(650); }); // now ~1050ms since the latest edit
      expect(listDrafts()).toHaveLength(1);
      expect(listDrafts()[0].label).toBe('ก ข'); // the coalesced, final value -- not the first keystroke
    } finally {
      vi.useRealTimers();
    }
  });

  it('never autosaves a form that only carries its own defaults', () => {
    renderModal({ onSubmit: vi.fn() });
    vi.useFakeTimers();
    try {
      act(() => { vi.advanceTimersByTime(5000); }); // priority=NORMAL, nextFollowUpAt default -- neither counts
      expect(listDrafts()).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('บันทึกร่าง saves immediately, without waiting for the debounce', () => {
    renderModal({ onSubmit: vi.fn() });
    openSection('details');
    fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลด่วน' } });

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    expect(listDrafts()).toHaveLength(1);
    expect(listDrafts()[0].label).toBe('ดีลด่วน');
  });
});

describe('TicketCreateModal beforeunload guard (GLA-20)', () => {
  it('registers a beforeunload listener on mount and removes the SAME handler on unmount', () => {
    const addSpy = vi.spyOn(window, 'addEventListener');
    const removeSpy = vi.spyOn(window, 'removeEventListener');
    try {
      const { unmount } = renderModal({ onSubmit: vi.fn() });
      const registered = addSpy.mock.calls.find(([evt]) => evt === 'beforeunload');
      expect(registered).toBeTruthy();

      unmount();
      const removed = removeSpy.mock.calls.find(([evt, fn]) => evt === 'beforeunload' && fn === registered[1]);
      expect(removed).toBeTruthy();
    } finally {
      addSpy.mockRestore();
      removeSpy.mockRestore();
    }
  });

  it('does not warn when the form has no meaningful data', () => {
    const addSpy = vi.spyOn(window, 'addEventListener');
    try {
      renderModal({ onSubmit: vi.fn() });
      const handler = addSpy.mock.calls.find(([evt]) => evt === 'beforeunload')[1];
      const event = { preventDefault: vi.fn(), returnValue: '' };
      handler(event);
      expect(event.preventDefault).not.toHaveBeenCalled();
    } finally {
      addSpy.mockRestore();
    }
  });

  it('flushes synchronously and does NOT preventDefault when the flush succeeds', () => {
    const addSpy = vi.spyOn(window, 'addEventListener');
    try {
      renderModal({ onSubmit: vi.fn() });
      openSection('details');
      fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลค้างไว้' } });

      const handler = addSpy.mock.calls.find(([evt]) => evt === 'beforeunload')[1];
      const event = { preventDefault: vi.fn(), returnValue: '' };
      handler(event); // localStorage works in this test -- the synchronous flush inside it succeeds

      expect(event.preventDefault).not.toHaveBeenCalled();
      expect(listDrafts()).toHaveLength(1); // the flush actually landed
    } finally {
      addSpy.mockRestore();
    }
  });

  it('preventDefault is called only when the synchronous flush itself fails', () => {
    const addSpy = vi.spyOn(window, 'addEventListener');
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('nope'); },
      setItem: () => { throw new Error('nope'); },
      removeItem: () => { throw new Error('nope'); },
      clear: () => {},
      key: () => null,
      length: 0,
    });
    try {
      renderModal({ onSubmit: vi.fn() });
      openSection('details');
      fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลค้างไว้' } });

      const handler = addSpy.mock.calls.find(([evt]) => evt === 'beforeunload')[1];
      const event = { preventDefault: vi.fn(), returnValue: '' };
      handler(event);

      expect(event.preventDefault).toHaveBeenCalled();
      expect(event.returnValue).toBe('');
    } finally {
      addSpy.mockRestore();
      vi.stubGlobal('localStorage', createMemoryStorage());
    }
  });
});

describe('TicketCreateModal submit + autosave interaction (GLA-20)', () => {
  // Real timers throughout (not fake): the customer/project/channel selection just before this
  // schedules a REAL setTimeout via the component's own debounce effect, and vi.useFakeTimers()
  // does not retroactively adopt an already-scheduled real timer -- mixing the two here would make
  // the "does it resurrect" assertion depend on incidental wall-clock timing rather than on the
  // guard actually under test. See dealDrafts.test.js for the pure-storage-layer coverage of the
  // same guarantee and this file's other GLA-20 describe blocks for the fake-timer-only cases.
  it('deletes the current draft on submit success, and a still-pending autosave timer cannot resurrect it', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    chooseEntryChannel();
    // Let the autosave from customer+project+channel land, so there is a real persisted draft to
    // verify gets deleted below (not just an absence of one).
    await act(async () => { await sleep(1100); });
    expect(listDrafts()).toHaveLength(1);

    // A late edit right before submitting -- its own debounce timer is still pending (not yet
    // 1000ms old) at the moment submit() runs.
    openSection('details');
    fireEvent.change(screen.getByPlaceholderText(mockCustomer.name), { target: { value: 'แก้ไขล่าสุด' } });

    submitForm();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(listDrafts()).toHaveLength(0)); // deleted on success

    // Wait past where the late edit's own pending timer would have fired, had submit() not
    // cancelled it. (React's own effect-cleanup, via the debounce effect's `loading` dependency,
    // ALSO cancels this specific timer once `setLoading(true)` commits — so on its own this
    // real-clock wait does not isolate submit()'s explicit guard. The next test forces the exact
    // race directly.)
    await act(async () => { await sleep(1300); });
    expect(listDrafts()).toHaveLength(0);
  }, 10000);

  it('a stale debounce callback invoked directly after submit success cannot resurrect the draft', async () => {
    // React's own effect cleanup (debounce effect depends on `loading`, which submit() flips
    // synchronously) already cancels a pending timer in the ordinary case -- so waiting out the
    // clock (previous test) cannot, by itself, prove submit()'s OWN guard does anything. This test
    // isolates that guard deterministically: capture the exact callback the debounce effect hands
    // to setTimeout, let submit() succeed, then invoke that captured callback directly -- exactly
    // what a timer would do if it fired in the tightest possible race, a millisecond before
    // React's cleanup runs. Mutation-check target: remove `submittedRef.current = true` in
    // submit()'s success branch (frontend/src/features/tickets/TicketCreateModal.jsx) and this
    // goes red -- saveDraft(null, ...) recreates the just-deleted draft under a fresh id.
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    chooseEntryChannel();

    const setTimeoutSpy = vi.spyOn(window, 'setTimeout');
    try {
      openSection('details');
      fireEvent.change(screen.getByPlaceholderText(mockCustomer.name), { target: { value: 'แก้ไขล่าสุด' } });
      const scheduled = setTimeoutSpy.mock.calls.filter(([, ms]) => ms === 1000).at(-1);
      expect(scheduled).toBeTruthy();
      const staleCallback = scheduled[0];

      submitForm();
      await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(listDrafts()).toHaveLength(0));

      staleCallback(); // the "late timer" itself -- must be a no-op post-success
      expect(listDrafts()).toHaveLength(0);
    } finally {
      setTimeoutSpy.mockRestore();
    }
  });

  it('keeps the draft when submit fails', async () => {
    const onSubmit = vi.fn().mockRejectedValue(new Error('สร้างคำขอราคาไม่สำเร็จ'));
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    chooseEntryChannel();
    await act(async () => { await sleep(1100); });
    expect(listDrafts()).toHaveLength(1);

    submitForm();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    await screen.findByText('สร้างคำขอราคาไม่สำเร็จ');

    expect(listDrafts()).toHaveLength(1); // still there -- submit() only deletes on success
  }, 10000);

  // Review fix: onSubmit succeeding does not itself unmount the modal (that is the caller's own
  // choice -- e.g. QuotationEditorPage's inline-deal-creation flow keeps going after tickets.create
  // succeeds). Before this fix, handleRequestClose ignored submittedRef entirely, so Escape/close
  // right after a successful submit read the still-populated form fields as "meaningful data" and
  // wrote a brand-new draft OF THE DEAL THAT WAS JUST CREATED.
  it('submit success then Escape, with the modal still mounted, closes cleanly without writing a new draft', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined); // never unmounts the tree itself
    const onClose = vi.fn();
    renderModal({ onSubmit, onClose });

    await selectCustomerAndProject();
    chooseEntryChannel();
    submitForm();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull(); // no confirm shown either
    expect(listDrafts()).toHaveLength(0);
  });
});

describe('TicketCreateModal restore baseline (review fix, GLA-19/20)', () => {
  it('เปิดต่อ with no further edits closes immediately with no confirm, and does not delete or resave the draft', () => {
    const seeded = saveDraft(null, { dealTitle: 'ดีลค้างไว้', customer: mockCustomer });
    const onClose = vi.fn();
    renderModal({ onSubmit: vi.fn(), onClose });

    fireEvent.click(screen.getByRole('button', { name: 'เปิดต่อ' }));
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull(); // no confirm -- nothing changed
    expect(onClose).toHaveBeenCalledTimes(1);
    // Still there, untouched -- same savedAt as when it was seeded (not deleted, not re-saved).
    expect(loadDraft(seeded.id)).not.toBeNull();
    expect(loadDraft(seeded.id).savedAt).toBe(seeded.savedAt);
  });

  it('เปิดต่อ does not itself schedule an autosave when nothing is edited afterwards', () => {
    const seeded = saveDraft(null, { dealTitle: 'ดีลค้างไว้' });
    renderModal({ onSubmit: vi.fn() });

    // Fake timers BEFORE the เปิดต่อ click -- the debounce effect's (would-be) setTimeout must be
    // scheduled against the fake clock, or advancing it below can never touch a real one. Review
    // fix: this used to call useFakeTimers() AFTER the click, so a real timer -- had the baseline
    // fix from the previous review round been reverted -- would have been immune to the advance
    // below, making this test pass regardless of whether the bug it targets was present.
    vi.useFakeTimers();
    try {
      fireEvent.click(screen.getByRole('button', { name: 'เปิดต่อ' }));
      act(() => { vi.advanceTimersByTime(5000); }); // well past the 1000ms debounce window
    } finally {
      vi.useRealTimers();
    }
    // Still exactly the one draft, and its savedAt is UNCHANGED from what it was seeded with -- an
    // autosave firing here would still leave count=1 but WOULD rewrite savedAt (reordering the
    // picker), so this is the assertion that actually distinguishes "no timer ran" from "a no-op
    // timer ran".
    expect(listDrafts()).toHaveLength(1);
    expect(loadDraft(seeded.id).savedAt).toBe(seeded.savedAt);
  });

  it('เปิดต่อ then an actual edit autosaves normally (baseline only suppresses UNCHANGED restores)', () => {
    const seeded = saveDraft(null, { dealTitle: 'ดีลค้างไว้' });
    renderModal({ onSubmit: vi.fn() });

    fireEvent.click(screen.getByRole('button', { name: 'เปิดต่อ' }));
    openSection('details');

    // Fake timers BEFORE the edit -- the debounce effect's setTimeout must be scheduled against
    // the fake clock, or advancing it below never fires the (still-real) pending timer.
    vi.useFakeTimers();
    try {
      fireEvent.change(screen.getByDisplayValue('ดีลค้างไว้'), { target: { value: 'ดีลค้างไว้ แก้ไข' } });
      act(() => { vi.advanceTimersByTime(1100); });
    } finally {
      vi.useRealTimers();
    }
    expect(listDrafts()).toHaveLength(1); // same draft, updated in place
    expect(loadDraft(seeded.id).dealTitle).toBe('ดีลค้างไว้ แก้ไข');
  });
});

describe('TicketCreateModal close confirmation (GLA-20)', () => {
  it('closes immediately with no meaningful data typed, no confirm shown', () => {
    const onClose = vi.fn();
    renderModal({ onSubmit: vi.fn(), onClose });
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));
    expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('asks for confirmation when closing with meaningful data, flushing a save first', () => {
    const onClose = vi.fn();
    renderModal({ onSubmit: vi.fn(), onClose });
    openSection('details');
    fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลค้างไว้' } });

    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByText('ยังไม่ได้สร้างดีล')).not.toBeNull();
    expect(listDrafts()).toHaveLength(1); // the close-time flush already landed
  });

  it('เก็บร่างไว้ closes and keeps the draft', () => {
    const onClose = vi.fn();
    renderModal({ onSubmit: vi.fn(), onClose });
    openSection('details');
    fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลค้างไว้' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    fireEvent.click(screen.getByRole('button', { name: 'เก็บร่างไว้' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(listDrafts()).toHaveLength(1);
  });

  it('ทิ้งร่างนี้ deletes the draft and closes', () => {
    const onClose = vi.fn();
    renderModal({ onSubmit: vi.fn(), onClose });
    openSection('details');
    fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลค้างไว้' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    fireEvent.click(screen.getByRole('button', { name: /ทิ้งร่างนี้ไปเลย/ }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(listDrafts()).toHaveLength(0);
  });

  it('กลับไปแก้ต่อ dismisses the confirm and keeps the modal open, untouched', () => {
    const onClose = vi.fn();
    renderModal({ onSubmit: vi.fn(), onClose });
    openSection('details');
    fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลค้างไว้' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    fireEvent.click(screen.getByRole('button', { name: 'กลับไปแก้ต่อ' }));

    expect(onClose).not.toHaveBeenCalled();
    expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull();
    // The modal itself, not just the confirm dialog, is still open.
    expect(screen.getByRole('button', { name: /^ลูกค้า/ })).not.toBeNull();
  });

  it('an empty draft (restored, then cleared back out) is deleted on close instead of left behind', () => {
    const seeded = saveDraft(null, { dealTitle: 'ดีลจะลบ' });
    const onClose = vi.fn();
    renderModal({ onSubmit: vi.fn(), onClose });

    fireEvent.click(screen.getByRole('button', { name: 'เปิดต่อ' }));
    openSection('details');
    fireEvent.change(screen.getByDisplayValue('ดีลจะลบ'), { target: { value: '' } }); // back to blank

    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull(); // nothing meaningful -- no confirm needed
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(loadDraft(seeded.id)).toBeNull();
  });

  // Review fix: ConfirmDialog.jsx's own `open` effect always focuses `confirmButtonRef`
  // unconditionally (confirmed by reading it -- no initial-focus prop exists), so whichever label
  // is bound to `confirmLabel` gets focus. Before this fix `confirmLabel` was ALWAYS the
  // destructive "ปิดโดยไม่บันทึก" whenever the flush failed, so a bare Enter on an unsaveable
  // close lost the rep's typed data. jsdom does not implement the browser's native "Enter
  // activates the focused <button>" behaviour (no fireEvent for it), so this asserts the two
  // halves of that behaviour directly: the safe action IS the focused element, and activating
  // the currently-focused element keeps the modal open rather than discarding anything.
  it('when the flush fails, the SAFE action is focused (not the destructive one) and stays open', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('nope'); },
      setItem: () => { throw new Error('nope'); },
      removeItem: () => { throw new Error('nope'); },
      clear: () => {},
      key: () => null,
      length: 0,
    });
    try {
      const onClose = vi.fn();
      renderModal({ onSubmit: vi.fn(), onClose });
      openSection('details');
      fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลค้างไว้' } });

      fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

      const safeButton = screen.getByRole('button', { name: 'กลับไปแก้ต่อ' });
      const destructiveButton = screen.getByRole('button', { name: 'ปิดโดยไม่บันทึก' });
      expect(document.activeElement).toBe(safeButton);
      expect(document.activeElement).not.toBe(destructiveButton);

      // Activating the focused element (what a real browser's Enter does for a <button>) stays open.
      fireEvent.click(document.activeElement);
      expect(onClose).not.toHaveBeenCalled();
      expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull();
    } finally {
      vi.stubGlobal('localStorage', createMemoryStorage());
    }
  });

  // Opus final-final review: every other test in this describe block that reaches the
  // closeFlushFailed dialog either dismisses it (Escape/backdrop/กลับไปแก้ต่อ) or checks focus --
  // none of them actually CLICK "ปิดโดยไม่บันทึก" itself. Reviewer proved that gap by neutering the
  // button's handler (swapping it for the same no-op as กลับไปแก้ต่อ) and watching all 66 tests in
  // this file stay green. This is the missing other half: the destructive button, actually clicked,
  // must still close the modal and must not resurrect/keep the draft it was trying (and failing) to
  // persist.
  it('ปิดโดยไม่บันทึก (clicked, not just focused) closes the modal and does not keep the draft', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('nope'); },
      setItem: () => { throw new Error('nope'); },
      removeItem: () => { throw new Error('nope'); },
      clear: () => {},
      key: () => null,
      length: 0,
    });
    try {
      const onClose = vi.fn();
      renderModal({ onSubmit: vi.fn(), onClose });
      openSection('details');
      fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลค้างไว้' } });
      fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

      fireEvent.click(screen.getByRole('button', { name: 'ปิดโดยไม่บันทึก' }));

      expect(onClose).toHaveBeenCalledTimes(1);
      expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull();
      // localStorage was broken throughout, so there is nothing to have persisted -- but the
      // point of this test is that the button's own onClick ran handleCloseWithoutSaving (which
      // sets submittedRef and calls onClose), not that it was neutered into a no-op like
      // กลับไปแก้ต่อ's handler.
    } finally {
      vi.stubGlobal('localStorage', createMemoryStorage());
    }
  });

  // Regression probes (Opus final review): in the closeFlushFailed branch, cancelLabel is bound
  // to the DESTRUCTIVE "ปิดโดยไม่บันทึก" action. Before onDismiss existed, ConfirmDialog wired its
  // Modal's onClose to that same onCancel unconditionally, so Escape/backdrop/header-✕ -- which
  // Modal treats as "just dismiss", not "press the labeled cancel button" -- silently discarded
  // the unsaved draft instead of just closing the confirm. Reachable via the at-cap scenario alone
  // (no broken storage needed): saving at MAX_DRAFTS returns reason 'limit', which is still a
  // "flush failed" case.
  it('regression probe: Escape on the closeFlushFailed dialog dismisses only the confirm, never discards', () => {
    for (let i = 0; i < 10; i += 1) saveDraft(null, { dealTitle: `ดีลเดิม ${i}` });
    vi.useFakeTimers();
    try {
      const onClose = vi.fn();
      renderModal({ onSubmit: vi.fn(), onClose });
      fireEvent.click(screen.getByRole('button', { name: /เริ่มดีลใหม่/ }));
      openSection('details');
      fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลใหม่เกินโควตา' } });
      fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));
      expect(screen.getByText('ยังไม่ได้สร้างดีล')).not.toBeNull(); // confirm open, flush failed (at cap)

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(onClose).not.toHaveBeenCalled(); // the modal must stay open
      expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull(); // only the confirm itself closed
      // The typed data is still there -- Escape must not have discarded anything.
      openSection('details');
      expect(screen.getByDisplayValue('ดีลใหม่เกินโควตา')).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('regression probe: backdrop mousedown on the closeFlushFailed dialog dismisses only the confirm, never discards', () => {
    for (let i = 0; i < 10; i += 1) saveDraft(null, { dealTitle: `ดีลเดิม ${i}` });
    vi.useFakeTimers();
    try {
      const onClose = vi.fn();
      renderModal({ onSubmit: vi.fn(), onClose });
      fireEvent.click(screen.getByRole('button', { name: /เริ่มดีลใหม่/ }));
      openSection('details');
      fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลใหม่เกินโควตา' } });
      fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));
      expect(screen.getByText('ยังไม่ได้สร้างดีล')).not.toBeNull();

      // The confirm dialog's OWN backdrop -- the last (topmost) [role="presentation"] in the DOM,
      // since it is rendered after (and on top of) the outer modal's own backdrop.
      const backdrops = document.querySelectorAll('[role="presentation"]');
      fireEvent.mouseDown(backdrops[backdrops.length - 1]);

      expect(onClose).not.toHaveBeenCalled();
      expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull();
      openSection('details');
      expect(screen.getByDisplayValue('ดีลใหม่เกินโควตา')).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('softens the copy when an older saved copy already exists: says the latest edits will be lost, not everything', () => {
    saveDraft(null, { dealTitle: 'ร่างเดิมที่บันทึกไว้แล้ว' });
    renderModal({ onSubmit: vi.fn() });
    // Restore with a WORKING store first, so currentDraftIdRef is genuinely already assigned --
    // the whole point of this test is that the older copy predates the failure below.
    fireEvent.click(screen.getByRole('button', { name: 'เปิดต่อ' }));
    openSection('details');
    fireEvent.change(screen.getByDisplayValue('ร่างเดิมที่บันทึกไว้แล้ว'), { target: { value: 'ร่างเดิมที่บันทึกไว้แล้ว แก้ไข' } });

    // NOW break storage, so the close-time flush of the latest edit fails.
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('nope'); },
      setItem: () => { throw new Error('nope'); },
      removeItem: () => { throw new Error('nope'); },
      clear: () => {},
      key: () => null,
      length: 0,
    });
    try {
      fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

      expect(screen.getByText(/เฉพาะการแก้ไขล่าสุดจะหายไป/)).not.toBeNull();
      expect(screen.queryByText(/ข้อมูลที่กรอกไว้จะหายไปทั้งหมด/)).toBeNull();
    } finally {
      vi.stubGlobal('localStorage', createMemoryStorage());
    }
  });
});

describe('TicketCreateModal with broken localStorage (GLA-19/20)', () => {
  it('still renders and creates a deal when every localStorage method throws', async () => {
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('nope'); },
      setItem: () => { throw new Error('nope'); },
      removeItem: () => { throw new Error('nope'); },
      clear: () => { throw new Error('nope'); },
      key: () => { throw new Error('nope'); },
      get length() { throw new Error('nope'); },
    });
    try {
      const onSubmit = vi.fn().mockResolvedValue(undefined);
      expect(() => renderModal({ onSubmit })).not.toThrow();
      // No picker (listDrafts() degrades to []) -- straight to the blank form.
      expect(screen.getByRole('button', { name: /^ลูกค้า/ })).not.toBeNull();

      await selectCustomerAndProject();
      chooseEntryChannel();
      expect(() => submitForm()).not.toThrow();
      await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    } finally {
      vi.stubGlobal('localStorage', createMemoryStorage());
    }
  });
});

// Review fix (P1-P5): the behaviour these test already existed (the `closeConfirmOpen ||
// draftDeleteTarget` guard at the top of handleRequestClose, and the close/cap/migration paths
// exercised by other describe blocks in this file), but had no DIRECT test -- a reviewer removing
// that guard entirely left all other tests in this file green. Mutation-checked below.
describe('TicketCreateModal nested-dialog Escape/backdrop routing (review fix)', () => {
  // Fake timers around every test that makes a meaningful edit: that schedules the debounce
  // effect's real setTimeout, and these tests (synchronous, no `await`) never let it fire or
  // clear it via unmount before the NEXT test starts -- observed cross-test pollution where an
  // earlier test's orphaned timer fired mid-way through a LATER test and wrote an extra draft
  // into its (by-then-current) storage stub. Fake timers make that scheduling inert: nothing here
  // advances the clock, so the timer is simply discarded when vi.useRealTimers() runs.
  function typeMeaningfulTitle() {
    openSection('details');
    fireEvent.change(screen.getByPlaceholderText('ชื่อดีล'), { target: { value: 'ดีลค้างไว้' } });
  }

  it('P1: Escape while the close confirm is open closes only the confirm, not the modal', () => {
    vi.useFakeTimers();
    try {
      const onClose = vi.fn();
      renderModal({ onSubmit: vi.fn(), onClose });
      typeMeaningfulTitle();
      fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));
      expect(screen.getByText('ยังไม่ได้สร้างดีล')).not.toBeNull();

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(screen.queryByText('ยังไม่ได้สร้างดีล')).toBeNull(); // the confirm itself is dismissed
      expect(onClose).not.toHaveBeenCalled(); // but the outer modal was NOT closed
      expect(sectionHeader('customer')).not.toBeNull(); // the form is still there
    } finally {
      vi.useRealTimers();
    }
  });

  // Two separate cases (not one test with two renders in sequence): the FIRST render's own
  // backdrop-mousedown already writes a real draft via handleRequestClose's synchronous flush, so
  // reusing that same storage for a second render would open it on the PICKER instead of a blank
  // hub -- each case gets its own fresh beforeEach-provided store instead.
  it('P2a: backdrop mousedown routes through the guard -- confirm shown, not a direct close', () => {
    vi.useFakeTimers();
    try {
      const onClose = vi.fn();
      renderModal({ onSubmit: vi.fn(), onClose });
      typeMeaningfulTitle();
      const backdrop = document.querySelector('[role="presentation"]');
      expect(backdrop).not.toBeNull();
      fireEvent.mouseDown(backdrop);
      expect(onClose).not.toHaveBeenCalled();
      expect(screen.getByText('ยังไม่ได้สร้างดีล')).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('P2b: header ✕ routes through the guard -- confirm shown, not a direct close', () => {
    vi.useFakeTimers();
    try {
      const onClose = vi.fn();
      renderModal({ onSubmit: vi.fn(), onClose });
      typeMeaningfulTitle();
      fireEvent.click(screen.getByRole('button', { name: 'ปิด' }));
      expect(onClose).not.toHaveBeenCalled();
      expect(screen.getByText('ยังไม่ได้สร้างดีล')).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('P3: Escape on the picker\'s ลบ confirm closes only that confirm -- draft not deleted, picker stays', () => {
    saveDraft(null, { dealTitle: 'ดีล A' });
    const onClose = vi.fn();
    renderModal({ onSubmit: vi.fn(), onClose });

    fireEvent.click(screen.getByRole('button', { name: /^ลบร่าง/ }));
    expect(screen.getByText('ลบร่างนี้?')).not.toBeNull();

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByText('ลบร่างนี้?')).toBeNull(); // its own confirm dismissed
    expect(listDrafts()).toHaveLength(1); // NOT deleted
    expect(onClose).not.toHaveBeenCalled(); // outer modal untouched
    expect(screen.getByRole('button', { name: 'เปิดต่อ' })).not.toBeNull(); // still on the picker
  });

  it('P4: at the draft cap, closing warns data will be lost and never offers เก็บร่างไว้', () => {
    for (let i = 0; i < 10; i += 1) saveDraft(null, { dealTitle: `ดีลเดิม ${i}` });
    vi.useFakeTimers();
    try {
      const onClose = vi.fn();
      renderModal({ onSubmit: vi.fn(), onClose });

      fireEvent.click(screen.getByRole('button', { name: /เริ่มดีลใหม่/ }));
      typeMeaningfulTitle();

      fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

      expect(screen.getByText('ยังไม่ได้สร้างดีล')).not.toBeNull();
      expect(screen.getByText(/ข้อมูลที่กรอกไว้จะหายไปทั้งหมด/)).not.toBeNull(); // never-saved -> full loss wording
      expect(screen.queryByText('เก็บร่างไว้')).toBeNull(); // that label never appears when the flush failed
      expect(onClose).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('P5: a legacy glr:draft-deal key migrates into the picker on open, and the legacy key is gone', () => {
    localStorage.setItem('glr:draft-deal', JSON.stringify({ dealTitle: 'ร่างเก่าก่อน GLA-19' }));
    renderModal({ onSubmit: vi.fn() });

    expect(screen.getByText('ร่างเก่าก่อน GLA-19')).not.toBeNull();
    expect(localStorage.getItem('glr:draft-deal')).toBeNull();
    expect(listDrafts()).toHaveLength(1);
  });
});
