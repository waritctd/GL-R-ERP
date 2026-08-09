import { test, expect } from '@playwright/test';
import { apiSessionFor } from './helpers/api.js';
import { loginAs } from './helpers/auth.js';

// ─────────────────────────────────────────────────────────────────────────────
// /tax-allowance IN A REAL BROWSER — gap 9 of the ล.ย.01 programme.
//
// WHY THIS SPEC EXISTS. jsdom does not implement the browser's HTML "implicit submission"
// algorithm at all (SafeForm.jsx's own header: fireEvent.keyDown/keyPress/keyUp('Enter') fires no
// submit event whatsoever under jsdom — verified empirically, not assumed). TaxAllowanceForm.jsx
// now puts roughly a dozen text/number inputs inside ONE <form> with ONE submit button, and that
// exact shape has already shipped this exact bug TWICE in this repo — SafeForm.jsx names both
// incidents by name, and the FIRST one was this page: "/tax-allowance filed a real tax
// declaration" the moment a redesign narrowed its blocking-field count. TaxAllowanceForm.test.jsx's
// own "a submit event with no submitter does nothing" test says so explicitly in its doc comment:
// "the case that actually matters — Enter in one of a dozen text fields, which HTML turns into a
// real implicit submission — CANNOT be reproduced here at all: jsdom does not implement implicit
// submission. That one needs e2e-real". This file is that.
//
// The mock e2e suite used to cover this shape generically (`e2e/implicit-submission.spec.js`, 5
// tests), but `frontend/e2e/` was deleted whole on 2026-08-08 (CLAUDE.md, "There is only one e2e
// suite now") — so this file is now the ONLY place implicit-submission behaviour is checked in a
// real browser anywhere in the app, not just on this page.
//
// WHAT EACH TEST PINS, AND WHAT IT DOES NOT — read this before trusting a green run:
//   1. The page loads through the real stack (Vite → Spring → Postgres) for a plain employee — the
//      heading renders, no React error boundary, no 5xx. Independent of every state below.
//   2. A collapsed ข้อ's typed value survives collapse + re-expand. CollapsibleSection unmounts its
//      children, so this only works because react-hook-form defaults `shouldUnregister: false`.
//      TaxAllowanceForm.test.jsx ALREADY pins the react-hook-form mechanics of this under jsdom
//      ("a value typed into a ข้อ survives collapsing it") — jsdom can simulate mount/unmount/
//      re-register fine; it only fails at implicit submission. This test is real-browser
//      corroboration through the ACTUAL page (real CollapsibleSection CSS/DOM, real
//      TaxAllowancePage data-fetch wiring), not a newly-discovered gap — said plainly so a reader
//      doesn't credit it with more novelty than it has.
//   3. THE HEADLINE TEST. Pressing Enter in a real, focused text field must not POST the
//      declaration — asserted on the network (a request listener / waitForRequest), not just the
//      DOM, because a DOM-only assertion can pass while a request already went out.
//
//      ⚠️ READ THE SCOPE OF THIS ONE CAREFULLY. It covers Enter **while the submit button is
//      disabled**, i.e. before a signed form is staged. That is a real state an employee spends
//      most of their time in — the whole form is filled in before the signed scan is attached last
//      — and it is the state the historical incident occurred in. It is NOT a general "Enter can
//      never file this declaration" guarantee, and this comment used to claim that it was.
//
//      MEASURED, 2026-08-09, real Chromium against the real stack: with the signed form attached
//      and the submit button therefore ENABLED, Enter in `#ta-taxpayer-id` fires exactly one
//      `POST /api/payroll/tax-allowances/declarations/me` and files the declaration for real.
//      Verified with a throwaway probe spec, not inferred. That follows from SafeForm.jsx's own
//      documented mechanism: a form that HAS a default submit button gives implicit submission a
//      real `event.submitter`, so SafeForm's submitter check passes, and `canSubmit` is true in
//      that state by construction. Whether that is acceptable is a PRODUCT decision (Enter
//      activating a visible, enabled submit button is ordinary HTML), so it is recorded here rather
//      than silently "fixed" or, worse, pinned by a test as though it were the intended design.
//      Deliberately not covered by a test either way: a test of that state files a real declaration
//      on a shared database, and `uq_tad_one_pending_per_employee_year` then makes every later run
//      of tests 2–4 skip (see below).
//
//      In the state this test DOES cover, two guards are active at once: SafeForm.jsx documents
//      (verified empirically in real Chromium) that a sole default submit button which is DISABLED
//      makes the browser fire no native 'submit' event at all, and SafeForm's `canSubmit` check
//      would refuse a submit if one somehow fired anyway. This test cannot tell those two layers
//      apart and does not try to.
//   4. The submit button stays disabled until a signed-form file is staged, then enables.
//      TaxAllowanceForm.test.jsx already pins the PROP wiring (`signedFormAttached` → disabled) in
//      isolation via a manual rerender. This test additionally exercises the real pipeline that
//      prop depends on: CollapsibleSection expand → FileUploadField's real <input type="file"> →
//      TaxAllowanceEvidencePanel's staging path → TaxAllowancePage's `stagedEvidence` state →
//      `signedFormAttached` → TaxAllowanceForm's `canSubmit`. Never clicks submit — see below.
//
// SAFETY / RE-RUNNABILITY — this suite has no per-test database reset (README.md, "How the write
// specs stay safe on a shared database"), so this file is written to need none, and is safe to run
// back-to-back against an already-dirtied database:
//   • no test here ever sends a POST/PUT/PATCH/DELETE to the tax-allowance API. Tests 1–3 are pure
//     GET-and-observe; test 4 stages a file into REACT STATE ONLY (TaxAllowanceEvidencePanel's
//     'staging' mode — TaxAllowancePage.jsx's own doc comment: staged files upload only from
//     `submitMutation.onSuccess`, which nothing here ever triggers). The staged file is discarded
//     with the page when the test ends; no server-side row is ever created, so there is nothing to
//     withdraw or clean up;
//   • tests 2–4 need the form to be EDITABLE, which the employee's current-tax-year declaration
//     status may or may not allow (TaxAllowancePage.jsx's EDITABLE_STATUS_KEYS = NONE / REJECTED /
//     EXPIRED only — PENDING and both APPROVED variants are permanently read-only from the
//     employee's side). `beforeAll` below reads that status fresh from the real API before any
//     browser test runs, and skips 2–4 with an explicit reason if none of those three applies —
//     deterministic either way. It never tries to force the database into an editable state: in
//     particular it never withdraws a PENDING declaration, because this spec creates nothing, so
//     any PENDING/APPROVED row it finds belongs to something else and is not this spec's to touch.
//     ⚠️ THE COST OF THAT CHOICE: a skipped test reads as green. If demo.employee ever ends up with
//     a PENDING or APPROVED current-year declaration, the three tests that matter here vanish from
//     the run and nothing fails. Confirmed by observation, not theory — after a probe filed one
//     declaration, a re-run reported "1 passed, 3 skipped" and exit 0. CI is unaffected (each job
//     gets a brand-new Postgres, so the seed state is always NONE), but a LOCAL green run is only
//     evidence if you read the skip count. `frontend/scripts/reset-e2e-db.mjs` restores a pristine
//     seed;
//   • test 2 always `.fill()`s an explicit, known value rather than depending on the field starting
//     empty, so a field pre-populated by a leftover REJECTED/EXPIRED declaration from an earlier
//     run does not change the assertion.
//
// NAVIGATION. `page.goto()` is used directly (not the SPA `spaGoto` history-push helper) — per
// helpers/auth.js's own comment, that is a speed choice for route SWEEPS, not a correctness
// requirement here: this suite's session lives in Postgres, so a hard navigation is safe, and
// smoke.spec.js already does exactly this (`loginAs` then `page.goto(path)`) for the same kind of
// single-page, real-data deep dive this file is.
//
// VIEWPORT. `loginAs()` needs a desktop viewport or `inert` hides subtrees and elements become
// unclickable. Nothing here sets one explicitly because nothing in e2e-real/ needs to: the suite's
// only project (playwright.real.config.js) is `devices['Desktop Chrome']`, already desktop-sized.
// ─────────────────────────────────────────────────────────────────────────────

