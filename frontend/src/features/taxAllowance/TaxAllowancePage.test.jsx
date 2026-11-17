import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation, useNavigationType } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
        renderMyTaxAllowanceForm: vi.fn(),
        withdrawMyTaxAllowanceDeclaration: vi.fn(),
        estimateMyTaxAllowanceDeclaration: vi.fn(),
        listTaxAllowanceAttachments: vi.fn(),
        downloadTaxAllowanceForm: vi.fn(),
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

// Same idiom as TaxAllowanceReviewPage.test.jsx's `LocationProbe`: exposes the router's URL as
// text, plus `navigationType` (PUSH/REPLACE/POP via `useNavigationType`) to verify view changes
// push a history entry while year changes replace.
function LocationProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return (
    <div data-testid="location" data-nav-type={navigationType}>
      {`${location.pathname}${location.search}`}
    </div>
  );
}

function renderPage({ entry = '/tax-allowance', showToast = vi.fn() } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <TaxAllowancePage user={user} showToast={showToast} />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Waits for an evidence picker to actually mount before firing at it. `last` picks the sign-off panel's picker
 * when more than one ข้อ is open. */
async function findPicker({ last = false } = {}) {
  return waitFor(() => {
    const pickers = document.querySelectorAll('input[type="file"]');
    expect(pickers.length).toBeGreaterThan(0);
    return last ? pickers[pickers.length - 1] : pickers[0];
  });
}

describe('TaxAllowancePage', () => {
  /** ข้อมูลผู้มีเงินได้ is collapsed unless the form is editable — open it to read its slots. */
  function openIdentity() {
    const header = screen.getByRole('button', { name: /ข้อมูลผู้มีเงินได้/ });
    if (header.getAttribute('aria-expanded') === 'false') fireEvent.click(header);
  }

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
      // The badge beside it already reads "รอ HR ตรวจสอบ"; this line must add the way out
      // rather than repeat it.
      expect(screen.getByText(/ยกเลิกการยื่น แล้วยื่นฉบับใหม่/)).not.toBeNull();
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

  // The PENDING banner promises "ยกเลิกการยื่นเพื่อแก้ไข" and the withdraw ConfirmDialog promises
  // "กลับมาแก้ไข": the employee gets their typed-in values back after withdrawing. WITHDRAWN sits in
  // NON_CURRENT_STATUSES, so `current` is null and `defaultAllowanceValues(null)` would feed the
  // form zeros; `selectResumableDeclaration` seeds the form from the newest WITHDRAWN row when
  // there is no current declaration, but must NOT make a withdrawn declaration look filed (the
  // badge stays ยังไม่ได้ยื่น).
  //
  // ⚠️ DO NOT BARRIER THESE TESTS ON THE STATUS BADGE; assert on what the FORM holds.
  // `findByText('ยังไม่ได้ยื่น')` is satisfied on paint 1 (`taxAllowanceStatusInfo(null)` renders it
  // while the declarations query is in flight), and `findByText(/อนุมัติแล้ว/)` matches
  // TaxAllowanceForm's static identity-section hint on paint 1. Form values arrive strictly later:
  // TaxAllowancePage commits `defaultValues`, then TaxAllowanceForm's passive
  // `useEffect(() => reset(defaultValues))` pushes them into react-hook-form, so opening a ข้อ
  // before that flush mounts its input against '0'. On a loaded CI box this raced
  // (`expected '0' to be '60000'`).
  //
  // So: barrier on something the FORM produces (a ข้อ's `useWatch`-driven subtotal, or an
  // `editing`-gated control the settle effect creates), never on a status badge. Assert the badge
  // afterwards, synchronously, where it is a real assertion rather than a race.
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

      // THE barrier. ข้อ 7's collapsed row shows the withdrawn declaration's figure, rendered from
      // `useWatch`, so it cannot appear until `reset(defaultValues)` has run: it proves the
      // declarations query settled AND fed `resumable` through to the form.
      //
      // ฿25,000 not ฿85,000: the withdrawn row's spouseAllowance is deliberately NOT shown, because
      // ล.ย.01 has no spouse amount box (HR sets that figure at review).
      expect(await screen.findByText('฿25,000.00')).not.toBeNull();

      // Status still reads "not yet filed": a withdrawn declaration is not current standing, only
      // its VALUES are offered back. Asserted AFTER the barrier with a synchronous `getByText`: as a
      // leading `findByText` it matched the in-flight paint and said nothing about the settled state.
      expect(screen.getByText('ยังไม่ได้ยื่น')).not.toBeNull();

      // And the field value survived into the reopened section, not just the total shown on the hub.
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
            // Same allowances as the WITHDRAWN fixture in the test above, so status is the only
            // thing that differs and the assertion below isolates exactly that.
            //
            // `lifeInsuranceAllowance` is the load-bearing half: `spouseAllowance` alone has no
            // amount box on ล.ย.01 (see the test above), so it can never reach ข้อ 7's field and the
            // assertion would read '0' whatever `selectResumableDeclaration` did (accepting
            // SUPERSEDED left the test green).
            allowances: { spouseAllowance: 60000, lifeInsuranceAllowance: 25000 },
          }),
        ],
      });
      renderPage();

      // A test whose expected outcome is "nothing was prefilled" cannot barrier on a value
      // appearing, so it barriers on the page having SETTLED. The sign-off panel is `readOnly ?
      // null : ...` and `readOnly` is `!editing`, which is only set by the effect that opens with
      // `if (declarationsQuery.isLoading) return`, so this control existing means the query
      // resolved and the page acted on it.
      //
      // It is also strictly LATER than any prefill: child effects run before parent effects, so
      // TaxAllowanceForm's `reset(defaultValues)` has already run when TaxAllowancePage's
      // `setEditing` re-render puts this control on screen. That makes the '0' below decide on
      // BEHAVIOUR rather than timing. A `findByText('ยังไม่ได้ยื่น')` barrier is satisfied by the
      // in-flight paint and would fail SILENTLY, by passing.
      await screen.findByRole('button', { name: /ตรวจทาน ลงนาม และยื่น/ });
      expect(screen.getByText('ยังไม่ได้ยื่น')).not.toBeNull();

      fireEvent.click(screen.getByRole('button', { name: /ข้อ 7/ }));
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
          // Deliberately submitted LATER than the APPROVED row above -- proves `current` wins because it IS current, not
          // merely because it happens to be the most recent row overall.
          declaration({
            declarationId: 40,
            status: 'WITHDRAWN',
            submittedAt: `${currentYear}-06-01T00:00:00.000Z`,
            allowances: { lifeInsuranceAllowance: 12345 },
          }),
        ],
      });
      renderPage();

      // THE barrier. ข้อ 7's collapsed row renders `formatMoney` over `useWatch`'s values, so it
      // carries what the FORM holds. ฿60,000.00 means `current` (the APPROVED row) won and is in
      // form state, the precondition for the assertion below. (Awaiting `/อนุมัติแล้ว/` resolved on
      // first paint against a static hint paragraph, not the badge.)
      // Two matches now, not one: ข้อ 7's own subtotal AND the declared total beneath the sections
      // (read-only lost the sign-off panel that used to be the total's only home). `findAllByText`
      // rather than `findByText`, which throws on more than one hit.
      expect(await screen.findAllByText('฿60,000.00')).toHaveLength(2);
      // A real assertion rather than a race. The exact badge label, not `/อนุมัติแล้ว/`: that
      // regex also matches the identity ข้อ's static hint.
      expect(screen.getByText('อนุมัติแล้ว — ยังไม่ใช้กับเงินเดือน')).not.toBeNull();

      fireEvent.click(screen.getByRole('button', { name: /ข้อ 7/ }));
      expect(await screen.findByLabelText('จำนวนเงิน')).not.toBeNull();
      // 60,000 (the APPROVED row), not 12,345 (the later WITHDRAWN one).
      expect(screen.getByLabelText('จำนวนเงิน').value).toBe('60000');
    });
  });

  // ---------------------------------------------------------------------------------------------
  // Revising an ALREADY-CONFIRMED declaration, and getting a copy of it.
  // `TaxAllowanceDeclarationService#submitOwn` refuses only on `existsPending` and `#approve`
  // calls `supersedeApproved` first, so the server accepts a revision; `GET
  // /declarations/{id}/form.pdf` / `hrApi.downloadTaxAllowanceForm` let a filed declaration be
  // downloaded.
  // ---------------------------------------------------------------------------------------------
  describe('an already-confirmed declaration can be revised and downloaded', () => {
    const applied = (overrides = {}) => declaration({
      status: 'APPROVED',
      appliedAt: `${currentYear}-01-20T00:00:00.000Z`,
      appliedEffectiveMonth: 1,
      allowances: { lifeInsuranceAllowance: 40000 },
      ...overrides,
    });

    it('offers a revise action on an APPLIED declaration, where there used to be none', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [applied()] });
      renderPage();

      expect(await screen.findByText(/ใช้กับเงินเดือนแล้ว/)).not.toBeNull();
      expect(await screen.findByRole('button', { name: 'ยื่นฉบับแก้ไข' })).not.toBeNull();
      // Its own words, not the rejected/expired button's — this replaces a declaration in force.
      expect(screen.queryByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' })).toBeNull();
    });

    it('offers the same on APPROVED-but-not-yet-applied', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [applied({ appliedAt: null, appliedEffectiveMonth: null })],
      });
      renderPage();

      expect(await screen.findByText('อนุมัติแล้ว — ยังไม่ใช้กับเงินเดือน')).not.toBeNull();
      expect(await screen.findByRole('button', { name: 'ยื่นฉบับแก้ไข' })).not.toBeNull();
    });

    it('a past tax year is still read-only — revising applies to the current year only', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [applied()] });
      renderPage({ entry: `/tax-allowance?year=${currentYear - 1}` });

      expect(await screen.findByText(new RegExp(`ยื่นหรือแก้ไขได้เฉพาะปีภาษี ${currentYear}`))).not.toBeNull();
      expect(screen.queryByRole('button', { name: 'ยื่นฉบับแก้ไข' })).toBeNull();
    });

    /** The header-prefill invariant, wrong-way-round. A confirmed declaration shows WHAT WAS FILED — seeding its
     * blank header from today's employee master would show the employee an address they never declared, on a
     * document HR has already accepted. */
    it('does NOT seed the header from the master while the confirmed declaration is only being viewed', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [applied()],
        headerPrefill: {
          taxpayerId: '1103700000011', firstNameTh: 'สมชาย', lastNameTh: 'ใจดี',
          maritalState: 'SINGLE', address: { province: 'กรุงเทพมหานคร' },
        },
      });
      renderPage();

      expect(await screen.findByRole('button', { name: 'ยื่นฉบับแก้ไข' })).not.toBeNull();
      openIdentity();
      expect(document.getElementById('ta-taxpayer-id').value).toBe('');
      expect(document.getElementById('ta-addr-province').value).toBe('');
    });

    it('downloads the filed declaration by its own id', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [applied({ declarationId: 91 })] });
      api.payroll.downloadTaxAllowanceForm.mockResolvedValue(new Blob(['%PDF-1.6'], { type: 'application/pdf' }));
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลดแบบ ล.ย.01 ที่ยื่นไว้' }));

      await waitFor(() => {
        expect(api.payroll.downloadTaxAllowanceForm).toHaveBeenCalledWith(91);
      });
    });

    it('offers no download on a year that was never filed — there is no saved row to fetch', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      renderPage();

      expect(await screen.findByText('ยังไม่ได้ยื่น')).not.toBeNull();
      expect(screen.queryByRole('button', { name: 'ดาวน์โหลดแบบ ล.ย.01 ที่ยื่นไว้' })).toBeNull();
    });

    it('still offers the download while the declaration is only PENDING — a filing is the employee\'s either way', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration({ declarationId: 55 })] });
      renderPage();

      expect(await screen.findByRole('button', { name: 'ดาวน์โหลดแบบ ล.ย.01 ที่ยื่นไว้' })).not.toBeNull();
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

  // A file picked mid-fill-in shows immediately (staged, client-side) WITHOUT hitting the server
  // (a brand-new declaration has no declarationId yet), and is uploaded, tagged to the section it
  // was picked under, only once the declaration is submitted and a real declarationId exists.
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

      // Submitting is blocked until the signed form is back, so the flow goes through that panel:
      // evidence staged mid-fill-in has to survive the extra step.
      expect(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }).disabled).toBe(true);

      fireEvent.click(screen.getByRole('button', { name: /ตรวจทาน ลงนาม และยื่น/ }));
      const signed = new File(['y'], 'signed.pdf', { type: 'application/pdf' });
      fireEvent.change(await findPicker({ last: true }), { target: { files: [signed] } });
      await screen.findByText('signed.pdf');

      const submit = screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' });
      await waitFor(() => expect(submit.disabled).toBe(false));
      fireEvent.click(submit);

      // Submitting opens a confirmation first; the button does not file on its own. Scoped to the
      // dialog: the page's own submit button carries the same label, and picking "the last match"
      // would silently re-click the wrong one.
      const dialog = await screen.findByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยื่นแบบแจ้ง' }));

      await waitFor(() => expect(api.payroll.submitMyTaxAllowanceDeclaration).toHaveBeenCalled());
      // Flushed against the REAL declarationId the submit just returned, tagged with the ข้อ it was picked under
      // while filling in -- proves this is not "attach after a first submit", it is the SAME pick from mid-fill-in
      // finally reaching the server.
      await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledWith(77, file, 'item7'));
      expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledWith(77, signed, 'signed_form');
    });
  });

  // `?view=` is gone: the page is one screen of collapsibles, so only the year is URL-addressable.
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

    // HUB/SECTION/REVIEW are URL-addressable screens, so Back steps between them rather than
    // leaving `/tax-allowance` and dropping everything typed. A year switch is a filter on the
    // same screen, so it keeps `replace: true`.
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

      // Dirties the form through a ข้อ's own field: a value typed inside a collapsible still counts
      // as unsaved work worth confirming before it is discarded.
      fireEvent.click(await screen.findByRole('button', { name: /ข้อ 7/ }));
      fireEvent.change(await screen.findByLabelText('จำนวนเงิน'), { target: { value: '1000' } });
      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });
      fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันเปลี่ยนปีภาษี' }));

      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
    });
  });

  // -------------------------------------------------------------------------------------------
  // Header prefill. The gate is `canStartEdit`, so the interesting assertions are the NEGATIVE
  // ones: a prefill must not appear on a declaration that has already been filed, because those
  // fields would then show today's master record against a document HR already accepted.
  // -------------------------------------------------------------------------------------------
  describe('ล.ย.01 header prefill', () => {
    const headerPrefill = {
      taxpayerId: '1103700000011',
      firstNameTh: 'สมชาย',
      lastNameTh: 'ใจดี',
      maritalState: 'SINGLE',
      address: {
        building: 'อาคารเอ', roomNo: '1201', floor: '12', village: 'หมู่บ้านสวนหลวง',
        houseNo: '123/45', moo: '4', soi: 'ซอย 7', junction: 'แยกรัชดา', road: 'ถนนพระราม 9',
        subDistrict: 'ห้วยขวาง', district: 'ห้วยขวาง', province: 'กรุงเทพมหานคร', postalCode: '10310',
      },
    };

    it('seeds the identity block on a year that has never been filed', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [], headerPrefill });
      renderPage();

      expect(await screen.findByText('ยังไม่ได้ยื่น')).not.toBeNull();
      await waitFor(() => {
        expect(document.getElementById('ta-taxpayer-id').value).toBe('1103700000011');
      });
      expect(document.getElementById('ta-first-name').value).toBe('สมชาย');
      expect(document.getElementById('ta-last-name').value).toBe('ใจดี');
      expect(document.getElementById('ta-addr-houseNo').value).toBe('123/45');
      expect(document.getElementById('ta-addr-moo').value).toBe('4');
      expect(document.getElementById('ta-addr-postalCode').value).toBe('10310');
    });

    it('does NOT seed a PENDING declaration — that view shows what was actually filed', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [declaration({ status: 'PENDING' })],
        headerPrefill,
      });
      renderPage();

      expect(await screen.findByRole('button', { name: 'ยกเลิกการยื่น' })).not.toBeNull();
      // ข้อมูลผู้มีเงินได้ is collapsed on a read-only declaration, so the slots have to be disclosed before they
      // can be read — being blank is still the assertion.
      openIdentity();
      expect(document.getElementById('ta-taxpayer-id').value).toBe('');
      expect(document.getElementById('ta-addr-houseNo').value).toBe('');
    });

    it('does NOT seed a past tax year, even though that year is read-only anyway', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [], headerPrefill });
      renderPage({ entry: `/tax-allowance?year=${currentYear - 1}` });

      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
      // A past year is read-only, so its identity block starts collapsed (see above).
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /ข้อมูลผู้มีเงินได้/ })).not.toBeNull();
      });
      openIdentity();
      expect(document.getElementById('ta-taxpayer-id').value).toBe('');
      expect(document.getElementById('ta-addr-province').value).toBe('');
    });

    it('lets a REJECTED declaration\'s own header win over the master', async () => {
      // The employee is re-preparing a filing they already corrected once. Seeding the master over the top would
      // revert that correction under them.
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [declaration({
          status: 'REJECTED',
          reviewerNote: 'เลขประจำตัวไม่ตรง',
          lorYor01: { taxpayerId: '9999999999999', address: { houseNo: '77/7' } },
        })],
        headerPrefill,
      });
      renderPage();

      expect(await screen.findByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' })).not.toBeNull();
      // Read the header slots from the READ-ONLY view rather than pressing "แก้ไข / ยื่นฉบับใหม่":
      // which source won in `defaultValues` is decided by `canStartEdit` (true for REJECTED in the
      // current year either way), and jsdom flushes the page's `setEditing(statusInfo.key ===
      // 'NONE')` effect AFTER the click, putting `editing` back to false. (A real browser flushes
      // it before.)
      openIdentity();
      await waitFor(() => {
        expect(document.getElementById('ta-taxpayer-id').value).toBe('9999999999999');
      });
      expect(document.getElementById('ta-addr-houseNo').value).toBe('77/7');
      // ...while the slots that declaration left blank still come from the master.
      expect(document.getElementById('ta-addr-province').value).toBe('กรุงเทพมหานคร');
    });

    it('survives a response with no headerPrefill at all — the form still opens', async () => {
      // The old wire shape (and an older cached response). A crash here would take the whole page
      // down for a convenience field.
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      renderPage();

      expect(await screen.findByText('ยังไม่ได้ยื่น')).not.toBeNull();
      // `findByText` above resolves on the FIRST paint — the badge already reads ยังไม่ได้ยื่น while the query is in
      // flight — so this has to wait for the form to actually become editable before reading a slot that only the
      // editable form discloses.
      await waitFor(() => {
        expect(document.getElementById('ta-taxpayer-id')).not.toBeNull();
      });
      expect(document.getElementById('ta-taxpayer-id').value).toBe('');
    });
  });

  // -------------------------------------------------------------------------------------------
  // Confirm before filing. Once the signed scan is staged the submit button is enabled, and
  // HTML's implicit submission hands Enter in any text field a real `event.submitter`, so both of
  // SafeForm's guards pass and Enter would file the declaration outright. The guard is not
  // Enter-specific: the dialog sits between the submit EVENT and the mutation, so every trigger
  // (Enter, click, anything added later) goes through the same gate.
  //
  // ⚠️ jsdom cannot perform implicit submission, so nothing here exercises the Enter trigger; it
  // proves a submit event does not reach the API on its own. The Enter path is covered in
  // frontend/e2e-real/loryor01-form.spec.js.
  // -------------------------------------------------------------------------------------------
  describe('filing asks for confirmation first', () => {
    async function fillToSubmittable() {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] }); // status NONE
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: /ตรวจทาน ลงนาม และยื่น/ }));
      const signed = new File(['y'], 'signed.pdf', { type: 'application/pdf' });
      fireEvent.change(await findPicker({ last: true }), { target: { files: [signed] } });
      await screen.findByText('signed.pdf');
      const submit = screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' });
      await waitFor(() => expect(submit.disabled).toBe(false));
      return submit;
    }

    it('a submit does NOT reach the API — it opens the confirmation', async () => {
      const submit = await fillToSubmittable();

      fireEvent.click(submit);

      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByRole('heading', { name: 'ยื่นแบบแจ้ง ล.ย.01' })).not.toBeNull();
      // The assertion that matters. Everything above it is scaffolding.
      expect(api.payroll.submitMyTaxAllowanceDeclaration).not.toHaveBeenCalled();
    });

    it('says what filing actually does, rather than asking "are you sure"', async () => {
      const submit = await fillToSubmittable();
      fireEvent.click(submit);

      // Both consequences stated before the employee commits: it goes to HR, and the form locks
      // until withdrawn. Someone who reads only this sentence should not be surprised afterwards.
      // Scoped to the dialog on purpose: the sign-off panel's step list also contains
      // "ส่งให้ฝ่ายบุคคลตรวจสอบ", and an unscoped match would read that and pass for the wrong reason.
      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByText(/ส่งให้ฝ่ายบุคคลตรวจสอบ/).textContent)
        .toMatch(/ล็อกไม่ให้แก้ไขจนกว่าจะยกเลิกการยื่น/);
    });

    it('dismissing files nothing and leaves the form exactly as it was', async () => {
      const submit = await fillToSubmittable();
      fireEvent.click(submit);
      await screen.findByRole('dialog');

      fireEvent.click(screen.getByRole('button', { name: 'ตรวจทานอีกครั้ง' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      expect(api.payroll.submitMyTaxAllowanceDeclaration).not.toHaveBeenCalled();
      // Still editable, still holding the staged signed form — dismissing returns you to the form, it does not reset
      // it. `getAllByText`: the filename legitimately appears twice, once in FileUploadField's picked-file echo and
      // once in the staged-evidence list.
      expect(screen.getAllByText('signed.pdf').length).toBeGreaterThan(0);
      expect(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }).disabled).toBe(false);
    });

    it('confirming files exactly the declaration the form was holding', async () => {
      api.payroll.submitMyTaxAllowanceDeclaration.mockResolvedValue({
        declarationId: 77, employeeId: 9, status: 'PENDING',
      });
      const submit = await fillToSubmittable();

      // A value typed before the dialog opened must survive into what is actually filed. The gate holds the
      // submitted values, and handing the mutation a stale or empty snapshot is the obvious way to get this refactor
      // subtly wrong.
      fireEvent.click(screen.getByRole('button', { name: /ข้อ 7/ }));
      fireEvent.change(await screen.findByLabelText('จำนวนเงิน'), { target: { value: '4321' } });

      fireEvent.click(submit);
      const dialog = await screen.findByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยื่นแบบแจ้ง' }));

      await waitFor(() => expect(api.payroll.submitMyTaxAllowanceDeclaration).toHaveBeenCalledTimes(1));
      expect(api.payroll.submitMyTaxAllowanceDeclaration.mock.calls[0][0]).toMatchObject({
        taxYear: currentYear,
        lifeInsuranceAllowance: 4321,
      });
      // The /me endpoint never carries an employeeId — the server resolves the caller from the session. Asserted
      // here because this change moved the body-building call site.
      expect(api.payroll.submitMyTaxAllowanceDeclaration.mock.calls[0][0].employeeId).toBeUndefined();
    });
  });
  /**
   * showToast's signature is `showToast(kind, message)` (hooks/useToast.js); swapping the two is
   * silent (`sanitizeToastMessage` returns `message` untouched when `kind !== 'error'`, so the
   * literal "success" renders as the body). Other tests pass a bare `vi.fn()`, which accepts any
   * argument order: assert the ARGUMENTS.
   */
  describe('toast argument order (kind first, message second)', () => {
    it('reports a generated PDF as a success toast, not a toast whose body is the word "success"', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      api.payroll.renderMyTaxAllowanceForm.mockResolvedValue(new Blob(['pdf']));
      const showToast = vi.fn();
      renderPage({ showToast });

      fireEvent.click(await screen.findByRole('button', { name: /ตรวจทาน ลงนาม และยื่น/ }));
      fireEvent.click(screen.getByRole('button', { name: 'สร้างไฟล์ PDF แบบ ล.ย.01' }));

      await waitFor(() => expect(showToast).toHaveBeenCalled());
      const [kind, message] = showToast.mock.calls.at(-1);
      expect(kind).toBe('success');
      expect(message).toMatch(/ลงนาม/);
    });

    it('reports a failed PDF as an error toast carrying the real reason', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      api.payroll.renderMyTaxAllowanceForm.mockRejectedValue(new Error('boom'));
      const showToast = vi.fn();
      renderPage({ showToast });

      fireEvent.click(await screen.findByRole('button', { name: /ตรวจทาน ลงนาม และยื่น/ }));
      fireEvent.click(screen.getByRole('button', { name: 'สร้างไฟล์ PDF แบบ ล.ย.01' }));

      await waitFor(() => expect(showToast).toHaveBeenCalled());
      const [kind, message] = showToast.mock.calls.at(-1);
      expect(kind).toBe('error');
      expect(message).toBe('boom');
    });
  });

});
