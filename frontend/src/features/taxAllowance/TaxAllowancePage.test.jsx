import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation, useNavigationType } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowancePage } from './TaxAllowancePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        getTaxAllowanceCaps: vi.fn(),
        getMyTaxAllowanceDeclarations: vi.fn(),
        submitMyTaxAllowanceDeclaration: vi.fn(),
        withdrawMyTaxAllowanceDeclaration: vi.fn(),
        estimateMyTaxAllowanceDeclaration: vi.fn(),
        listTaxAllowanceAttachments: vi.fn(),
        uploadTaxAllowanceAttachment: vi.fn(),
      },
    },
  };
});

const user = { role: 'employee', name: 'สมชาย ใจดี', employeeId: 9 };
const currentYear = new Date().getFullYear();

function declaration(overrides = {}) {
  return {
    declarationId: 55,
    employeeId: 9,
    status: 'PENDING',
    submittedAt: `${currentYear}-03-01T00:00:00.000Z`,
    appliedAt: null,
    appliedEffectiveMonth: null,
    effectiveMonth: null,
    expiresOn: null,
    reviewerNote: null,
    allowances: {},
    ...overrides,
  };
}

// Same idiom as TaxAllowanceReviewPage.test.jsx's own `LocationProbe` -- exposes the router's
// current URL as text so a test can assert on it without reaching into react-router internals.
// `navigationType` additionally exposes react-router's own PUSH/REPLACE/POP classification of the
// most recent navigation (public API, `useNavigationType`) -- used to verify view changes push a
// real history entry while year changes replace, without reaching into MemoryRouter's own history
// stack.
function LocationProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return (
    <div data-testid="location" data-nav-type={navigationType}>
      {`${location.pathname}${location.search}`}
    </div>
  );
}