const DECLARATIONS_ME_PATH = '/api/payroll/tax-allowances/declarations/me';
const CAPS_PATH = '/api/payroll/tax-allowances/caps';

// A declaration's raw backend `status` stops being "the employee's current standing" once it is
// SUPERSEDED or WITHDRAWN — mirrors taxAllowanceStatus.js's NON_CURRENT_STATUSES /
// selectCurrentDeclaration. Cited rather than imported: this file deliberately imports no
// application source, so everything it asserts is observable the same way a real user or the real
// API sees it, not coupled to an internal module staying import-compatible.
const NON_CURRENT_DECLARATION_STATUSES = new Set(['SUPERSEDED', 'WITHDRAWN']);

/**
 * The status of "the employee's current standing" for a tax year — the most recently submitted
 * non-terminal row, or 'NONE' if every row is terminal or there are none. See
 * NON_CURRENT_DECLARATION_STATUSES above for why SUPERSEDED/WITHDRAWN are excluded from
 * "current" before this even looks at submittedAt.
 */
function currentDeclarationStatus(items = []) {
  const candidates = items.filter((item) => !NON_CURRENT_DECLARATION_STATUSES.has(item.status));
  if (candidates.length === 0) return 'NONE';
  return [...candidates].sort((a, b) =>
    String(b.submittedAt || '').localeCompare(String(a.submittedAt || ''))
  )[0].status;
}

