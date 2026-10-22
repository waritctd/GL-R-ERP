import React from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationContactPicker } from './QuotationContactPicker.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      customers: {
        contacts: vi.fn(),
        createContact: vi.fn(),
        updateContact: vi.fn(),
      },
    },
  };
});

function Harness({
  customerId = 1, customerName = null, initial = null, showToast,
  omitContactHonorific, onChangeOmitContactHonorific,
}) {
  const [value, setValue] = React.useState(initial);
  return (
    <QuotationContactPicker
      customerId={customerId} customerName={customerName} value={value} onChange={setValue} showToast={showToast}
      omitContactHonorific={omitContactHonorific} onChangeOmitContactHonorific={onChangeOmitContactHonorific}
    />
  );
}

// ── The field and its list (owner rulings 2026-10-18, .design/quotation-contact/DESIGN.md): label
// ผู้ติดต่อ, optional, and the ONLY explanatory text on the screen is the one hint. ──
describe('QuotationContactPicker — the field and its list', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: [] });
  });

  it('shows the one hint and no required marker', async () => {
    const { container } = render(<Harness onChangeOmitContactHonorific={vi.fn()} />);
    const trigger = await screen.findByLabelText(/^ผู้ติดต่อ/);
    expect(trigger.getAttribute('aria-required')).toBeNull();
    expect(container.querySelector('.field-required')).toBeNull();
    const hint = screen.getByText('ชื่อจะพิมพ์เป็นผู้สั่งซื้อบนใบเสนอราคา');
    expect(trigger.getAttribute('aria-describedby')).toBe(hint.id);
    // No other explanatory text: the old PCR-only hint and the "คุณ" checkbox's sub-line are gone.
    expect(screen.queryByText(/ฝ่ายนำเข้าไม่ใช้ข้อมูลนี้/)).toBeNull();
    expect(screen.queryByText(/ใช้เมื่อ/)).toBeNull();
  });

  it('typing 081 filters the list to the contact with that phone; rows show phone · email, a missing one muted as ไม่มีเบอร์ / ไม่มีอีเมล', async () => {
    api.customers.contacts.mockResolvedValue({
      contacts: [
        { id: 1, customerId: 1, firstName: 'สมชาย', lastName: 'ใจดี', phone: '081-234-5678', email: null },
        { id: 2, customerId: 1, firstName: 'วราภรณ์', lastName: 'มั่นคง', phone: '089-000-1122', email: 'waraporn@example.com' },
        { id: 3, customerId: 1, firstName: 'บริษัท ทดสอบ จำกัด', lastName: null, phone: null, email: null },
      ],
    });
    render(<Harness />);
    fireEvent.click(await screen.findByLabelText(/^ผู้ติดต่อ/));

    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(3));
    expect(screen.getAllByRole('option')[2].textContent).toContain('ไม่มีเบอร์ · ไม่มีอีเมล');

    fireEvent.change(screen.getByLabelText('ค้นหาชื่อ เบอร์ หรืออีเมล'), { target: { value: '081' } });

    const options = screen.getAllByRole('option');
    expect(options).toHaveLength(1);
    expect(options[0].textContent).toContain('สมชาย ใจดี');
    expect(options[0].textContent).toContain('081-234-5678 · ไม่มีอีเมล');
  });

  // Safari, and Firefox on macOS, do not focus a pressed <button> or plain box: the press takes
  // focus off whatever held it, with NO relatedTarget — so a blur-to-close keyed on relatedTarget
  // reads every such press as "focus left". jsdom has no default action for mousedown, so this
  // plays it: unless a handler cancelled the mousedown, focus is dropped the way those browsers
  // drop it, then the click lands. Only for things those browsers do not focus on a press.
  function pressAsSafari(el) {
    if (fireEvent.mouseDown(el)) act(() => { document.activeElement?.blur(); });
    fireEvent.mouseUp(el);
    fireEvent.click(el);
  }

  it('a press inside the open list keeps it open and a press on the trigger closes it, where a press does not move focus (Safari, Firefox)', async () => {
    api.customers.contacts.mockResolvedValue({
      contacts: [{ id: 1, customerId: 1, firstName: 'สมชาย', lastName: 'ใจดี', phone: '081-234-5678', email: null }],
    });
    render(<Harness />);
    const trigger = await screen.findByLabelText(/^ผู้ติดต่อ/);
    fireEvent.click(trigger);
    const search = screen.getByLabelText('ค้นหาชื่อ เบอร์ หรืออีเมล');
    await waitFor(() => expect(document.activeElement).toBe(search));

    pressAsSafari(search.parentElement); // the padding round the search box
    expect(screen.queryByRole('listbox')).not.toBeNull();
    fireEvent.change(search, { target: { value: 'ไม่มีใครชื่อนี้' } });
    pressAsSafari(screen.getByText('ไม่พบผู้ติดต่อ'));
    expect(screen.queryByRole('listbox')).not.toBeNull();
    expect(document.activeElement).toBe(search);

    // Not cancelled: the search box (a press there must still place the caret) and the listbox
    // element itself (the only press that lands on it is its own scrollbar, and a cancelled
    // mousedown can stop a scrollbar drag in some browsers).
    expect(fireEvent.mouseDown(search)).toBe(true);
    expect(fireEvent.mouseDown(screen.getByRole('listbox'))).toBe(true);

    // The trigger toggles it shut — not shut on the mousedown's blur and straight back open on
    // the click — and focus lands back on the trigger rather than on the page.
    pressAsSafari(trigger);
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });
});

