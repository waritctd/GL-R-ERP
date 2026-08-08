import { test, expect } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

// #safe-form-primitive review round, finding F2: e2e/implicit-submission.spec.js drives
// TicketCreateModal, whose form is `canSubmit`-gated on every view -- the DETAILS view it presses
// Enter on is rejected by `canSubmit` before SafeForm's separate SUBMITTER guard is ever reached
// (confirmed by mutation: neutering the submitter guard alone left that spec green; neutering
// `canSubmit` alone turned it red). No currently-migrated production form has "no canSubmit, no
// submit button anywhere" as a reachable state either -- every one of them already has a real
// button in the DOM (verified per file during the original migration). So the mechanism this
// whole primitive is actually built around -- the one that maps to the real historical bug, "a
// lone Enter-blocking field with literally no submit button anywhere" -- had zero real-browser
// coverage. This spec drives `SafeFormSubmitterProbe.jsx` (registered only under
// VITE_USE_MOCKS='true', never in production) specifically to close that gap.
test('SafeForm submitter guard: Enter in a lone field with no submit button does not submit; the same field with a button does', async ({ page }) => {
  await loginAs(page, 'hr');
  await spaGoto(page, '/__e2e/safe-form-submitter-probe');

  const probeField = page.getByLabel('only field').first();
  const probeResult = page.getByTestId('submitter-probe-result');
  await expect(probeResult).toHaveText('NOT SUBMITTED');

  await probeField.fill('probe value');
  // Load-bearing wait, not flake-suppression (mirrors implicit-submission.spec.js's own note):
  // fill() dispatches a synthetic input event and returns before React's resulting re-render has
  // necessarily flushed; chasing it immediately with press('Enter') can race Chromium's own
  // implicit-submission field count against that still-in-flight commit.
  await page.waitForTimeout(300);
  await probeField.press('Enter');
  await page.waitForTimeout(300);

  // THE ASSERTION THIS WHOLE FIXTURE EXISTS FOR: no submit button anywhere in the DOM, no
  // canSubmit -- Enter must be fully absorbed by SafeForm's submitter guard.
  await expect(probeResult).toHaveText('NOT SUBMITTED');

  // Positive control, same page, same interaction, one difference (a real submit button): proves
  // the "SUBMITTED" marker actually works (so PROBE staying "NOT SUBMITTED" is evidence of
  // something) and that SafeForm does not also break a legitimate button-backed submission.
  const controlField = page.getByLabel('only field').nth(1);
  const controlResult = page.getByTestId('submitter-control-result');
  await expect(controlResult).toHaveText('NOT SUBMITTED');

  await controlField.fill('control value');
  await page.waitForTimeout(300);
  await controlField.press('Enter');

  await expect(controlResult).toHaveText('SUBMITTED');
});