/**
 * Navigates to /tax-allowance and waits for the declarations fetch that decides whether the form
 * is editable — the same call TaxAllowancePage.jsx's own declarationsQuery makes
 * (getMyTaxAllowanceDeclarations). Waiting for it (rather than `page.goto` and hoping) is what
 * makes the editability checks below deterministic instead of racing the query.
 */
async function openTaxAllowancePage(page) {
  const [response] = await Promise.all([
    page.waitForResponse((candidate) =>
      new URL(candidate.url()).pathname === DECLARATIONS_ME_PATH &&
      candidate.request().method() === 'GET'
    ),
    page.goto('/tax-allowance'),
  ]);
  await expect(page.getByRole('heading', { level: 1, name: /ล\.ย\.01/ })).toBeVisible();
  return response;
}

/**
 * Gets the form into its editable state, regardless of which editable status produced it. NONE
 * starts editable automatically (TaxAllowancePage.jsx's effect:
 * `setEditing(statusInfo.key === 'NONE' && isCurrentYear)`); REJECTED/EXPIRED need
 * "แก้ไข / ยื่นฉบับใหม่" clicked first. Callers must already know — via the `beforeAll` editability
 * check below — that one of NONE/REJECTED/EXPIRED applies, or this hangs waiting for a field that
 * will never enable.
 *
 * The edit button's own visibility is awaited with a bounded timeout (not a snapshot check) so
 * this is correct in BOTH directions: it waits out the query-settling window for REJECTED/EXPIRED
 * (where the button appears only once `declarationsQuery` resolves), and still resolves promptly
 * for NONE (where the button never appears at all, because editing already started true).
 */
async function enterEditMode(page) {
  const editButton = page.getByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' });
  const editButtonAppeared = await editButton
    .waitFor({ state: 'visible', timeout: 3_000 })
    .then(() => true)
    .catch(() => false);
  if (editButtonAppeared) {
    await editButton.click();
  }
  await expect(page.locator('#ta-taxpayer-id')).toBeEnabled();
}

