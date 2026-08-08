import { test, expect } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

// fix/form-enter-submits-real-records: HTML implicit submission — a <form> with no submit button
// but exactly ONE field that blocks implicit submission (a bare text input; <select>, checkboxes,
// radios and <textarea> do NOT block) submits when the user presses Enter in that field, with no
// button ever pressed. jsdom does not implement this algorithm at all, so a green vitest suite
// (TicketCreateModal.test.jsx's `fireEvent.submit(form)` cases) is evidence `handleFormSubmit`'s
// gate is wired correctly, but not that the browser's own implicit-submission heuristic is
// actually defused in practice — only a real browser engine reproduces that. This spec is the
// layer that can, and is the durable guard against the class of bug, not just this one instance.
//
// DETAILS (renderDetailsView) is the primary case TicketCreateModal.jsx:1429-1471 hit: its ชื่อดีล
// <input> (no `type` attribute, defaults to text) is the view's only Enter-blocking field — the
// <textarea> and role="radio" priority chips don't count toward the rule — and renderFooter()
// gives DETAILS no submit button at all. ลูกค้า + โครงการ are picked FIRST below, exactly like the
// bug report's own reproduction: those are validateTicketForm()'s only two required fields, so
// with both already valid the OLD unguarded submit() would have gone on to actually file the deal
// on Enter, not merely fail validation and redirect elsewhere. Skipping that setup would make this
// test pass whether or not the fix exists — see the mutation-check note in the PR description.
test('pressing Enter in ชื่อดีล on the DETAILS view does not create a deal', async ({ page }) => {
  await loginAs(page, 'sales');
  await spaGoto(page, '/tickets');

  await page.getByRole('button', { name: 'สร้างดีลใหม่' }).click();
  const modal = page.getByTestId('ticket-create-modal');
  await expect(modal).toBeVisible();

  // ── ลูกค้า + โครงการ: the only two fields validateTicketForm() requires (same fixture as
  // deal-creation.spec.js's own happy-path walk) ──────────────────────────────────────────
  await modal.getByRole('button', { name: 'ลูกค้า', exact: false }).first().click();
  await modal.locator('#customer-select').fill('ก้าวหน้า');
  await modal.getByText('บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด').click();
  await modal.getByRole('button', { name: 'กลับ' }).click();

  await modal.getByRole('button', { name: 'โครงการ', exact: false }).first().click();
  await modal.getByRole('button', { name: 'โครงการ Central Ladprao ชั้น B1' }).click();
  await modal.getByRole('button', { name: 'กลับ' }).click();

  // ── รายละเอียดดีล: type into ชื่อดีล, then press Enter instead of clicking anything ──────
  await modal.getByRole('button', { name: 'รายละเอียดดีล', exact: false }).first().click();
  const dealTitleInput = modal.getByLabel(/^ชื่อดีล/);
  await dealTitleInput.fill('Implicit submit probe');
  // This wait is load-bearing, not flake-suppression: `fill()` dispatches a synthetic input
  // event and returns immediately, before React's resulting re-render (setDealTitle -> commit)
  // has necessarily flushed. Chasing it immediately with `press('Enter')` races Chromium's own
  // implicit-submission field count against that still-in-flight commit and the Enter keypress
  // silently does nothing — found by chaining fill()+press('Enter') back-to-back during this
  // spec's own mutation-check: the gate-removed run stayed green because the interaction never
  // actually reached the browser's implicit-submission heuristic, not because anything defused
  // it. A real user's keystrokes are never this fast either.
  await page.waitForTimeout(300);
  await dealTitleInput.press('Enter');
  // Symmetric with the wait above, and equally load-bearing: `submit()` (when it runs at all)
  // is async — validate, then `await onSubmit(payload)` against the mock API, then navigate on
  // success. `expect(...).toHaveURL(...)` retries, but its FIRST check runs near-instantly, and
  // "still on /tickets" is also the truthful answer a heartbeat before a real submission's own
  // navigate() call lands — asserting immediately would pass on that transient state whether or
  // not a create was actually in flight, proving nothing either way. Waiting here first means
  // "no navigation happened" is checked once any real submission would already have finished.
  await page.waitForTimeout(500);

  // Observable consequences only — never internal `view` state. TicketListPage.handleCreate's
  // onSuccess always fires all three of these together (invalidate+navigate+toast), so proving
  // none of them happened is proof the create mutation itself never ran, not just that one
  // particular side effect was missed:
  // 1. no navigation to a ticket detail page (stays on the list route)
  await expect(page).toHaveURL(/\/tickets$/);
  // 2. no success toast ("สร้างดีลเรียบร้อย")
  await expect(page.getByText('สร้างดีลเรียบร้อย')).toHaveCount(0);
  // 3. the modal is still open exactly where the Enter keypress left it, with the typed value
  //    still sitting in the field — the keypress was fully absorbed by the gate, not partially
  //    processed (e.g. validated-then-redirected) before being dropped.
  await expect(modal).toBeVisible();
  await expect(dealTitleInput).toHaveValue('Implicit submit probe');
});
