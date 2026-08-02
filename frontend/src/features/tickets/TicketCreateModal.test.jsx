import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
    },
  };
});

const mockCustomer = { id: 1, name: 'บริษัท ทดสอบ จำกัด', taxId: null };
const mockProject = { id: 10, name: 'โครงการ A' };

function validItem(overrides = {}) {
  return {
    brand: 'SCG', model: 'Stone', color: 'ขาว', texture: 'ด้าน', size: '60x60',
    factory: '', unitBasis: 'PIECE', qty: 5, qtySqm: '', sqmPerPiece: null,
    source: 'custom', catalogPrice: null, catalogCurrency: null,
    ...overrides,
  };
}

function submitForm() {
  fireEvent.submit(document.getElementById('ticket-create-form'));
}

// Anchored to the start of the accessible name: a hub row's name is its
// title + subtitle concatenated (e.g. "โครงการจำเป็นเลือกลูกค้าก่อน"), so an
// unanchored search for "ลูกค้า" would also match the โครงการ row while it's
// waiting on a customer, and "สร้างดีล" would also match the ตรวจสอบ & บันทึก
// row's "ตรวจก่อนสร้างดีล" subtitle.
function goToSection(name) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${name}`) }));
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
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

    submitForm();

    const customerInput = await screen.findByPlaceholderText('พิมพ์ค้นหาชื่อบริษัท…');
    await waitFor(() => expect(customerInput.getAttribute('aria-invalid')).toBe('true'));
    expect(customerInput.getAttribute('aria-describedby')).toBe('customer-select-error');
    expect(screen.getByText('กรุณาเลือกบริษัท/ลูกค้า')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('marks a specific blank field (color), not just a generic row message', async () => {
    const onSubmit = vi.fn();
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem({ color: '' })]}
      />,
    );

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
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem({ unitBasis: 'PIECE', qty: 0 })]}
      />,
    );

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
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem({ unitBasis: 'SQM', qtySqm: '' })]}
      />,
    );

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
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem()]}
      />,
    );

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
    render(
      <TicketCreateModal
        onClose={() => {}}
        onSubmit={onSubmit}
        initialItems={[validItem({ color: '' })]}
      />,
    );

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
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

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
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

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
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

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
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

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
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

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
    render(<TicketCreateModal onClose={() => {}} onSubmit={onSubmit} />);

    await selectCustomerAndProject();
    const brandInput = await addItemAndSearchBrand('Cotto');
    const factoryNameInDropdown = await screen.findByText('Cotto Factory');
    fireEvent.mouseDown(factoryNameInDropdown.parentElement);
    await waitFor(() => expect(brandInput.value).toBe('Cotto'));

    // Hand-edit color after the pick — the link no longer reliably describes this row.
    fireEvent.change(screen.getByPlaceholderText('เช่น ขาว, เทา, ครีม'), { target: { value: 'เทาเข้ม' } });

    submitForm();

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].items[0]).toMatchObject({
      color: 'เทาเข้ม',
      catalogPriceId: null,
      catalogProductCode: null,
    });
  });
});