// ── "ใช้ชื่อเดียวกับลูกค้า" (owner testing feedback, 2026-09-11): "when the customer and the
// purchaser are the same person, let the rep reuse the customer name instead of retyping it". ──
describe('QuotationContactPicker — ใช้ชื่อเดียวกับลูกค้า', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: [] });
  });

  async function openNewContactForm() {
    fireEvent.click(await screen.findByLabelText(/^ผู้ติดต่อ/));
    fireEvent.click(screen.getByTestId('contact-add-first'));
  }

  const dialog = () => screen.getByRole('dialog', { name: 'เพิ่มผู้ติดต่อใหม่' });
  const dialogField = (name) => within(dialog()).getByLabelText(name);

  it('the add row is the first row of the list and the form has no ตำแหน่ง field', async () => {
    api.customers.contacts.mockResolvedValue({
      contacts: [{ id: 5, customerId: 1, firstName: 'สมชาย', lastName: 'ใจดี', phone: null, email: null }],
    });
    render(<Harness customerName="บริษัท ทดสอบ จำกัด" />);
    fireEvent.click(await screen.findByLabelText(/^ผู้ติดต่อ/));

    const addRow = screen.getByTestId('contact-add-first');
    expect(addRow.textContent).toContain('เพิ่มผู้ติดต่อใหม่');
    const [firstContact] = await screen.findAllByRole('option');
    // DOCUMENT_POSITION_FOLLOWING (4): the search box and every contact row come AFTER the add row.
    expect(addRow.compareDocumentPosition(screen.getByLabelText('ค้นหาชื่อ เบอร์ หรืออีเมล')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(addRow.compareDocumentPosition(firstContact) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    fireEvent.click(addRow);

    expect(within(dialog()).getByText('ลูกค้า: บริษัท ทดสอบ จำกัด')).not.toBeNull();
    expect(dialogField(/^ชื่อ/).getAttribute('aria-required')).toBe('true');
    for (const optional of ['นามสกุล', 'เบอร์โทร', 'อีเมล']) {
      expect(dialogField(optional).getAttribute('aria-required')).toBeNull();
    }
    expect(within(dialog()).queryByText(/ตำแหน่ง/)).toBeNull();
    expect(within(dialog()).getByRole('button', { name: 'ยกเลิก' })).not.toBeNull();
    expect(within(dialog()).getByRole('button', { name: 'เพิ่มและเลือกคนนี้' })).not.toBeNull();
  });

  it('does not render the checkbox when no customer name is known yet', async () => {
    render(<Harness customerName={null} />);
    await openNewContactForm();
    expect(screen.queryByText(/ใช้ชื่อเดียวกับลูกค้า/)).toBeNull();
  });

  it('checking the box copies the customer name into ชื่อ, blanks นามสกุล, and locks ชื่อ', async () => {
    render(<Harness customerName="วิภา สมิทธ์" />);
    await openNewContactForm();

    const lastNameInput = dialogField('นามสกุล');
    fireEvent.change(lastNameInput, { target: { value: 'เดิม' } });

    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));

    const firstNameInput = dialogField(/^ชื่อ/);
    expect(firstNameInput.value).toBe('วิภา สมิทธ์');
    expect(firstNameInput.disabled).toBe(true);
    expect(lastNameInput.value).toBe('');
  });

  it('unchecking hands ชื่อ back for manual editing without clearing it', async () => {
    render(<Harness customerName="วิภา สมิทธ์" />);
    await openNewContactForm();
    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));

    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));

    const firstNameInput = dialogField(/^ชื่อ/);
    expect(firstNameInput.disabled).toBe(false);
    expect(firstNameInput.value).toBe('วิภา สมิทธ์'); // kept, not cleared -- a starting point to edit from

    fireEvent.change(firstNameInput, { target: { value: 'วิภา (ผู้จัดการ)' } });
    expect(firstNameInput.value).toBe('วิภา (ผู้จัดการ)');
  });

  it('stays live-bound to the customer name while checked, e.g. across a customer switch', async () => {
    function SwitchHarness() {
      const [name, setName] = React.useState('บริษัท เอ จำกัด');
      const [value, setValue] = React.useState(null);
      return (
        <>
          <button type="button" onClick={() => setName('บริษัท บี จำกัด')}>switch</button>
          <QuotationContactPicker customerId={1} customerName={name} value={value} onChange={setValue} />
        </>
      );
    }
    render(<SwitchHarness />);
    await openNewContactForm();
    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));
    expect(dialogField(/^ชื่อ/).value).toBe('บริษัท เอ จำกัด');

    fireEvent.click(screen.getByRole('button', { name: 'switch' }));

    expect(dialogField(/^ชื่อ/).value).toBe('บริษัท บี จำกัด');
  });

  it('submits the copied name as the new contact', async () => {
    api.customers.createContact.mockResolvedValue({ contact: { id: 9, customerId: 1, firstName: 'วิภา สมิทธ์', lastName: null } });
    render(<Harness customerName="วิภา สมิทธ์" />);
    await openNewContactForm();
    fireEvent.click(screen.getByRole('checkbox', { name: /ใช้ชื่อเดียวกับลูกค้า/ }));

    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มและเลือกคนนี้' }));

    await waitFor(() => expect(api.customers.createContact).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ firstName: 'วิภา สมิทธ์', lastName: null }),
    ));
    // …and the created contact becomes the selection (the modal's button says เพิ่ม AND เลือก):
    // the selected card, with its เปลี่ยน trigger, replaces the closed select.
    expect(await screen.findByRole('button', { name: 'เปลี่ยนผู้ติดต่อ' })).toBeTruthy();
  });
});

