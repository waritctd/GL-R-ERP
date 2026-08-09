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

      // Only one bucket survives the per-ข้อ evidence removal: the signed form itself. The staging
      // behaviour still matters — a file picked while the declaration has no id yet must reach the
      // server, tagged, once submit creates one.
      fireEvent.click(await screen.findByRole('button', { name: /ตรวจทาน ลงนาม และยื่น/ }));
      const signed = new File(['y'], 'signed.pdf', { type: 'application/pdf' });
      fireEvent.change(await findPicker(), { target: { files: [signed] } });
      await screen.findByText('signed.pdf');

      // Held client-side: there is no declarationId to upload against yet.
      expect(api.payroll.uploadTaxAllowanceAttachment).not.toHaveBeenCalled();

      const submit = screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' });
      await waitFor(() => expect(submit.disabled).toBe(false));
      fireEvent.click(submit);
      const dialog = await screen.findByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยื่นแบบแจ้ง' }));

      await waitFor(() => expect(api.payroll.submitMyTaxAllowanceDeclaration).toHaveBeenCalled());
      await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment)
        .toHaveBeenCalledWith(77, signed, 'signed_form'));
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

      // Staged through the signed-form panel — the only attachment bucket left after the per-ข้อ
      // หลักฐานแสดงสิทธิ panels were removed. What this test guards is unchanged: a year switch must
      // not silently discard a file the employee has picked but not yet sent.
      fireEvent.click(await screen.findByRole('button', { name: /ตรวจทาน ลงนาม และยื่น/ }));
      const file = new File(['x'], 'signed.pdf', { type: 'application/pdf' });
      fireEvent.change(await findPicker(), { target: { files: [file] } });
      await screen.findByText('signed.pdf');

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
      expect(document.getElementById('ta-taxpayer-id').value).toBe('');
      expect(document.getElementById('ta-addr-houseNo').value).toBe('');
    });

    it('does NOT seed a past tax year, even though that year is read-only anyway', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [], headerPrefill });
      renderPage({ entry: `/tax-allowance?year=${currentYear - 1}` });

      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
      await waitFor(() => {
        expect(document.getElementById('ta-taxpayer-id')).not.toBeNull();
      });
      expect(document.getElementById('ta-taxpayer-id').value).toBe('');
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

      fireEvent.click(await screen.findByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' }));

      await waitFor(() => {
        expect(document.getElementById('ta-taxpayer-id').value).toBe('9999999999999');
      });
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
