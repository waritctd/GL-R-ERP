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
  /** ข้อมูลผู้มีเงินได้ is collapsed unless the form is editable — open it to read its slots. */
  function openIdentity() {
    const header = screen.getByRole('button', { name: /ข้อมูลผู้มีเงินได้/ });
    if (header.getAttribute('aria-expanded') === 'false') fireEvent.click(header);
  }

  /**
   * The 13 address fields moved behind their own collapsed sub-section — 3,341px of a 4,340px
   * page at 390px, pre-filled and rarely touched. They are not in the document until opened, so
   * every assertion below that reads one has to open it first.
   *
   * By id rather than by accessible name: the identity section's subtitle is "ชื่อ
   * เลขประจำตัวผู้เสียภาษีอากร และที่อยู่ตามแบบ ล.ย.01", so a /ที่อยู่/ name query matches its
   * header as well and fails ambiguously.
   *
   * These still assert on the INPUTS rather than on the collapsed summary, deliberately: what is
   * under test is where the prefill comes from and which declaration wins, and an input is the
   * unambiguous read of that. That the values survive while the section stays SHUT — the thing
   * this collapse could plausibly have broken — is pinned separately, on the submit payload, in
   * TaxAllowanceForm.test.jsx.
   */
  function openAddress() {
    const header = document.getElementById('ta-section-address-header');
    if (header?.getAttribute('aria-expanded') === 'false') fireEvent.click(header);
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
      // rather than repeat the badge (which is what it used to do, word for word).
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

  // Regression (fix/tax-allowance-withdraw-preserves-values): the PENDING banner promises
  // "ยกเลิกการยื่นเพื่อแก้ไข" and the withdraw ConfirmDialog promises "กลับมาแก้ไข" -- both say the
  // employee gets their typed-in values back after withdrawing. Before this fix, withdrawal wiped
  // every one of the 21 fields to zero: WITHDRAWN sits in NON_CURRENT_STATUSES, so `current` became
  // null and `defaultAllowanceValues(null)` fed the form nothing but zeros. `selectResumableDeclaration`
  // now seeds the form from the newest WITHDRAWN row when there is no current declaration -- but must
  // NOT make a withdrawn declaration look filed (the badge stays ยังไม่ได้ยื่น).
  //
  // ⚠️ DO NOT BARRIER THESE TESTS ON THE STATUS BADGE. Every test below asserts on what the FORM
  // holds, and the badge does not gate that. Both badge barriers this block used to open its tests
  // with were satisfied by the FIRST PAINT, while the declarations query was still in flight -- so
  // the fixture had not reached the form at all and everything after them raced the query. Measured
  // on the render, not inferred:
  //
  //   `findByText('ยังไม่ได้ยื่น')` -- `current` is null while loading, so `taxAllowanceStatusInfo(null)`
  //       renders exactly that badge before any response exists. Satisfied on paint 1, every time.
  //
  //   `findByText(/อนุมัติแล้ว/)`  -- worse, because it never matched the badge at all. On paint 1 the
  //       regex has exactly ONE match and it is TaxAllowanceForm's static identity-section hint
  //       ("...จะบันทึกกลับเข้าทะเบียนก็ต่อเมื่อฝ่ายบุคคลอนุมัติแล้วเท่านั้น"), which renders immediately
  //       because the identity ข้อ is `defaultOpen`. Once the query settles there are TWO matches, so
  //       had the badge ever been the only candidate this would have thrown "found multiple elements"
  //       instead -- the ambiguity is precisely what let it resolve against static copy.
  //
  // Underneath that, form values arrive strictly later than any paint anyway: TaxAllowancePage
  // recomputes `defaultValues` and commits, and only then does TaxAllowanceForm's
  // `useEffect(() => reset(defaultValues))` -- a PASSIVE effect, one Scheduler macrotask after the
  // commit -- push them into react-hook-form. Clicking a ข้อ open before that flush mounts its input
  // against `defaultAllowanceValues(null)`, i.e. '0'.
  //
  // Both gaps were absorbed by the incidental await-room in the following `findByLabelText`, which is
  // the only reason these ever passed. On a loaded CI box the room runs out: PR #640 -- a leave-surface
  // change touching no tax-allowance file -- failed here with `expected '0' to be '60000'`, passed on a
  // re-run with zero code changes, 26/26 locally, 127s in CI against ~22s locally.
  //
  // So: barrier on something the FORM produces (a ข้อ's own `useWatch`-driven subtotal, or an
  // `editing`-gated control that the settle effect creates), never on a status badge. Assert the badge
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

      // THE barrier. ข้อ 7's own collapsed row shows the withdrawn declaration's figure, not ฿0.00 --
      // and it is rendered from `useWatch`, i.e. from react-hook-form's state, so it cannot appear
      // until `reset(defaultValues)` has actually run. That is what makes it a barrier and the badge
      // not one (see this block's header): it proves the declarations query settled AND fed
      // `resumable` through to the form, rather than proving only that something painted.
      //
      // ฿25,000 not ฿85,000: the withdrawn row's spouseAllowance is deliberately NOT shown, because
      // ล.ย.01 has no spouse amount box — HR sets that figure at review.
      expect(await screen.findByText('฿25,000.00')).not.toBeNull();

      // Status still reads "not yet filed", exactly as before this fix -- a withdrawn declaration is
      // not current standing, only its VALUES are offered back for editing. Deliberately asserted
      // AFTER the barrier above and with a synchronous `getByText`: as a leading `findByText` this
      // matched the in-flight paint (`current` is null while loading, which renders this same badge),
      // so it passed before the fixture had been applied and said nothing about the settled state.
      expect(screen.getByText('ยังไม่ได้ยื่น')).not.toBeNull();

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
            // Same allowances as the WITHDRAWN fixture in the test above, so status is the only thing
            // that differs between the two and the assertion below isolates exactly that.
            //
            // `lifeInsuranceAllowance` is the load-bearing half. This fixture used to carry
            // `spouseAllowance` alone -- the one declared value ล.ย.01 has no amount box for (see the
            // test above), so it can never reach ข้อ 7's field. The assertion therefore read '0'
            // whatever `selectResumableDeclaration` did, and mutation-checking proved it: teaching
            // that function to accept SUPERSEDED left this test GREEN. It was not weakly covering the
            // guard, it was incapable of failing on it.
            allowances: { spouseAllowance: 60000, lifeInsuranceAllowance: 25000 },
          }),
        ],
      });
      renderPage();

      // A test whose expected outcome is "nothing was prefilled" cannot barrier on a value appearing,
      // so it barriers on the page having SETTLED instead. The sign-off panel is `readOnly ? null :
      // ...`, and `readOnly` is `!editing` -- while `editing` is only ever set by the effect that
      // opens with `if (declarationsQuery.isLoading) return`. So this control existing means the query
      // resolved and the page acted on it.
      //
      // It is also strictly LATER than any prefill would be: React runs child effects before parent
      // effects, so TaxAllowanceForm's `reset(defaultValues)` has already run by the time
      // TaxAllowancePage's `setEditing` re-render puts this control on screen. A regression that made
      // SUPERSEDED resumable has therefore already landed in the field when this resolves -- which is
      // what makes the '0' below decide on BEHAVIOUR rather than on timing.
      //
      // The previous barrier, `findByText('ยังไม่ได้ยื่น')`, gave no such ordering: it is what an
      // IN-FLIGHT query renders too, so it was satisfied on the first paint and whether the fixture
      // had reached the form by the time '0' was read came down to whether a pending flush happened
      // to land. On a negative assertion that is the dangerous direction -- it fails SILENTLY, by
      // passing. Same defect as the #640 flake below, mirrored.
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

      // THE barrier -- and the #640 flake fix. This used to await `/อนุมัติแล้ว/`, which resolved on
      // the first paint against a static hint paragraph rather than the badge (see this block's
      // header), so the click below opened ข้อ 7 on a form the response had never reached and the
      // assertion read '0'.
      //
      // ข้อ 7's collapsed row renders `formatMoney` over `useWatch`'s values, so it carries what the
      // FORM holds. ฿60,000.00 here means `current` (the APPROVED row) won and is in form state --
      // the exact precondition the assertion below depends on.
      // Two matches now, not one: ข้อ 7's own subtotal AND the declared total beneath the sections
      // (read-only lost the sign-off panel that used to be the total's only home). `findAllByText`
      // rather than `findByText`, which throws on more than one hit.
      expect(await screen.findAllByText('฿60,000.00')).toHaveLength(2);
      // Now a real assertion rather than a race. The exact badge label, not `/อนุมัติแล้ว/`: that
      // regex also matches the identity ข้อ's static hint, which is what made it useless as a barrier.
      expect(screen.getByText('อนุมัติแล้ว — ยังไม่ใช้กับเงินเดือน')).not.toBeNull();

      fireEvent.click(screen.getByRole('button', { name: /ข้อ 7/ }));
      expect(await screen.findByLabelText('จำนวนเงิน')).not.toBeNull();
      // 60,000 (the APPROVED row), not 12,345 (the later WITHDRAWN one).
      expect(screen.getByLabelText('จำนวนเงิน').value).toBe('60000');
    });
  });

  // ---------------------------------------------------------------------------------------------
  // Revising an ALREADY-CONFIRMED declaration, and getting a copy of it.
  //
  // Both were missing, and neither was a backend limitation:
  //   - `TaxAllowanceDeclarationService#submitOwn` refuses only on `existsPending`, and `#approve`
  //     calls `supersedeApproved` first (decision #7, "a new submission supersedes the previous
  //     once approved") — so a revision has always been accepted by the server.
  //   - `GET /declarations/{id}/form.pdf` and `hrApi.downloadTaxAllowanceForm` existed with ZERO
  //     call sites, so a filed declaration could not be downloaded at all.
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

    /**
     * The header-prefill invariant, wrong-way-round. A confirmed declaration shows WHAT WAS FILED —
     * seeding its blank header from today's employee master would show the employee an address they
     * never declared, on a document HR has already accepted. Making it revisable must not leak the
     * prefill into the read-only view it is still sitting in.
     */
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
      openAddress();
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

      // Submitting now opens a confirmation first (owner ruling 2026-08-09) — the button no longer
      // files anything on its own. Scoped to the dialog: the page's own submit button carries the
      // same label, and picking "the last match" instead would silently re-click the wrong one.
      const dialog = await screen.findByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยื่นแบบแจ้ง' }));

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

  // -------------------------------------------------------------------------------------------
  // Header prefill (owner decision #4, the read half). The write-back shipped in #621; until this
  // landed the identity fields opened empty and every employee retyped a 13-digit tax ID and a
  // 13-part address on every filing.
  //
  // The gate is `canStartEdit`, so the interesting assertions are the NEGATIVE ones: a prefill
  // must not appear on a declaration that has already been filed, because those fields would then
  // show today's master record against a document HR already accepted.
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
      openAddress();
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
      // ข้อมูลผู้มีเงินได้ is collapsed on a read-only declaration, so the slots have to be
      // disclosed before they can be read — being blank is still the assertion.
      openIdentity();
      expect(document.getElementById('ta-taxpayer-id').value).toBe('');
      openAddress();
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
      openAddress();
      expect(document.getElementById('ta-addr-province').value).toBe('');
    });

    it('lets a REJECTED declaration\'s own header win over the master', async () => {
      // The employee is re-preparing a filing they already corrected once. Seeding the master over
      // the top would revert that correction under them.
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
      // Read the header slots straight from the READ-ONLY view rather than pressing
      // "แก้ไข / ยื่นฉบับใหม่" first. What is under test is which source won in `defaultValues`, and
      // that is decided by `canStartEdit` — true for REJECTED in the current year whether or not the
      // button has been pressed — so the read-only view carries the same values the editable one
      // will. Pressing it here would not help anyway: jsdom flushes the page's own
      // `setEditing(statusInfo.key === 'NONE')` effect AFTER the click, which puts `editing` back to
      // false. (A real browser flushes it before, so the button works there — verified in Chromium.)
      openIdentity();
      await waitFor(() => {
        expect(document.getElementById('ta-taxpayer-id').value).toBe('9999999999999');
      });
      openAddress();
      expect(document.getElementById('ta-addr-houseNo').value).toBe('77/7');
      // ...while the slots that declaration left blank still come from the master.
      expect(document.getElementById('ta-addr-province').value).toBe('กรุงเทพมหานคร');
    });

    it('survives a response with no headerPrefill at all — the form still opens', async () => {
      // The pre-#627 wire shape, and also what an older cached response looks like. A crash here
      // would take the whole page down for a field that is only ever a convenience.
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      renderPage();

      expect(await screen.findByText('ยังไม่ได้ยื่น')).not.toBeNull();
      // `findByText` above resolves on the FIRST paint — the badge already reads ยังไม่ได้ยื่น while
      // the query is in flight — so this has to wait for the form to actually become editable
      // before reading a slot that only the editable form discloses.
      await waitFor(() => {
        expect(document.getElementById('ta-taxpayer-id')).not.toBeNull();
      });
      expect(document.getElementById('ta-taxpayer-id').value).toBe('');
    });
  });

  // -------------------------------------------------------------------------------------------
  // Confirm before filing (owner ruling 2026-08-09).
  //
  // Measured in a real browser first: once the signed scan is staged the submit button is enabled,
  // and HTML's implicit submission then hands Enter in any text field a real `event.submitter`, so
  // both of SafeForm's guards pass and Enter filed the declaration outright.
  //
  // The ruling was explicitly NOT an Enter-specific guard. The hazard is "files with no
  // confirmation", and an Enter-only fix leaves a stray click on the enabled button just as
  // exposed. The dialog sits between the submit EVENT and the mutation, so every trigger — Enter,
  // click, anything added later — arrives through the same gate.
  //
  // ⚠️ jsdom cannot perform implicit submission at all, so nothing in THIS file exercises the
  // trigger that prompted the ruling. What it does prove is the property that makes the trigger
  // harmless: a submit event does not reach the API on its own. The Enter path itself is covered
  // in frontend/e2e-real/loryor01-form.spec.js, in a real browser.
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
      // Scoped to the dialog on purpose: the sign-off panel's own step list also contains
      // "ส่งให้ฝ่ายบุคคลตรวจสอบ", and an unscoped match reads that instead and passes for the
      // wrong reason — which is exactly what the first draft of this test did.
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
      // Still editable, still holding the staged signed form — dismissing returns you to the form,
      // it does not reset it. `getAllByText`: the filename legitimately appears twice, once in
      // FileUploadField's picked-file echo and once in the staged-evidence list.
      expect(screen.getAllByText('signed.pdf').length).toBeGreaterThan(0);
      expect(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }).disabled).toBe(false);
    });

    it('confirming files exactly the declaration the form was holding', async () => {
      api.payroll.submitMyTaxAllowanceDeclaration.mockResolvedValue({
        declarationId: 77, employeeId: 9, status: 'PENDING',
      });
      const submit = await fillToSubmittable();

      // A value typed before the dialog opened must survive into what is actually filed. The gate
      // holds the submitted values, and handing the mutation a stale or empty snapshot is the
      // obvious way to get this refactor subtly wrong.
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
      // The /me endpoint never carries an employeeId — the server resolves the caller from the
      // session. Asserted here because this change moved the body-building call site.
      expect(api.payroll.submitMyTaxAllowanceDeclaration.mock.calls[0][0].employeeId).toBeUndefined();
    });
  });
  /**
   * showToast's real signature is `showToast(kind, message)` (hooks/useToast.js). Getting the two
   * the wrong way round is silent: `sanitizeToastMessage` returns `message` untouched whenever
   * `kind !== 'error'`, so a reversed call renders the literal string "success" as the toast body
   * and hands the Thai sentence to Toast.jsx as the styling kind. Nothing throws.
   *
   * ⚠️ The page is handed `showToast` as a prop and every other test passes a bare `vi.fn()` that
   * nothing asserts on — which is why the suite happily shipped this reversed for two PRs. A spy
   * accepts any argument order. Assert the ARGUMENTS, not just that it was called.
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
