import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DeductionConsentsPage } from './DeductionConsentsPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: { getDeductionConsents: vi.fn(), upsertDeductionConsent: vi.fn() },
      employees: { list: vi.fn() },
    },
  };
});

const HR = { role: 'hr', employeeId: 10 };
const CEO = { role: 'ceo', employeeId: 1 };

// Field-for-field with DeductionWrittenConsentDto. Only the four CONSENT_APPLICABLE_KINDS can
// appear — V107's CHECK constraint rejects the rest at the database.
const rows = [
  {
    id: 1,
    employeeId: 3,
    employeeCode: 'GLR-1003',
    employeeName: 'สมชาย ใจดี',
    deductionKind: 'WARNING_LETTER',
    consentOnFile: true,
    consentDocumentReference: 'CONSENT-2569-0012',
    consentDate: '2026-05-04',
    notes: 'เก็บต้นฉบับไว้ที่แฟ้มบุคคล ชั้น 3',
    recordedById: 9,
    recordedAt: '2026-05-05T03:00:00Z',
    updatedById: 9,
    updatedAt: '2026-05-05T03:00:00Z',
  },
  {
    id: 2,
    employeeId: 20,
    employeeCode: 'GLR-1020',
    employeeName: 'มานี รักงาน',
    deductionKind: 'OTHER_POST_TAX',
    consentOnFile: false,
    consentDocumentReference: null,
    consentDate: null,
    notes: 'ส่งแบบฟอร์มให้พนักงานแล้ว รอเซ็นกลับ',
    recordedById: 9,
    recordedAt: '2026-06-01T03:00:00Z',
    updatedById: 9,
    updatedAt: '2026-06-02T03:00:00Z',
  },
];

