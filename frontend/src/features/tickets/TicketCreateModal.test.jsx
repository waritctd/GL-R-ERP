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
      // ราคาตั้ง (ประมาณการ) estimate inputs (Part D/E) — both fetched via react-query inside the
      // modal, so every render() needs a QueryClientProvider (see renderModal() below) and every
      // test needs these two resolved (or explicitly rejected, for the "fetch failure" case).
      fxRates: {
        list: vi.fn(),
      },
      dealEstimateMarkup: {
        get: vi.fn(),
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

/** A catalog-sourced item with a real reference price, for the ราคาตั้ง estimate tests. */
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
// now exercises `canSubmit` specifically: HUB/REVIEW let it through, every other view still
// blocks it even though a submitter is present.
function submitForm() {
  const form = document.getElementById('ticket-create-form');
  form.dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true, submitter: document.createElement('button') }));
}

// Anchored to the start of the accessible name: a hub row's name is its
// title + subtitle concatenated (e.g. "โครงการจำเป็นเลือกลูกค้าก่อน"), so an
// unanchored search for "ลูกค้า" would also match the โครงการ row while it's
// waiting on a customer, and "สร้างดีล" would also match the ตรวจสอบ & บันทึก
// row's "ตรวจก่อนสร้างดีล" subtitle.
function goToSection(name) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${name}`) }));
}

// Every render needs a QueryClientProvider now that the modal fetches fxRates/dealEstimateMarkup
// via react-query (Part D). A fresh QueryClient per render keeps tests isolated from each other.
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

beforeEach(() => {
  vi.clearAllMocks();
  api.customers.search.mockResolvedValue({ customers: [mockCustomer] });
  api.customers.projects.mockResolvedValue({ projects: [mockProject] });
  api.customers.contacts.mockResolvedValue({ contacts: [] });
  api.tickets.list.mockResolvedValue({ tickets: [] });
  // Default happy path for the ราคาตั้ง estimate: FX rates covering the catalogItem() fixture's
  // EUR price, and the owner's default ×2 markup. Individual tests override these to exercise
  // "fetch failed" / different markup scenarios.
  api.fxRates.list.mockResolvedValue({
    fxRates: [
      { currency: 'THB', rateToThb: 1 },
      { currency: 'EUR', rateToThb: 38.5 },
      { currency: 'USD', rateToThb: 33 },
    ],
  });
  api.dealEstimateMarkup.get.mockResolvedValue({ dealEstimateMarkup: { multiplier: 2 } });
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

  it('marks a specific blank field (color), not just a generic row message', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit, initialItems: [validItem({ color: '' })] });

    await selectCustomerAndProject();
    submitForm();

    const colorInput = await screen.findByPlaceholderText('เช่น ขาว, เทา, ครีม');
    await waitFor(() => expect(colorInput.getAttribute('aria-invalid')).toBe('true'));
    expect(colorInput.id).toBe('item-0-color');
    expect(colorInput.getAttribute('aria-describedby')).toBe('item-0-color-error');
    expect(screen.getByText('กรุณากรอกสี')).toBeTruthy();

    // The old generic string must not appear — the whole point of the fix.
    expect(screen.queryByText(/กรุณากรอกข้อมูลสินค้าให้ครบทุกช่องในรายการที่/)).toBeNull();
    // Other fields in the same row stay untouched.
    expect(document.getElementById('item-0-brand').getAttribute('aria-invalid')).toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('marks the qty field for a PIECE row with qty 0', async () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit, initialItems: [validItem({ unitBasis: 'PIECE', qty: 0 })] });

    await selectCustomerAndProject();
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
        factory: null,
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
    renderModal({ onSubmit, initialItems: [validItem({ color: '' })] });

    await selectCustomerAndProject();
    submitForm();

    const colorInput = await screen.findByPlaceholderText('เช่น ขาว, เทา, ครีม');
    await waitFor(() => expect(colorInput.getAttribute('aria-invalid')).toBe('true'));

    fireEvent.change(colorInput, { target: { value: 'เทา' } });

    expect(colorInput.getAttribute('aria-invalid')).toBeNull();
    expect(colorInput.getAttribute('aria-describedby')).toBeNull();
    expect(screen.queryByText('กรุณากรอกสี')).toBeNull();
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

    submitForm();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items).toEqual([]);
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
// HUB/REVIEW? A submitter-less dispatch would pass every "does NOT submit" case below for free
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

  it('submits a real submit event dispatched from the REVIEW view', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSubmit, initialItems: [validItem()] });

    await selectCustomerAndProject();
    goToSection('ตรวจสอบ & บันทึก');

    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });
});

// V110 fix: the catalog product picked here used to be discarded entirely (ticket_item had no
// column for it), forcing PricingRequestCreateModal to re-search the same product. It is now
// persisted as catalogPriceId/catalogProductCode. Picking a catalog product also used to write
// the FACTORY name into ยี่ห้อ (brand) — a real bug, fixed here to match
// PricingRequestCreateModal.applyCatalogItem's already-correct rule.
describe('TicketCreateModal catalog picker (V110 catalog link + brand mapping fix)', () => {
  function mockCatalogProduct(overrides = {}) {
    return {
      priceId: 501,
      productCode: 'CT-100',
      factoryName: 'Cotto Factory',
      grade: 'Cotto',
      collection: 'Stone Series',
      color: 'ขาว',
      surface: 'ด้าน',
      sizeRaw: '60x60',
      price: 250,
      currency: 'THB',
      ...overrides,
    };
  }

  // Opens the item editor for a fresh row and types into the brand field to trigger the
  // (debounced, real-timer) catalog search — mirrors the actual user flow, not a shortcut around
  // it, since the brand-mapping bug lives inside applyCatalogItem, reached only via a real pick.
  async function addItemAndSearchBrand(query) {
    goToSection('รายการสินค้า');
    fireEvent.click(screen.getByRole('button', { name: /ค้นหาสินค้า/ }));
    const brandInput = await screen.findByPlaceholderText('เช่น SCG, Cotto, Panaria');
    fireEvent.change(brandInput, { target: { value: query } });
    return brandInput;
  }

  it('fills brand from the catalog grade (not the factory name), and carries catalogPriceId/catalogProductCode into the submit payload', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct()] });
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    const brandInput = await addItemAndSearchBrand('Cotto');

    // <strong>{cat.factoryName}</strong> is a leaf element whose OWN text is exactly the factory
    // name, unlike the row's other text (collection/size/price share a div with the " — "
    // separator) — the one reliably unique target to click without a test id on this legacy
    // hand-rolled dropdown.
    const factoryNameInDropdown = await screen.findByText('Cotto Factory');
    fireEvent.mouseDown(factoryNameInDropdown.parentElement);

    await waitFor(() => expect(brandInput.value).toBe('Cotto'));
    // The bug this fixes: brand must NOT become the factory name.
    expect(brandInput.value).not.toBe('Cotto Factory');
    expect(screen.getByPlaceholderText('เช่น SCG Ceramics').value).toBe('Cotto Factory');

    // Close the item editor back to the hub before submitting — the item editor itself never
    // renders a submit control (fix/form-enter-submits-real-records's `handleFormSubmit` gate), so
    // submitting from inside it is not a real, reachable path in a browser.
    fireEvent.click(screen.getByRole('button', { name: /บันทึกรายการ/ }));
    goToSection('กลับ');

    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items[0]).toMatchObject({
      brand: 'Cotto',
      model: 'Stone Series',
      color: 'ขาว',
      texture: 'ด้าน',
      size: '60x60',
      factory: 'Cotto Factory',
      catalogPriceId: 501,
      catalogProductCode: 'CT-100',
    });
  });

  it('keeps an already-typed brand instead of overwriting it with the catalog grade', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct()] });
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    goToSection('รายการสินค้า');
    fireEvent.click(screen.getByRole('button', { name: /ค้นหาสินค้า/ }));
    const modelInput = await screen.findByPlaceholderText('เช่น Stone, Elegance, L-Trim…');
    // Type a brand FIRST (deliberately, before searching by model) — this is the case the
    // fallback rule exists for.
    fireEvent.change(screen.getByPlaceholderText('เช่น SCG, Cotto, Panaria'), { target: { value: 'MyOwnBrand' } });
    fireEvent.change(modelInput, { target: { value: 'Stone' } });

    const factoryNameInDropdown = await screen.findByText('Cotto Factory');
    fireEvent.mouseDown(factoryNameInDropdown.parentElement);

    await waitFor(() => expect(modelInput.value).toBe('Stone Series'));
    expect(screen.getByPlaceholderText('เช่น SCG, Cotto, Panaria').value).toBe('MyOwnBrand');
  });

  // ยี่ห้อ doubles as the catalog search box on THIS form (unlike PricingRequestCreateModal,
  // which has a dedicated one). So a partial query left in it after a pick from the ยี่ห้อ
  // dropdown is a QUERY, not a deliberate brand — keeping it would leave junk in the field and
  // fail this fix's intent just as writing the factory name did. The grade must win here; the
  // "already-typed brand wins" rule applies only to the รุ่น path (covered by the test above).
  it('replaces a partial ยี่ห้อ search query with the catalog grade when picking from the ยี่ห้อ dropdown', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct()] });
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    // "Cot" is a prefix query, deliberately NOT equal to the product's grade ("Cotto") — so
    // this asserts the grade actually won, rather than the query coincidentally matching it.
    const brandInput = await addItemAndSearchBrand('Cot');
    const factoryNameInDropdown = await screen.findByText('Cotto Factory');
    fireEvent.mouseDown(factoryNameInDropdown.parentElement);

    await waitFor(() => expect(brandInput.value).toBe('Cotto'));
    expect(brandInput.value).not.toBe('Cot');
    expect(brandInput.value).not.toBe('Cotto Factory');
  });

  it('clears the catalog link when the user hand-edits a field after a catalog pick', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct()] });
    renderModal({ onSubmit });

    await selectCustomerAndProject();
    const brandInput = await addItemAndSearchBrand('Cotto');
    const factoryNameInDropdown = await screen.findByText('Cotto Factory');
    fireEvent.mouseDown(factoryNameInDropdown.parentElement);
    await waitFor(() => expect(brandInput.value).toBe('Cotto'));

    // Hand-edit color after the pick — the link no longer reliably describes this row.
    fireEvent.change(screen.getByPlaceholderText('เช่น ขาว, เทา, ครีม'), { target: { value: 'เทาเข้ม' } });

    // Close the item editor back to the hub before submitting — the item editor itself never
    // renders a submit control (fix/form-enter-submits-real-records's `handleFormSubmit` gate), so
    // submitting from inside it is not a real, reachable path in a browser.
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
});

// Part D/E — ราคาตั้ง (ประมาณการ) estimate. dealEstimatePricing.js carries the actual math and its
// own unit tests; these confirm the modal wires that math up correctly: fetches FX + markup,
// renders the per-line and grand-total numbers, excludes non-computable lines from the total while
// still counting them in the "ยังคำนวณไม่ได้" note, and hides the whole UI on a failed fetch.
describe('TicketCreateModal ราคาตั้ง (ประมาณการ) estimate', () => {
  it('computes and shows the per_sqm line total (qtySqm × catalog price × fx × markup)', async () => {
    const item = catalogItem({ catalogPriceUnit: 'per_sqm', qtySqm: 10, qty: '' });
    renderModal({ onSubmit: vi.fn(), initialItems: [item] });

    goToSection('รายการสินค้า');

    // 43 EUR × 38.5 THB/EUR × 2 markup × 10 ตร.ม. = 33,110.00
    const row = await screen.findByTestId('item-estimate-0');
    expect(within(row).getByText('33,110.00 บาท')).not.toBeNull();
    const total = screen.getByTestId('items-estimate-total');
    expect(within(total).getByText('33,110.00 บาท')).not.toBeNull();
    expect(screen.queryByTestId('items-estimate-uncomputable')).toBeNull();
  });

  it('computes and shows the per_piece line total (qty × catalog price × fx × markup)', async () => {
    const item = catalogItem({ catalogPriceUnit: 'per_piece', catalogPrice: 5.5, catalogCurrency: 'USD', unitBasis: 'PIECE', qty: 20, qtySqm: '' });
    renderModal({ onSubmit: vi.fn(), initialItems: [item] });

    goToSection('รายการสินค้า');

    // 5.5 USD × 33 THB/USD × 2 markup × 20 pieces = 7,260.00
    const row = await screen.findByTestId('item-estimate-0');
    expect(within(row).getByText('7,260.00 บาท')).not.toBeNull();
  });

  // D1 regression: when the ENTIRE cart is uncomputable, dealEstimatePricing.js's
  // summarizeItemsEstimate now returns total: null, and the grand-total block must be hidden
  // entirely rather than rendering a bold "0.00 บาท" that reads as a real computed answer.
  it('D1: hides the grand-total block entirely when the whole cart is uncomputable (single per_box line)', async () => {
    const item = catalogItem({ catalogPriceUnit: 'per_box' });
    renderModal({ onSubmit: vi.fn(), initialItems: [item] });

    goToSection('รายการสินค้า');

    const row = await screen.findByTestId('item-estimate-0');
    expect(within(row).getByText('—')).not.toBeNull();

    expect(screen.queryByTestId('items-estimate-total')).toBeNull();
  });

  // D1 regression, exact fire path from the review: adding a plain custom item (the default
  // "เพิ่มรายการสินค้า" flow — no catalog pick at all) must not show a "0.00 บาท" grand total.
  it('D1: hides the grand-total block for an all-custom cart (default add-item flow)', async () => {
    renderModal({ onSubmit: vi.fn(), initialItems: [validItem()] });

    goToSection('รายการสินค้า');

    // Wait for the estimate fetches to actually settle (item-estimate-0 only renders once
    // estimateReady is true) before asserting the total block is absent — otherwise this
    // assertion could pass merely because react-query hasn't resolved yet.
    const row = await screen.findByTestId('item-estimate-0');
    expect(within(row).getByText('—')).not.toBeNull();
    expect(screen.queryByTestId('items-estimate-total')).toBeNull();
    expect(screen.queryByText('0.00 บาท')).toBeNull();
  });

  // D5 regression: the item-editor "ยังคำนวณไม่ได้" caveat used to hardcode "หน่วยราคานี้ยังไม่รองรับ"
  // (unsupported unit) for EVERY not-computable reason. These two open the item editor (where the
  // per-line reason is rendered) and check the message actually names the real cause.
  describe('D5: not-computable reason messages', () => {
    async function openItemEditor() {
      goToSection('รายการสินค้า');
      fireEvent.click(await screen.findByRole('button', { name: 'แก้ไขรายการที่ 1' }));
      return screen.findByTestId('item-editor-estimate-0');
    }

    it('names a per_box line "หน่วยราคานี้ยังไม่รองรับ" (UNSUPPORTED_UNIT)', async () => {
      const item = catalogItem({ catalogPriceUnit: 'per_box' });
      renderModal({ onSubmit: vi.fn(), initialItems: [item] });

      const box = await openItemEditor();
      expect(within(box).getByText(/หน่วยราคานี้ยังไม่รองรับ/)).not.toBeNull();
    });

    // Fire path: a per_sqm item correctly on SQM basis (so no unit-basis mismatch) whose area
    // simply hasn't been typed in yet — this must say "missing quantity", not "unit unsupported".
    it('names a per_sqm line with an empty area "ยังไม่ได้กรอกจำนวน/พื้นที่" (MISSING_QUANTITY), not "unit unsupported"', async () => {
      const item = catalogItem({ catalogPriceUnit: 'per_sqm', unitBasis: 'SQM', qtySqm: '', qty: '' });
      renderModal({ onSubmit: vi.fn(), initialItems: [item] });

      const box = await openItemEditor();
      expect(within(box).getByText(/ยังไม่ได้กรอกจำนวน\/พื้นที่/)).not.toBeNull();
      expect(within(box).queryByText(/หน่วยราคานี้ยังไม่รองรับ/)).toBeNull();
    });
  });

  it('sums only the computable lines in a mixed cart (per_sqm + per_piece + per_box)', async () => {
    const items = [
      catalogItem({ catalogPriceUnit: 'per_sqm', qtySqm: 10, qty: '' }),          // 33,110.00
      catalogItem({ catalogPriceUnit: 'per_piece', catalogPrice: 5.5, catalogCurrency: 'USD', unitBasis: 'PIECE', qty: 20, qtySqm: '' }), // 7,260.00
      catalogItem({ catalogPriceUnit: 'per_box' }),                               // not computable
    ];
    renderModal({ onSubmit: vi.fn(), initialItems: items });

    goToSection('รายการสินค้า');

    // 33,110.00 + 7,260.00 = 40,370.00 — the per_box line must not silently zero this out or
    // count towards it.
    const total = await screen.findByTestId('items-estimate-total');
    expect(within(total).getByText('40,370.00 บาท')).not.toBeNull();
    expect(within(total).getByTestId('items-estimate-uncomputable').textContent).toContain('1 รายการ');
  });

  it('applies the CEO markup multiplier — a ×3 config yields 1.5× the ×2 default figure', async () => {
    api.dealEstimateMarkup.get.mockResolvedValue({ dealEstimateMarkup: { multiplier: 3 } });
    const item = catalogItem({ catalogPriceUnit: 'per_sqm', qtySqm: 10, qty: '' });
    renderModal({ onSubmit: vi.fn(), initialItems: [item] });

    goToSection('รายการสินค้า');

    // 43 EUR × 38.5 × 3 markup × 10 = 49,665.00 (vs 33,110.00 at the default ×2 markup above).
    const row = await screen.findByTestId('item-estimate-0');
    expect(within(row).getByText('49,665.00 บาท')).not.toBeNull();
  });

  it('hides the ราคาตั้ง UI entirely when the FX fetch fails, and the modal still works', async () => {
    api.fxRates.list.mockRejectedValue(new Error('network down'));
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const item = catalogItem();
    renderModal({ onSubmit, initialItems: [item] });

    goToSection('รายการสินค้า');

    // The existing "จากแคตตาล็อก" provenance badge still renders — only the NEW ราคาตั้ง block is
    // gone. Give the rejected query a tick to settle before asserting its absence.
    await screen.findByText('✓ จากแคตตาล็อก');
    await waitFor(() => expect(api.fxRates.list).toHaveBeenCalled());
    expect(screen.queryByTestId('item-estimate-0')).toBeNull();
    expect(screen.queryByTestId('items-estimate-total')).toBeNull();
    expect(screen.queryByText('ราคาตั้ง (ประมาณการ)', { exact: false })).toBeNull();

    // The modal must still be fully usable — including creating the deal — with no price shown.
    goToSection('กลับ');
    await selectCustomerAndProject();
    submitForm();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });

  it('hides the ราคาตั้ง UI when the markup fetch fails, even though FX succeeded', async () => {
    api.dealEstimateMarkup.get.mockRejectedValue(new Error('network down'));
    const item = catalogItem();
    renderModal({ onSubmit: vi.fn(), initialItems: [item] });

    goToSection('รายการสินค้า');

    await waitFor(() => expect(api.dealEstimateMarkup.get).toHaveBeenCalled());
    expect(screen.queryByTestId('item-estimate-0')).toBeNull();
    expect(screen.queryByTestId('items-estimate-total')).toBeNull();
  });
});
