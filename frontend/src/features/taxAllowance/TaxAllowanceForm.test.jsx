import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TaxAllowanceForm } from './TaxAllowanceForm.jsx';
import {
  ALLOWANCE_MONEY_KEYS,
  HR_SUPPLIED_KEYS,
  LOR_YOR_01_SECTIONS,
  emptyAllowanceValues,
} from './taxAllowanceSchema.js';

/**
 * Guards the ล.ย.01 form after it stopped being a five-category wizard and became the government
 * form's own ข้อ 1–15, one collapsible each.
 *
 * The suite this replaced pinned hub/section/review navigation, which no longer exists. What
 * matters now is different: that the page really is the document, that collapsing does not eat
 * data, and that an unsigned declaration cannot be filed.
 */

function renderForm(props = {}) {
  return render(
    <TaxAllowanceForm
      defaultValues={emptyAllowanceValues()}
      onSubmit={vi.fn()}
      {...props}
    />,
  );
}

/** Opens a ข้อ by clicking its disclosure header. */
function openSection(titleFragment) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(titleFragment) }));
}

describe('TaxAllowanceForm — the page is the government form', () => {
  it('renders one section per ข้อ, numbered as the form numbers them', () => {
    renderForm();
    // ข้อ 1–2 share a section (both are สถานภาพ), so the numbering is not a plain 1..15 sequence —
    // it is whatever the form prints, which is the point.
    for (const section of LOR_YOR_01_SECTIONS) {
      const label = section.no ? `ข้อ ${section.no} · ${section.title}` : section.title;
      expect(screen.getByRole('button', { name: new RegExp(label.slice(0, 24)) })).toBeTruthy();
    }
  });

  it('offers no control for the five deductions ล.ย.01 does not print', () => {
    // SSF, Thai ESG, pension life insurance, ค่าฝากครรภ์, political donation are all legally real
    // but absent from this form (owner ruling: strictly the 15 items). They must not reappear as
    // stray inputs — the wire shape still carries them, which is exactly why a control would be
    // easy to add back by accident.
    renderForm();
    for (const key of ['ssfAllowance', 'thaiEsgAllowance', 'pensionInsuranceAllowance',
      'maternityAllowance', 'politicalDonation']) {
      expect(document.getElementById(`ta-${key}`), `${key} must have no input`).toBeNull();
    }
    // …and neither do the two the form omits but payroll needs — HR supplies those at review.
    for (const key of HR_SUPPLIED_KEYS) {
      expect(document.getElementById(`ta-${key}`), `${key} is HR-supplied, not declared`).toBeNull();
    }
    // The wire shape is unchanged, though: every key still ships in the submit body.
    expect(ALLOWANCE_MONEY_KEYS).toContain('ssfAllowance');
  });

  it('ข้อ 11 and ข้อ 13 explain themselves instead of offering an input', () => {
    renderForm();
    openSection('ข้อ 11');
    expect(screen.getByText(/สิ้นสุดสิทธิลดหย่อน/)).toBeTruthy();
    openSection('ข้อ 13');
    expect(screen.getByText(/คำนวณอัตโนมัติ/)).toBeTruthy();
  });

  /**
   * The one library behaviour this component leans on. `CollapsibleSection` UNMOUNTS its children,
   * and these are `register`ed inputs — so the values survive only because react-hook-form defaults
   * `shouldUnregister: false`. If a future bump flips that default, every collapsed ข้อ silently
   * submits as empty and nothing else in the suite would notice.
   */
  it('a value typed into a ข้อ survives collapsing it', () => {
    const onSubmit = vi.fn();
    renderForm({ onSubmit, signedFormAttached: true });

    openSection('ข้อ 7');
    fireEvent.change(screen.getByLabelText('จำนวนเงิน'), { target: { value: '12345' } });
    openSection('ข้อ 7'); // collapse again — the input unmounts here
    expect(screen.queryByLabelText('จำนวนเงิน')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }));
    return vi.waitFor(() => {
      expect(onSubmit).toHaveBeenCalled();
      expect(onSubmit.mock.calls[0][0].lifeInsuranceAllowance).toBe(12345);
    });
  });

  /**
   * The address is the sharper version of the case above, and it needs its own test.
   *
   * The 13 address fields are pre-filled from the employee register and their section now starts
   * COLLAPSED — 13 stacked inputs of mostly-blank government address slots were 3,341px of a
   * 4,340px page at 390px, between the employee and the ข้อ they came to fill in.
   *
   * Collapsed by default means the inputs are never mounted at all unless the employee chooses to
   * open them, so the address reaches the wire purely through form state. If `shouldUnregister`
   * ever flips, the ข้อ test above fails only for someone who typed into a ข้อ and closed it —
   * whereas EVERY declaration would silently file a blank address, on a government form, with
   * nobody having touched anything. That is worth pinning separately.
   */
  it('a pre-filled address still submits while its section has never been opened', () => {
    const onSubmit = vi.fn();
    const defaults = emptyAllowanceValues();
    defaults.lorYor01 = {
      ...defaults.lorYor01,
      address: { ...defaults.lorYor01?.address, houseNo: '123/45', province: 'กรุงเทพมหานคร', postalCode: '10310' },
    };
    renderForm({ onSubmit, defaultValues: defaults, signedFormAttached: true });

    // Never opened: the inputs are not in the document, and the summary is what the employee reads.
    expect(document.getElementById('ta-addr-houseNo')).toBeNull();
    expect(screen.getByText(/123\/45/)).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }));
    return vi.waitFor(() => {
      expect(onSubmit).toHaveBeenCalled();
      const submitted = onSubmit.mock.calls[0][0];
      expect(submitted.lorYor01.address.houseNo).toBe('123/45');
      expect(submitted.lorYor01.address.province).toBe('กรุงเทพมหานคร');
      expect(submitted.lorYor01.address.postalCode).toBe('10310');
    });
  });

  // The collapsed summary is the only view of the address an employee gets without opening it, so
  // it has to track edits. A summary built from `defaultValues` would look right on load and then
  // quietly go stale the moment someone corrected a typo — worse than no summary, because it
  // invites trusting it.
  it('the collapsed summary reflects an edited address, not the value it loaded with', () => {
    const defaults = emptyAllowanceValues();
    defaults.lorYor01 = {
      ...defaults.lorYor01,
      address: { ...defaults.lorYor01?.address, houseNo: '123/45', province: 'กรุงเทพมหานคร' },
    };
    renderForm({ defaultValues: defaults });

    // By id, not by accessible name: the identity section's own subtitle is "ชื่อ
    // เลขประจำตัวผู้เสียภาษีอากร และที่อยู่ตามแบบ ล.ย.01", so /ที่อยู่/ matches its header too.
    const addressHeader = document.getElementById('ta-section-address-header');
    fireEvent.click(addressHeader);
    fireEvent.change(document.getElementById('ta-addr-houseNo'), { target: { value: '999' } });
    fireEvent.click(addressHeader); // collapse again

    expect(screen.getByText(/999 กรุงเทพมหานคร/)).not.toBeNull();
    expect(screen.queryByText(/123\/45/)).toBeNull();
  });
});