// ── edit-in-place: โทร./อีเมล of the SELECTED contact (gap fix, prod QT-2026-0041-1) ──────────
describe('QuotationContactPicker — edit-in-place โทร./อีเมล', () => {
  const SELECTED_CONTACT = { id: 42, customerId: 1, firstName: 'วิภา', lastName: 'สมิทธ์', phone: '081-000-0000', email: 'wipa@example.com' };

  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: [SELECTED_CONTACT] });
  });

  it('editing the e-mail and blurring calls updateContact with ONLY the changed field', async () => {
    api.customers.updateContact.mockResolvedValue({ contact: { ...SELECTED_CONTACT, email: 'new@example.com' } });
    render(<Harness initial={SELECTED_CONTACT} />);

    const emailInput = await screen.findByLabelText('แก้ไขอีเมลผู้ติดต่อ');
    fireEvent.change(emailInput, { target: { value: 'new@example.com' } });
    fireEvent.blur(emailInput);

    await waitFor(() => expect(api.customers.updateContact).toHaveBeenCalledWith(1, 42, { email: 'new@example.com' }));
    expect(api.customers.updateContact).toHaveBeenCalledTimes(1);
  });

  it('does not call the API when the field is blurred unchanged', async () => {
    render(<Harness initial={SELECTED_CONTACT} />);
    const phoneInput = await screen.findByLabelText('แก้ไขเบอร์ผู้ติดต่อ');
    expect(phoneInput.value).toBe('081-000-0000');
    fireEvent.focus(phoneInput);
    fireEvent.blur(phoneInput);
    await Promise.resolve(); // let any (unwanted) microtask flush
    expect(api.customers.updateContact).not.toHaveBeenCalled();
  });

  it('rolls back and shows a toast when the save is refused', async () => {
    api.customers.updateContact.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    const showToast = vi.fn();
    render(<Harness initial={SELECTED_CONTACT} showToast={showToast} />);

    const emailInput = await screen.findByLabelText('แก้ไขอีเมลผู้ติดต่อ');
    fireEvent.change(emailInput, { target: { value: 'broken@example.com' } });
    fireEvent.blur(emailInput);

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    expect(emailInput.value).toBe('wipa@example.com'); // rolled back
  });
});

