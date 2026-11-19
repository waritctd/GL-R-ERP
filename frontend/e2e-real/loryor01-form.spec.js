import { test, expect } from '@playwright/test';
import { apiSessionFor } from './helpers/api.js';
import { loginAs } from './helpers/auth.js';

// ─────────────────────────────────────────────────────────────────────────────
// /tax-allowance IN A REAL BROWSER: HTML implicit submission (Enter in a text field) is not implemented by jsdom,
// and this exact shape has already filed a real tax declaration twice (SafeForm.jsx names both incidents).
//   1. Enter in a text field while submit is disabled must not POST the declaration (network-asserted).
//   2. Enter with the signed form staged and submit ENABLED opens the confirmation and files nothing.
// Neither test ever sends a write: the staged file lives in React state only, so the shared database stays clean.
// Both need an EDITABLE form (NONE/REJECTED/EXPIRED); beforeAll reads the status and skips with a reason otherwise,
// so a LOCAL green run is only evidence if you read the skip count (CI gets a fresh Postgres, always NONE).
// ─────────────────────────────────────────────────────────────────────────────

const DECLARATIONS_ME_PATH = '/api/payroll/tax-allowances/declarations/me';

// A declaration's raw backend `status` stops being "the employee's current standing" once it is SUPERSEDED or
// WITHDRAWN — mirrors taxAllowanceStatus.js's NON_CURRENT_STATUSES / selectCurrentDeclaration.
const NON_CURRENT_DECLARATION_STATUSES = new Set(['SUPERSEDED', 'WITHDRAWN']);

/** The status of "the employee's current standing" for a tax year — the most recently submitted non-terminal
 * row, or 'NONE' if every row is terminal or there are none. */
function currentDeclarationStatus(items = []) {
  const candidates = items.filter((item) => !NON_CURRENT_DECLARATION_STATUSES.has(item.status));
  if (candidates.length === 0) return 'NONE';
  return [...candidates].sort((a, b) =>
    String(b.submittedAt || '').localeCompare(String(a.submittedAt || ''))
  )[0].status;
}

/** Navigates to /tax-allowance and waits for the declarations fetch that decides whether the form is editable —
 * the same call TaxAllowancePage.jsx's own declarationsQuery makes (getMyTaxAllowanceDeclarations). */
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

/** Gets the form into its editable state, regardless of which editable status produced it. Callers must already
 * know — via the `beforeAll` editability check below — that one of NONE/REJECTED/EXPIRED applies, or this
 * hangs waiting for a field that will never enable. */
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

  test('pressing Enter in a text field must not file the declaration', async ({ page }) => {
    test.skip(!editability.canEdit, editability.reason);

    await loginAs(page, 'employee');
    await openTaxAllowancePage(page);
    await enterEditMode(page);

    // Deliberately BEFORE any signed-form file is staged: canSubmit is false/disabled in this state, which is
    // exactly what makes this the safest test in the file — see the file header's safety note for why that does
    // not make it a vacuous test of the OBSERVABLE behaviour that actually matters.
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

    // Field 2 — a SECOND, independently-rendered text field elsewhere in the same <form>.
    await page.locator('#ta-section-item15-header').click();
    const donationNote = page.locator('#ta-lorYor01-otherDonationNote');
    await expect(donationNote).toBeVisible();
    await donationNote.fill('e2e: must not submit on Enter');
    await donationNote.press('Enter');

    // A bounded wait for the SPECIFIC bad thing (not an arbitrary sleep): resolves immediately if either Enter
    // press above wrongly triggered a POST, otherwise times out harmlessly.
    await page
      .waitForRequest(
        (request) =>
          request.method() === 'POST' && new URL(request.url()).pathname === DECLARATIONS_ME_PATH,
        { timeout: 2_500 }
      )
      .catch(() => {});

    expect(postedSubmissions, 'Enter in a text field POSTed the declaration').toEqual([]);
    // Corroborating DOM signal, independent of the network assertion above: a real submission's onSuccess calls
    // `setEditing(false)`, which would disable every field, this one included. Still enabled == still editing ==
    // no submission went through.
    await expect(taxpayerId).toBeEnabled();
    await expect(taxpayerId).toHaveValue('1234567890123');
    await expect(page.getByText('ยื่นแบบแจ้งเรียบร้อย', { exact: false })).toHaveCount(0);
    expect(page.url(), 'must not have navigated away').toContain('/tax-allowance');
  });

  /**
   * The other half of the Enter story, and the one that would otherwise file a real declaration.
   *
   * The test above stops at "the button is now enabled". THAT is the state in which Enter is
   * dangerous: with a default submit button present and enabled, HTML's implicit submission hands
   * the event a real `event.submitter`, so SafeForm's submitter guard passes, and `canSubmit` is
   * true by construction — both guards satisfied, declaration filed, no confirmation.
   *
   * Rule: confirm before filing on ANY trigger, not an Enter-specific guard — the hazard is
   * "files with no confirmation", and an Enter-only fix leaves a stray click equally exposed. So
   * Enter still submits, exactly like ordinary HTML; the submit opens a dialog instead of
   * calling the API.
   *
   * ⚠️ This test is only safe to run on a shared database BECAUSE of that confirmation. Dismissing the
   * dialog means nothing is created, so there is no PENDING row to withdraw and no
   * `uq_tad_one_pending_per_employee_year` collision to make every later run of this file skip. An
   * Enter-blocking implementation would have been untestable here without filing something real.
   */
  test('Enter with the submit button ENABLED opens the confirmation and files nothing', async ({ page }) => {
    test.skip(!editability.canEdit, editability.reason);

    await loginAs(page, 'employee');
    await openTaxAllowancePage(page);
    await enterEditMode(page);

    // Get into the dangerous state: signed form staged, submit button live.
    await page.locator('#ta-section-sign-header').click();
    await page.locator('#tax-allowance-evidence-file-signed_form').setInputFiles({
      name: 'signed-loryor01.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\ne2e stub — staged client-side only, never uploaded\n'),
    });
    const submitButton = page.locator('form#tax-allowance-form button[type="submit"]');
    await expect(submitButton).toBeEnabled();

    const postedSubmissions = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === DECLARATIONS_ME_PATH) {
        postedSubmissions.push(request.url());
      }
    });

    const taxpayerId = page.locator('#ta-taxpayer-id');
    await taxpayerId.fill('1234567890123');
    await taxpayerId.press('Enter');

    // Enter reaches the gate, not the API. The dialog is the observable proof that the submit event really did
    // fire — without it this test could pass simply because Enter did nothing at all, which would make it evidence
    // about the wrong thing entirely.
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('ส่งให้ฝ่ายบุคคลตรวจสอบ');

    // Same bounded race as the disabled-state test: resolves at once if a POST slipped through.
    await page
      .waitForRequest(
        (request) =>
          request.method() === 'POST' && new URL(request.url()).pathname === DECLARATIONS_ME_PATH,
        { timeout: 2_500 },
      )
      .catch(() => {});
    expect(postedSubmissions, 'Enter filed the declaration instead of asking first').toEqual([]);

    // Dismiss, and confirm the page is exactly where it was — still editable, nothing sent. This is also what
    // keeps the shared database clean; see the block comment above.
    await dialog.getByRole('button', { name: 'ตรวจทานอีกครั้ง' }).click();
    await expect(dialog).toBeHidden();
    expect(postedSubmissions, 'dismissing the confirmation still filed something').toEqual([]);
    await expect(taxpayerId).toBeEnabled();
    await expect(submitButton).toBeEnabled();
  });
});