test.describe('ล.ย.01 tax-allowance form — real browser (jsdom cannot see this)', () => {
  /** @type {{ canEdit: boolean, reason: string }} */
  let editability;

  test.beforeAll(async () => {
    const currentYear = new Date().getFullYear();
    const session = await apiSessionFor('employee');
    try {
      const response = await session.get(`${DECLARATIONS_ME_PATH}?year=${currentYear}`);
      if (!response.ok()) {
        throw new Error(
          `GET ${DECLARATIONS_ME_PATH}?year=${currentYear} returned ${response.status()} while ` +
          `checking demo.employee's tax-allowance state before the browser tests run: ` +
          `${await response.text()}`
        );
      }
      const { items } = await response.json();
      const status = currentDeclarationStatus(items ?? []);
      const canEdit = ['NONE', 'REJECTED', 'EXPIRED'].includes(status);
      editability = {
        canEdit,
        reason: canEdit ? '' :
          `demo.employee's ${currentYear} ล.ย.01 declaration is currently ${status} — ` +
          'TaxAllowancePage.jsx only lets NONE/REJECTED/EXPIRED be edited (EDITABLE_STATUS_KEYS); ' +
          'PENDING and both APPROVED variants render permanently read-only, so there is no ' +
          'editable field or submit button here to exercise. This spec never submits anything ' +
          '(see file header), so it did not create this state, and will not withdraw or clear a ' +
          'declaration it does not own just to unblock itself.',
      };
    } finally {
      await session.dispose();
    }
  });

  test('loads for a plain employee — no error boundary, no 5xx', async ({ page }) => {
    await loginAs(page, 'employee');

    const serverErrors = [];
    page.on('response', (response) => {
      if (response.status() >= 500 && response.url().includes('/api/')) {
        serverErrors.push(`${response.status()} ${new URL(response.url()).pathname}`);
      }
    });

    const declarationsResponse = await openTaxAllowancePage(page);
    expect(declarationsResponse.status(), 'GET .../declarations/me').toBe(200);

    // The other background fetch this page always makes (field-cap captions). Waited for
    // explicitly, with its own bounded timeout, so a 5xx from it specifically is not missed by
    // the assertion below racing ahead of the response arriving.
    await page
      .waitForResponse((r) => new URL(r.url()).pathname === CAPS_PATH, { timeout: 5_000 })
      .catch(() => {});

    // ErrorBoundary's DefaultErrorFallback text — the same locator route-coverage.spec.js uses to
    // detect a route that crashed while rendering.
    await expect(page.getByText('โหลดหน้านี้ไม่สำเร็จ')).toHaveCount(0);
    expect(serverErrors, '5xx responses while loading /tax-allowance').toEqual([]);
  });

  test('a numeric ข้อ keeps its typed value across collapse and re-expand', async ({ page }) => {
    test.skip(!editability.canEdit, editability.reason);

    await loginAs(page, 'employee');
    await openTaxAllowancePage(page);
    await enterEditMode(page);

    // ข้อ 7 · เบี้ยประกันชีวิตที่จ่ายภายในปีภาษี — one field, kind 'money', id `ta-lifeInsuranceAllowance`
    // (taxAllowanceSchema.js LOR_YOR_01_SECTIONS, section key 'item7'). Starts collapsed:
    // CollapsibleSection's `id` prop is `ta-section-item7`, which becomes `ta-section-item7-header`
    // on the toggle <button> and `ta-section-item7-body` on the body — the <section> itself carries
    // no bare id, only those two derived ones (CollapsibleSection.jsx).
    const header = page.locator('#ta-section-item7-header');
    const field = page.locator('#ta-lifeInsuranceAllowance');

    await expect(header).toHaveAttribute('aria-expanded', 'false');
    await header.click();
    await expect(header).toHaveAttribute('aria-expanded', 'true');
    await expect(field).toBeVisible();

    await field.fill('12345');
    await expect(field).toHaveValue('12345');

    await header.click(); // collapse — CollapsibleSection unmounts `children` here
    await expect(header).toHaveAttribute('aria-expanded', 'false');
    await expect(field).toHaveCount(0);

    await header.click(); // re-expand
    await expect(field).toBeVisible();
    // THE assertion: the value is back, even though the <input> was just unmounted and remounted.
    // Only true because react-hook-form defaults shouldUnregister:false — see file header.
    await expect(field).toHaveValue('12345');
  });

  test('pressing Enter in a text field must not file the declaration', async ({ page }) => {
    test.skip(!editability.canEdit, editability.reason);

    await loginAs(page, 'employee');
    await openTaxAllowancePage(page);
    await enterEditMode(page);

    // Deliberately BEFORE any signed-form file is staged: canSubmit (and therefore the submit
    // button) is false/disabled in this state, which is exactly what makes this the safest test in
    // the file — see the file header's safety note for why that does not make it a vacuous test of
    // the OBSERVABLE behaviour that actually matters.
    const submitButton = page.locator('form#tax-allowance-form button[type="submit"]');
    await expect(submitButton).toBeDisabled();

    const postedSubmissions = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === DECLARATIONS_ME_PATH) {
        postedSubmissions.push(request.url());
      }
    });

    // Field 1 — the always-visible identity field.
    const taxpayerId = page.locator('#ta-taxpayer-id');
    await taxpayerId.fill('1234567890123');
    await taxpayerId.press('Enter');

    // Field 2 — a SECOND, independently-rendered text field elsewhere in the same <form>. The
    // historical bug's own shape was "more than one blocking field until a redesign narrowed it to
    // one" (SafeForm.jsx) — worth showing the invariant holds for more than just the first field on
    // the page. ข้อ 15's "ระบุ" is a plain type="text" input (taxAllowanceSchema.js, field key
    // `lorYor01.otherDonationNote` → id `ta-lorYor01-otherDonationNote`).
    await page.locator('#ta-section-item15-header').click();
    const donationNote = page.locator('#ta-lorYor01-otherDonationNote');
    await expect(donationNote).toBeVisible();
    await donationNote.fill('e2e: must not submit on Enter');
    await donationNote.press('Enter');

    // A bounded wait for the SPECIFIC bad thing (not an arbitrary sleep): resolves immediately if
    // either Enter press above wrongly triggered a POST, otherwise times out harmlessly. This is
    // what actually proves the negative — the passive listener above only has something to report
    // once whatever async work a wrongly-fired submit would kick off has had a real chance to run.
    await page
      .waitForRequest(
        (request) =>
          request.method() === 'POST' && new URL(request.url()).pathname === DECLARATIONS_ME_PATH,
        { timeout: 2_500 }
      )
      .catch(() => {});

    expect(postedSubmissions, 'Enter in a text field POSTed the declaration').toEqual([]);
    // Corroborating DOM signal, independent of the network assertion above: a real submission's
    // onSuccess calls `setEditing(false)`, which would disable every field, this one included.
    // Still enabled == still editing == no submission went through.
    await expect(taxpayerId).toBeEnabled();
    await expect(taxpayerId).toHaveValue('1234567890123');
    await expect(page.getByText('ยื่นแบบแจ้งเรียบร้อย', { exact: false })).toHaveCount(0);
    expect(page.url(), 'must not have navigated away').toContain('/tax-allowance');
  });

  test('submit stays disabled until a signed, scanned form is attached', async ({ page }) => {
    test.skip(!editability.canEdit, editability.reason);

    await loginAs(page, 'employee');
    await openTaxAllowancePage(page);
    await enterEditMode(page);

    const submitButton = page.locator('form#tax-allowance-form button[type="submit"]');
    await expect(submitButton).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // "ตรวจทาน ลงนาม และยื่น" starts collapsed (TaxAllowanceForm.jsx: defaultOpen={false}) — expand
    // it to reach the signed-form evidence panel's file input.
    await page.locator('#ta-section-sign-header').click();

    // id = `tax-allowance-evidence-file-${sectionKey ?? 'general'}` with sectionKey="signed_form"
    // (TaxAllowanceEvidencePanel.jsx / FileUploadField.jsx). Visually sr-only but a real, attached
    // <input type="file"> — setInputFiles only requires "attached", not visible, so no click on the
    // styled label it sits behind is needed.
    const signedFormInput = page.locator('#tax-allowance-evidence-file-signed_form');
    await expect(signedFormInput).toBeAttached();

    // In-memory buffer, never a real file off disk (safety instructions). application/pdf so
    // TaxAllowanceEvidencePanel#prepareFile passes it straight through — no image-compression path
    // (browser-image-compression + a web worker) for this test to depend on.
    await signedFormInput.setInputFiles({
      name: 'signed-loryor01.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\ne2e stub — staged client-side only, never uploaded\n'),
    });

    // Staged, not uploaded: TaxAllowanceEvidencePanel's 'staging' mode holds the file in
    // TaxAllowancePage's React state (`stagedEvidence`) rather than POSTing it anywhere. "รอส่ง"
    // ("pending send") only ever renders on a STAGED item, never on FileUploadField's own picked-
    // filename echo — a precise signal that the staging path specifically ran, not merely that the
    // native file input changed.
    await expect(page.getByText('รอส่ง')).toBeVisible();
    await expect(page.getByText('แนบแบบที่ลงนามแล้ว')).toBeVisible();

    // THE assertion: the exact same button that was disabled above is now enabled, driven purely by
    // the file just staged (TaxAllowancePage's `signedFormAttached` → TaxAllowanceForm's
    // `canSubmit`) — nothing else about the page changed.
    await expect(submitButton).toBeEnabled();

    // Deliberately never clicked. `submitMutation` only fires on a real click/submit, and
    // TaxAllowancePage only flushes staged evidence from that mutation's `onSuccess` — so stopping
    // here means nothing was ever sent to the server, and there is nothing to clean up.
  });
});