function renderPage({ entry = '/tax-allowance' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <TaxAllowancePage user={user} showToast={vi.fn()} />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * Waits for an evidence picker to actually mount before firing at it.
 *
 * The panel only renders once `evidenceMode` is set, and the page deliberately refuses to decide
 * whether this year is editable until the declarations query has SETTLED — so a picker queried
 * synchronously right after `findByRole` resolves is reliably null. `last` picks the sign-off
 * panel's picker when more than one ข้อ is open.
 */
async function findPicker({ last = false } = {}) {
  return waitFor(() => {
    const pickers = document.querySelectorAll('input[type="file"]');
    expect(pickers.length).toBeGreaterThan(0);
    return last ? pickers[pickers.length - 1] : pickers[0];
  });
}

describe('TaxAllowancePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.getTaxAllowanceCaps.mockResolvedValue({ caps: [] });
    api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({ items: [] });
    api.payroll.withdrawMyTaxAllowanceDeclaration.mockResolvedValue(undefined);
  });

  describe('withdrawal — the way out of PENDING', () => {
    it('offers withdrawal while pending, explaining why the form is locked', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration()] });
      renderPage();

      expect(await screen.findByRole('button', { name: 'ยกเลิกการยื่น' })).not.toBeNull();
      expect(screen.getByText(/แก้ไขไม่ได้ระหว่างรอ HR ตรวจสอบ/)).not.toBeNull();
      // Editing stays unavailable — withdrawal is the exit, not a direct edit.
      expect(screen.queryByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' })).toBeNull();
    });

    it('withdraws the pending declaration after confirmation', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration()] });
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิกการยื่น' }));
      // The confirm dialog repeats the label; the last match is the dialog's confirm button.
      const confirmButtons = await screen.findAllByRole('button', { name: 'ยกเลิกการยื่น' });
      fireEvent.click(confirmButtons[confirmButtons.length - 1]);

      await waitFor(() => {
        expect(api.payroll.withdrawMyTaxAllowanceDeclaration).toHaveBeenCalledWith(55);
      });
    });

    it('does not offer withdrawal once approved', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [declaration({ status: 'APPROVED', appliedAt: `${currentYear}-04-01T00:00:00Z`, appliedEffectiveMonth: 4 })],
      });
      renderPage();

      await screen.findByText(/ใช้กับเงินเดือนแล้ว/);
      expect(screen.queryByRole('button', { name: 'ยกเลิกการยื่น' })).toBeNull();
    });
  });

  // Regression (fix/tax-allowance-withdraw-preserves-values): the PENDING banner promises
  // "ยกเลิกการยื่นเพื่อแก้ไข" and the withdraw ConfirmDialog promises "กลับมาแก้ไข" -- both say the
  // employee gets their typed-in values back after withdrawing. Before this fix, withdrawal wiped
  // every one of the 21 fields to zero: WITHDRAWN sits in NON_CURRENT_STATUSES, so `current` became
  // null and `defaultAllowanceValues(null)` fed the form nothing but zeros. `selectResumableDeclaration`
  // now seeds the form from the newest WITHDRAWN row when there is no current declaration -- but must
  // NOT make a withdrawn declaration look filed (the badge stays ยังไม่ได้ยื่น).
  describe('withdrawal preserves values for resume (#387 regression)', () => {
    it('pre-fills the form from the most recently withdrawn declaration when there is no current one', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [
          declaration({
            status: 'WITHDRAWN',
            submittedAt: `${currentYear}-03-01T00:00:00.000Z`,
            allowances: { spouseAllowance: 60000, lifeInsuranceAllowance: 25000 },
          }),
        ],
      });
      renderPage();

      // Status still reads "not yet filed", exactly as before this fix -- a withdrawn declaration is
      // not current standing, only its VALUES are offered back for editing.
      expect(await screen.findByText('ยังไม่ได้ยื่น')).not.toBeNull();

      // ข้อ 7's own collapsed row shows the withdrawn declaration's figure, not ฿0.00. Waiting on
      // this is also what proves the declarations query has actually settled and fed `resumable`
      // through to `defaultValues`, rather than the synchronous first paint (null either way).
      //
      // ฿25,000 not ฿85,000: the withdrawn row's spouseAllowance is deliberately NOT shown, because
      // ล.ย.01 has no spouse amount box — HR sets that figure at review.
      expect(await screen.findByText('฿25,000.00')).not.toBeNull();

      // And the actual field value survived into the reopened section, not just the total shown on
      // the hub -- matches the real-backend repro's own `#ta-spouseAllowance` check.
      fireEvent.click(screen.getByRole('button', { name: /ข้อ 7/ }));
      expect(await screen.findByLabelText('จำนวนเงิน')).not.toBeNull();
      expect(screen.getByLabelText('จำนวนเงิน').value).toBe('25000');
    });

    it('does not pre-fill from a SUPERSEDED-only history -- only a WITHDRAWN row is resumable', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [
          declaration({
            status: 'SUPERSEDED',
            submittedAt: `${currentYear}-02-01T00:00:00.000Z`,
            allowances: { spouseAllowance: 60000 },
          }),
        ],
      });
      renderPage();

      expect(await screen.findByText('ยังไม่ได้ยื่น')).not.toBeNull();

      fireEvent.click(await screen.findByRole('button', { name: /ข้อ 7/ }));
      expect(await screen.findByLabelText('จำนวนเงิน')).not.toBeNull();
      expect(screen.getByLabelText('จำนวนเงิน').value).toBe('0');
    });

    it('lets a current APPROVED declaration win over an older WITHDRAWN one', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [
          declaration({
            status: 'APPROVED',
            submittedAt: `${currentYear}-01-01T00:00:00.000Z`,
            allowances: { lifeInsuranceAllowance: 60000 },
          }),
          // Deliberately submitted LATER than the APPROVED row above -- proves `current` wins because
          // it IS current, not merely because it happens to be the most recent row overall.
          declaration({
            declarationId: 40,
            status: 'WITHDRAWN',
            submittedAt: `${currentYear}-06-01T00:00:00.000Z`,
            allowances: { lifeInsuranceAllowance: 12345 },
          }),
        ],
      });
      renderPage();

      await screen.findByText(/อนุมัติแล้ว/);

      fireEvent.click(screen.getByRole('button', { name: /ข้อ 7/ }));
      expect(await screen.findByLabelText('จำนวนเงิน')).not.toBeNull();
      // 60,000 (the APPROVED row), not 12,345 (the later WITHDRAWN one).
      expect(screen.getByLabelText('จำนวนเงิน').value).toBe('60000');
    });
  });

  describe('tax-year history', () => {
    it('refetches when a past year is chosen', async () => {
      renderPage();
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear);
      });

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
    });

    it('is read-only for a past year even when nothing was ever filed', async () => {
      renderPage();
      await screen.findByLabelText('ปีภาษี');

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });

      expect(await screen.findByText(new RegExp(`ยื่นหรือแก้ไขได้เฉพาะปีภาษี ${currentYear}`))).not.toBeNull();
      expect(screen.queryByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' })).toBeNull();
    });
  });

  // The user's stated complaint (#tax-allowance-sections): "I couldn't attach a PDF while first
  // filling in the form" -- there was no declarationId yet for a brand-new declaration to attach
  // evidence to. This proves the fix end to end: a file picked mid-fill-in shows immediately
  // (staged, client-side) WITHOUT hitting the server, and is genuinely uploaded — tagged to the
  // section it was picked under — only once the declaration is actually submitted and a real
  // declarationId exists.
  describe('attaching evidence while first filling in a NOT-YET-SUBMITTED declaration', () => {
    it('stages a PDF picked while filling in a section, then uploads it against the real declarationId once submit succeeds', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] }); // status NONE
      api.payroll.submitMyTaxAllowanceDeclaration.mockResolvedValue({ declarationId: 77, employeeId: 9, status: 'PENDING' });
      api.payroll.uploadTaxAllowanceAttachment.mockResolvedValue({ attachment: { attachmentId: 1 } });

      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: /ข้อ 7/ }));

      const file = new File(['x'], 'cert.pdf', { type: 'application/pdf' });
      fireEvent.change(await findPicker(), { target: { files: [file] } });

      // Attached immediately, during fill-in -- no round trip needed to SEE it.
      expect(await screen.findByText('cert.pdf')).not.toBeNull();
      expect(screen.getByText('รอส่ง')).not.toBeNull();
      // But genuinely not sent yet -- there is no declarationId for a brand-new declaration.
      expect(api.payroll.uploadTaxAllowanceAttachment).not.toHaveBeenCalled();

      // Submitting is blocked until the signed form is back (owner decision #3), so the flow has
      // to go through that panel — which is the point: evidence staged mid-fill-in has to survive
      // the extra step, not just an immediate submit.
      expect(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }).disabled).toBe(true);

      fireEvent.click(screen.getByRole('button', { name: /ตรวจทาน ลงนาม และยื่น/ }));
      const signed = new File(['y'], 'signed.pdf', { type: 'application/pdf' });
      fireEvent.change(await findPicker({ last: true }), { target: { files: [signed] } });
      await screen.findByText('signed.pdf');

      const submit = screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' });
      await waitFor(() => expect(submit.disabled).toBe(false));
      fireEvent.click(submit);

      await waitFor(() => expect(api.payroll.submitMyTaxAllowanceDeclaration).toHaveBeenCalled());
      // Flushed against the REAL declarationId the submit just returned, tagged with the ข้อ it was
      // picked under while filling in -- proves this is not "attach after a first submit", it is
      // the SAME pick from mid-fill-in finally reaching the server.
      await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledWith(77, file, 'item7'));
      expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledWith(77, signed, 'signed_form');
    });
  });

  // `?view=` is gone with the wizard — the page is one screen of collapsibles now, so only the
  // year is still URL-addressable.
  describe('?year= navigation via the URL', () => {
    it('reads a valid ?year= from the URL and refetches for it', async () => {
      renderPage({ entry: `/tax-allowance?year=${currentYear - 1}` });
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
      expect(screen.getByLabelText('ปีภาษี').value).toBe(String(currentYear - 1));
    });

    it('falls back to the current year for a garbage ?year=', async () => {
      renderPage({ entry: '/tax-allowance?year=not-a-number' });
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear);
      });
      expect(screen.getByLabelText('ปีภาษี').value).toBe(String(currentYear));
    });

    // Review fix: HUB/SECTION/REVIEW are real, URL-addressable screens now that `?view=` exists at
    // all, so Back should step between them like any other page navigation -- not leave
    // `/tax-allowance` entirely and drop everything typed. A year switch is a filter on the same
    // screen, not a screen of its own, so it keeps the OLD `replace: true` behaviour instead.
  });

  describe('year-switch confirmation guards unsaved work', () => {
    it('switches immediately when the form is clean and no evidence is staged', async () => {
      renderPage();
      await screen.findByLabelText('ปีภาษี');

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });

      expect(screen.queryByRole('heading', { name: 'เปลี่ยนปีภาษี' })).toBeNull();
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
    });

    it('asks for confirmation before switching when the form has unsaved edits', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] }); // NONE + current year -> already editable
      renderPage();

      fireEvent.change(await screen.findByLabelText('เลขที่เอกสารอ้างอิง (ถ้ามี)'), { target: { value: 'REF-1' } });
      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });

      expect(await screen.findByRole('heading', { name: 'เปลี่ยนปีภาษี' })).not.toBeNull();
      expect(api.payroll.getMyTaxAllowanceDeclarations).not.toHaveBeenCalledWith(currentYear - 1);
    });

    it('asks for confirmation before switching when evidence is staged', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: /ข้อ 7/ }));
      const file = new File(['x'], 'cert.pdf', { type: 'application/pdf' });
      fireEvent.change(await findPicker(), { target: { files: [file] } });
      await screen.findByText('cert.pdf');

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });

      expect(await screen.findByRole('heading', { name: 'เปลี่ยนปีภาษี' })).not.toBeNull();
    });

    it('proceeds with the year switch once confirmed', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      renderPage();

      // Dirties the form through a ข้อ's own field rather than the declaration-level ones, so this
      // also covers the case that matters after the restructure: a value typed inside a
      // collapsible still counts as unsaved work worth confirming before it is discarded.
      fireEvent.click(await screen.findByRole('button', { name: /ข้อ 7/ }));
      fireEvent.change(await screen.findByLabelText('จำนวนเงิน'), { target: { value: '1000' } });
      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });
      fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันเปลี่ยนปีภาษี' }));

      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
    });
  });
});