describe('TaxAllowanceForm — the signed form gates submission', () => {
  it('the submit button is disabled until the signed scan is attached', () => {
    const { rerender } = renderForm({ signedFormAttached: false });
    expect(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }).disabled).toBe(true);

    rerender(
      <TaxAllowanceForm defaultValues={emptyAllowanceValues()} onSubmit={vi.fn()} signedFormAttached />,
    );
    expect(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }).disabled).toBe(false);
  });

  /**
   * A submit event that did not come from a real submitter must not file the declaration.
   *
   * ⚠️ READ THIS BEFORE TRUSTING IT. `SafeForm` refuses a submit with no submitter REGARDLESS of
   * `canSubmit`, so this passing does not prove the signed-form gate works — it proves the
   * submitter guard does. The gate itself is covered by the disabled-button test above, and the
   * case that actually matters — Enter in one of a dozen text fields, which HTML turns into a real
   * implicit submission — CANNOT be reproduced here at all: jsdom does not implement implicit
   * submission. That one needs e2e-real, and this repo has shipped exactly that bug before while a
   * green jsdom suite said nothing.
   */
  it('a submit event with no submitter does nothing', () => {
    const onSubmit = vi.fn();
    renderForm({ onSubmit, signedFormAttached: false });

    fireEvent.submit(document.getElementById('tax-allowance-form'));

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('generating the PDF hands over the values currently typed, not the defaults', async () => {
    const onGeneratePdf = vi.fn();
    renderForm({ onGeneratePdf });

    openSection('ข้อ 12');
    fireEvent.change(screen.getByLabelText('จำนวนเงิน'), { target: { value: '54321' } });
    openSection('ตรวจทาน ลงนาม และยื่น');
    fireEvent.click(screen.getByRole('button', { name: 'สร้างไฟล์ PDF แบบ ล.ย.01' }));

    // Awaited: the handler now validates the whole form before rendering anything (below).
    await vi.waitFor(() => expect(onGeneratePdf).toHaveBeenCalledTimes(1));
    expect(Number(onGeneratePdf.mock.calls[0][0].homeLoanInterestAllowance)).toBe(54321);
  });

  /**
   * The PDF is the sheet the employee prints and signs, so it must not be rendered from values the
   * schema rejects. Until validation moved to `onTouched` this was unreachable in practice: the
   * resolver only ran on submit, and submit is gated behind the signed scan — i.e. behind having
   * already printed and signed this very PDF.
   */
  it('refuses to render the PDF while a field is invalid, and says so', async () => {
    const onGeneratePdf = vi.fn();
    const showToast = vi.fn();
    renderForm({ onGeneratePdf, showToast });

    // 13 printed cells; "123" cannot be a เลขประจำตัวผู้เสียภาษีอากร.
    fireEvent.change(screen.getByLabelText('เลขประจำตัวผู้เสียภาษีอากร'), { target: { value: '123' } });
    openSection('ตรวจทาน ลงนาม และยื่น');
    fireEvent.click(screen.getByRole('button', { name: 'สร้างไฟล์ PDF แบบ ล.ย.01' }));

    await vi.waitFor(() => expect(showToast).toHaveBeenCalledWith('error', expect.stringMatching(/กรอกไม่ถูกต้อง/)));
    expect(onGeneratePdf).not.toHaveBeenCalled();
  });

  it('shows the field error as soon as the field is left, not only at submit', async () => {
    renderForm();

    const taxId = screen.getByLabelText('เลขประจำตัวผู้เสียภาษีอากร');
    fireEvent.change(taxId, { target: { value: '123' } });
    fireEvent.blur(taxId);

    expect(await screen.findByText('เลขประจำตัวผู้เสียภาษีอากรต้องมี 13 หลัก')).toBeTruthy();
  });
});

describe('TaxAllowanceForm — read-only', () => {
  it('offers neither a submit control nor the sign-and-file panel', () => {
    renderForm({ readOnly: true, signedFormAttached: true });
    expect(screen.queryByRole('button', { name: 'ยื่นแบบแจ้ง' })).toBeNull();
    expect(screen.queryByRole('button', { name: /ตรวจทาน ลงนาม/ })).toBeNull();
  });

  it('still shows the declared figures, so a filed declaration stays readable', () => {
    const values = emptyAllowanceValues();
    values.lifeInsuranceAllowance = 9999;
    renderForm({ readOnly: true, defaultValues: values });

    // Twice, and that is the point: once as ข้อ 7's own collapsed-row subtotal, once as the
    // declared total. The total used to live only inside the sign-off panel, which read-only does
    // not render — so a filed declaration showed fifteen subtotals and no total anywhere.
    expect(screen.getAllByText(/9,999/)).toHaveLength(2);
    expect(screen.getByText('รวมค่าลดหย่อนที่ประกาศ')).toBeTruthy();
  });
});