// ── Item 2 (V180, "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ", owner ruling 2026-09-16) ─────────────────
describe('QuotationContactPicker — ไม่เติม "คุณ" หน้าชื่อผู้สั่งซื้อ', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: [] });
  });

  it('does not render the checkbox when the caller passes no onChangeOmitContactHonorific', async () => {
    render(<Harness />);
    await screen.findByLabelText(/^ผู้ติดต่อ/);
    expect(screen.queryByText(/ไม่เติม/)).toBeNull();
  });

  it('renders unticked by default when the caller wires the handler', async () => {
    render(<Harness onChangeOmitContactHonorific={vi.fn()} />);
    const checkbox = await screen.findByRole('checkbox', { name: /ไม่เติม/ });
    expect(checkbox.checked).toBe(false);
  });

  it('reflects the omitContactHonorific prop when true', async () => {
    render(<Harness omitContactHonorific onChangeOmitContactHonorific={vi.fn()} />);
    const checkbox = await screen.findByRole('checkbox', { name: /ไม่เติม/ });
    expect(checkbox.checked).toBe(true);
  });

  it('clicking calls onChangeOmitContactHonorific with the new checked value', async () => {
    const onChangeOmitContactHonorific = vi.fn();
    render(<Harness onChangeOmitContactHonorific={onChangeOmitContactHonorific} />);
    fireEvent.click(await screen.findByRole('checkbox', { name: /ไม่เติม/ }));
    expect(onChangeOmitContactHonorific).toHaveBeenCalledWith(true);
  });

  it('is placed directly under the ผู้ติดต่อ picker (per the owner\'s own placement)', async () => {
    render(<Harness onChangeOmitContactHonorific={vi.fn()} />);
    const picker = await screen.findByLabelText(/^ผู้ติดต่อ/);
    const checkbox = screen.getByRole('checkbox', { name: /ไม่เติม/ });
    // DOCUMENT_POSITION_FOLLOWING (4): the checkbox comes AFTER the picker in document order.
    expect(picker.compareDocumentPosition(checkbox) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

// Bug fix (owner re-report 2026-09-16, "แก้หรือเพิ่ม Email ผู้สั่งซื้อภายหลังไม่ได้"): a
// quotation-grant holder who cannot load the ticket has `customerId` (== `contactCustomerId` on
// the editor page) stay null even though the quotation's own frozen snapshot already seeded a
// SELECTED contact with a real phone/email -- editing either field used to return silently: no
// request, no toast, the rep left to guess why nothing happened.
describe('QuotationContactPicker — missing customerId (owner re-report 2026-09-16)', () => {
  const SELECTED_CONTACT = { id: 42, customerId: 1, firstName: 'วิภา', lastName: 'สมิทธ์', phone: '081-000-0000', email: 'wipa@example.com' };

  // Resets call history AND any lingering mockRejectedValue/mockResolvedValue configuration from
  // an earlier describe block's tests (e.g. "edit-in-place โทร./อีเมล"'s own updateContact stubs) —
  // without this, `expect(api.customers.updateContact).not.toHaveBeenCalled()` below could see a
  // PREVIOUS test's call still in the mock's history.
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows a Thai toast instead of silently no-op-ing, and keeps the typed value visible', async () => {
    const showToast = vi.fn();
    render(<Harness customerId={null} initial={SELECTED_CONTACT} showToast={showToast} />);

    const emailInput = await screen.findByLabelText('แก้ไขอีเมลผู้ติดต่อ');
    fireEvent.change(emailInput, { target: { value: 'new@example.com' } });
    fireEvent.blur(emailInput);

    await waitFor(() => expect(showToast).toHaveBeenCalledWith(
      'error', 'แก้ไขข้อมูลผู้ติดต่อไม่ได้ — ไม่พบข้อมูลลูกค้าของดีลนี้',
    ));
    expect(api.customers.updateContact).not.toHaveBeenCalled();
    // No request was ever sent, so there is nothing to roll back -- the typed value stays exactly
    // as the rep left it (unlike the "rolls back" case above, which DID send a request).
    expect(emailInput.value).toBe('new@example.com');
  });
});
