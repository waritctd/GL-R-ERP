import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DeductionConsentsPage } from './DeductionConsentsPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: { payroll: { getDeductionConsents: vi.fn() } },
  };
});

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

function renderPage({ entry = '/payroll/deduction-consents' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <DeductionConsentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DeductionConsentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.getDeductionConsents.mockResolvedValue({ items: rows });
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
});