function renderPage({ entry = '/payroll/deduction-consents', user, showToast } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <DeductionConsentsPage user={user} showToast={showToast} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DeductionConsentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.getDeductionConsents.mockResolvedValue({ items: rows });
    api.payroll.upsertDeductionConsent.mockResolvedValue({ items: [rows[0]] });
    api.employees.list.mockResolvedValue({
      employees: [
        { id: 3, code: 'GLR-1003', nameTh: 'สมชาย ใจดี', active: true },
        { id: 20, code: 'GLR-1020', nameTh: 'มานี รักงาน', active: true },
        { id: 44, code: 'GLR-1044', nameTh: 'อดีต พนักงาน', active: false },
      ],
    });
  });

  it('renders a row per recorded consent', async () => {
    renderPage();

    expect(await screen.findByText('สมชาย ใจดี')).not.toBeNull();
    expect(screen.getByText('มานี รักงาน')).not.toBeNull();
    expect(screen.getByText('CONSENT-2569-0012')).not.toBeNull();
  });

  it('labels the deduction kind in Thai rather than showing the enum', async () => {
    renderPage();

    expect(await screen.findByText('หักตามใบเตือน')).not.toBeNull();
    expect(screen.queryByText('WARNING_LETTER')).toBeNull();
  });

  // ── The requirement this page exists to satisfy ────────────────────────────
  // The register records a fact; it gates nothing. These three tests pin the copy that says so,
  // because the failure mode is silent: nothing breaks if the wording drifts into implying that a
  // missing consent letter stops a deduction — it just becomes wrong, and a payroll officer acts
  // on it.
  it('states plainly that the record is not a condition on any deduction', async () => {
    renderPage();

    expect(await screen.findByText(/ไม่ใช่เงื่อนไขในการหักเงิน/)).not.toBeNull();
    expect(screen.getByText(/ระบบเงินเดือนไม่ได้อ่านข้อมูลในหน้านี้เลย/)).not.toBeNull();
  });

  it('says outright that "ยังไม่มีหนังสือยินยอม" suspends nothing', async () => {
    renderPage();

    // The single most misreadable value on the page is named explicitly, next to what it does not do.
    expect(await screen.findByText(/ไม่ได้ระงับหรือหยุดการหักเงินรายการใด/)).not.toBeNull();
  });

  // Mutation-checkable guard: swap the consent cell for a <StatusBadge tone="success"> / red-green
  // pair and this goes red. In this app a pill badge means a backend lifecycle status or a
  // work-state, and a success/danger hue means pass/fail — both would assert a consequence the
  // backend does not implement.
  it('renders consent state as plain text, with no status pill and no pass/fail colour', async () => {
    const { container } = renderPage();
    await screen.findByText('สมชาย ใจดี');

    expect(container.querySelector('.status-badge')).toBeNull();

    const onFile = screen.getByText('มีหนังสือยินยอม');
    const notOnFile = screen.getByText('ยังไม่มีหนังสือยินยอม');
    for (const node of [onFile, notOnFile]) {
      const className = node.getAttribute('class') || '';
      expect(className).not.toMatch(/text-success|text-danger|bg-success|bg-danger|status-/);
    }
  });

  it('keeps HR notes reachable on desktop behind a per-row disclosure', async () => {
    renderPage();
    await screen.findByText('สมชาย ใจดี');

    // Collapsed by default — renderExpanded returns null until the row is opened.
    expect(screen.queryByText(/รอเซ็นกลับ/)).toBeNull();

    const toggle = screen.getByRole('button', { name: /ดูหมายเหตุของ มานี รักงาน/ });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(toggle);

    expect(await screen.findByText(/รอเซ็นกลับ/)).not.toBeNull();
  });

  it('passes ?employeeId through to the server as a real query param', async () => {
    renderPage({ entry: '/payroll/deduction-consents?employeeId=3' });

    await waitFor(() => expect(api.payroll.getDeductionConsents).toHaveBeenCalledWith({ employeeId: '3' }));
  });

  // `kind` is a SERVER-side filter, not client-side narrowing — the arity contract test cannot see
  // whether a declared parameter is actually used, so it is asserted here.
  it('sends the chosen deduction kind to the server rather than filtering locally', async () => {
    renderPage();
    await screen.findByText('สมชาย ใจดี');

    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'OTHER_POST_TAX' } });

    await waitFor(() => expect(api.payroll.getDeductionConsents).toHaveBeenCalledWith({ kind: 'OTHER_POST_TAX' }));
  });

  it('asks for the whole register when nothing is filtered', async () => {
    renderPage();

    await waitFor(() => expect(api.payroll.getDeductionConsents).toHaveBeenCalledWith({}));
  });

  // The state every real environment is in today: nothing has ever been recorded, because the write
  // endpoint had no client. The empty copy must not read as "nothing is authorised".
  it('treats an empty register as the normal state and repeats the non-consequence', async () => {
    api.payroll.getDeductionConsents.mockResolvedValue({ items: [] });
    renderPage();

    expect((await screen.findAllByText('ยังไม่มีรายการในทะเบียน')).length).toBeGreaterThan(0);
    expect(screen.getByText(/ทะเบียนที่ว่างอยู่ไม่มีผลต่อการหักเงิน/)).not.toBeNull();
  });

  it('distinguishes an empty filter result from an empty register', async () => {
    api.payroll.getDeductionConsents.mockResolvedValue({ items: [] });
    renderPage({ entry: '/payroll/deduction-consents?kind=WARNING_LETTER' });

    expect((await screen.findAllByText('ไม่มีรายการตามเงื่อนไขที่เลือก')).length).toBeGreaterThan(0);
  });

  it('surfaces a load failure with a retry', async () => {
    api.payroll.getDeductionConsents.mockRejectedValue(new Error('boom'));
    renderPage();

    expect(await screen.findByText('โหลดทะเบียนหนังสือยินยอมไม่สำเร็จ')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ลองใหม่' })).not.toBeNull();
  });

  // ── The write half: hr only ───────────────────────────────────────────────
  // DeductionWrittenConsentService.EDIT_ROLES is Set.of("hr"), strictly narrower than VIEW_ROLES
  // (hr + ceo). A route guard cannot express that — CEO must still reach the page — so the split
  // lives in the page and is asserted wrong-way-round: what matters is that CEO gets NO control,
  // not that HR gets one.
  //
  // FRONTEND GATING ONLY. This says nothing about what the server would do with a CEO's PUT;
  // DeductionWrittenConsentService is what enforces that, and it is unverified here.
  // Every form query is scoped to the dialog: "พนักงาน" and "ประเภทการหัก" are also a table column
  // header and the toolbar filter's label, so an unscoped getByLabelText matches several elements.
  async function openDialog(name) {
    fireEvent.click(screen.getByRole('button', { name }));
    return within(await screen.findByRole('dialog'));
  }

  describe('write affordances', () => {
    it('offers HR both the record action and a per-row edit', async () => {
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');

      expect(screen.getByRole('button', { name: 'บันทึกหนังสือยินยอม' })).not.toBeNull();
      expect(screen.getByRole('button', { name: /แก้ไขบันทึกหนังสือยินยอมของ สมชาย ใจดี/ })).not.toBeNull();
    });

    it('offers the CEO no write control at all, on the page or on any row', async () => {
      renderPage({ user: CEO });
      await screen.findByText('สมชาย ใจดี');

      expect(screen.queryByRole('button', { name: 'บันทึกหนังสือยินยอม' })).toBeNull();
      expect(screen.queryByRole('button', { name: /แก้ไขบันทึกหนังสือยินยอม/ })).toBeNull();
      // And no employee lookup is even attempted — employees.list is HR-only server-side too.
      expect(api.employees.list).not.toHaveBeenCalled();
    });

    it('sends a recorded consent to the server as the DTO shape', async () => {
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');

      const dialog = await openDialog('บันทึกหนังสือยินยอม');

      fireEvent.change(dialog.getByRole('combobox', { name: 'พนักงาน' }), { target: { value: '20' } });
      fireEvent.change(dialog.getByRole('combobox', { name: 'ประเภทการหัก' }), { target: { value: 'CUSTOMER_RETURN' } });
      fireEvent.click(dialog.getByRole('checkbox'));
      fireEvent.change(dialog.getByRole('textbox', { name: 'เลขที่เอกสาร' }), { target: { value: ' CONSENT-2569-0099 ' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

      await waitFor(() => expect(api.payroll.upsertDeductionConsent).toHaveBeenCalledWith({
        employeeId: 20,
        deductionKind: 'CUSTOMER_RETURN',
        consentOnFile: true,
        // Trimmed, and null (not '') when blank — the columns are nullable TEXT.
        consentDocumentReference: 'CONSENT-2569-0099',
        consentDate: null,
        notes: null,
      }));
    });

    // (employee, kind) is the table's UNIQUE key and the endpoint upserts on it, so letting either
    // be changed during an edit would write a DIFFERENT row and silently leave the original.
    it('locks employee and deduction kind when editing an existing row', async () => {
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');

      const dialog = await openDialog(/แก้ไขบันทึกหนังสือยินยอมของ สมชาย ใจดี/);

      expect(dialog.getByRole('combobox', { name: 'พนักงาน' }).disabled).toBe(true);
      expect(dialog.getByRole('combobox', { name: 'ประเภทการหัก' }).disabled).toBe(true);
      // Prefilled from the row, so an edit is a real edit rather than a blank re-entry.
      expect(dialog.getByRole('textbox', { name: 'เลขที่เอกสาร' }).value).toBe('CONSENT-2569-0012');
      expect(dialog.getByRole('checkbox').checked).toBe(true);
    });

    it('offers only the four kinds the backend accepts', async () => {
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');
      const dialog = await openDialog('บันทึกหนังสือยินยอม');

      const kindOptions = Array.from(dialog.getByRole('combobox', { name: 'ประเภทการหัก' }).options)
        .map((option) => option.value)
        .filter(Boolean);
      expect(kindOptions.sort()).toEqual(['CUSTOMER_RETURN', 'OTHER_POST_TAX', 'OTHER_PRETAX', 'WARNING_LETTER']);
    });

    // The dialog is where the box actually gets ticked, and a modal covers the page's own
    // explanation while it is open — so the non-consequence has to be restated inside it.
    it('states inside the dialog that ticking the box changes no deduction', async () => {
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกหนังสือยินยอม' }));

      expect(await screen.findByText(/การติ๊กหรือไม่ติ๊กช่องนี้ไม่มีผลต่อการหักเงิน/)).not.toBeNull();
      // "บันทึก" (record), never "อนุมัติ" (approve) — the verb is part of the guarantee.
      expect(screen.queryByRole('button', { name: 'อนุมัติ' })).toBeNull();
    });

    it('keeps the dialog open and shows the backend message when the write is refused', async () => {
      api.payroll.upsertDeductionConsent.mockRejectedValue(new Error('ไม่พบข้อมูลพนักงาน'));
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');

      const dialog = await openDialog('บันทึกหนังสือยินยอม');
      fireEvent.change(dialog.getByRole('combobox', { name: 'พนักงาน' }), { target: { value: '20' } });
      fireEvent.change(dialog.getByRole('combobox', { name: 'ประเภทการหัก' }), { target: { value: 'CUSTOMER_RETURN' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

      // The server's own Thai message survives rather than being replaced by a generic fallback.
      expect(await screen.findByRole('alert')).not.toBeNull();
      expect(screen.getByText('ไม่พบข้อมูลพนักงาน')).not.toBeNull();
      // Still open, so the bad field is fixable without re-entering everything.
      expect(screen.getByRole('dialog')).not.toBeNull();
    });

    it('refuses to submit without an employee and a kind', async () => {
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');
      await openDialog('บันทึกหนังสือยินยอม');

      fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

      expect(await screen.findByText('กรุณาเลือกพนักงาน')).not.toBeNull();
      expect(screen.getByText('กรุณาเลือกประเภทการหัก')).not.toBeNull();
      expect(api.payroll.upsertDeductionConsent).not.toHaveBeenCalled();
    });

    it('leaves inactive employees out of the picker', async () => {
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');
      const dialog = await openDialog('บันทึกหนังสือยินยอม');

      const employeeOptions = Array.from(dialog.getByRole('combobox', { name: 'พนักงาน' }).options).map((option) => option.value);
      expect(employeeOptions).toContain('20');
      expect(employeeOptions).not.toContain('44');
    });
  });
});
