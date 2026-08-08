import { forwardRef } from 'react';

/**
 * SafeForm — a `<form>` that closes off HTML "implicit submission" by construction for every
 * normal call site, instead of relying on each form being safe for its own local reasons (field
 * count, a hand-rolled gate someone remembered to add, etc.). "By construction", not
 * "impossible": `eslint.config.js`'s `no-restricted-syntax` ban on raw `<form>` JSX (the
 * enforcement half of this) catches the literal `<form>` element — the shape both historical
 * incidents actually were — not `React.createElement('form', ...)` or a dynamic JSX tag name.
 * Those remain visible in review; they are not silent regressions the way a raw `<form>` used to
 * be.
 *
 * THE BUG THIS EXISTS TO KILL: a `<form>` with no submit button anywhere in the document, but
 * exactly one field that blocks HTML's implicit-submission rule (a bare text/number/date/...
 * input — `<select>`, checkboxes, radios and `<textarea>` do NOT count), submits on Enter in
 * that field with no button ever pressed. This shipped to production twice — `/tax-allowance`
 * filed a real tax declaration (#tax-allowance-ia-hub-review), `TicketCreateModal` filed a real
 * CRM deal (fix/form-enter-submits-real-records) — both times because the form happened to carry
 * more than one such field until a later redesign narrowed it to one, and nothing structural
 * caught the change. `e2e/implicit-submission.spec.js` is the only test layer that can reproduce
 * this: jsdom does not implement the browser's implicit-submission algorithm at all (verified —
 * `fireEvent.keyDown`/`keyPress`/`keyUp('Enter')` on a field fires no submit event whatsoever
 * under jsdom), so a green vitest suite has never been evidence this class of bug is fixed.
 *
 * THE MECHANISM (verified empirically in real Chromium, not assumed — see this task's PR
 * description for the full walkthrough): a native 'submit' event carries `event.submitter`, the
 * element that caused the submission.
 *   - Click a submit button, or press Enter in a field while the form HAS a submit button —
 *     whether that button is a descendant of the `<form>` or lives elsewhere in the document and
 *     is linked via its own `form="<id>"` attribute (e.g. a modal footer button — both confirmed
 *     to count as "the form has a default button") — `submitter` is that button.
 *   - Press Enter in a field when the form has NO submit button anywhere — the form submits
 *     directly with no button ever activated — `submitter` is `null`. This is exactly and only
 *     the dangerous case, and it is a genuinely trusted event (`isTrusted: true`) — a real user
 *     action, not a script artifact.
 *   - `form.requestSubmit()` called with no argument — `submitter` is `null` too.
 *   - A submit button that IS the form's only default button but is currently `disabled` does
 *     NOT fall back to a submitter-less implicit submission — Enter simply does nothing (no
 *     'submit' event fires at all). A transiently-disabled submit button (e.g.
 *     `disabled={saving}`) does not need `allowSubmitterlessSubmit` on its own account.
 *
 * `canSubmit` is a RESTRICTION, never a permission — it can only narrow when a submission is
 * allowed, never widen it. Both checks below always apply: `canSubmit === false` blocks
 * regardless of submitter, and `canSubmit === true` (or omitted) still requires a real submitter
 * (or `allowSubmitterlessSubmit`). An earlier version of this primitive let `canSubmit === true`
 * BYPASS the submitter check entirely, reasoning that every origin call site's `canSubmit=true`
 * state always has a real submit button in the DOM too. That reasoning was wrong on inspection,
 * not just in theory: `TaxAllowanceForm`'s `canSubmit={isReview}` is `true` on REVIEW regardless
 * of `readOnly`, but `submitFooter` — REVIEW's only submit button — is `readOnly ? null : ...`,
 * and `?view=review` is reachable read-only via `TaxAllowancePage`'s own advertised shareable
 * deep link. REVIEW currently renders no input fields at all in that state, so nothing reaches
 * the bypass today — but that is "safe by accident of what REVIEW currently renders", the exact
 * failure mode this primitive exists to close, not an argument for keeping a bypass whose whole
 * justification was "a button is always there". A `canSubmit`-gated form gets no LESS protection
 * than a plain one; it only gets the additional, independent constraint of also being on the
 * right view/step.
 *
 * `event.nativeEvent.submitter == null` (loose, not `=== null`) is deliberate: a real browser
 * always delivers a genuine `SubmitEvent` whose `submitter` is `null` when absent, but
 * `@testing-library/dom`'s `fireEvent.submit(form)` dispatches a plain synthetic `Event`, which
 * has no `.submitter` property at all (`undefined`, not `null`). Loose equality treats both the
 * same, so `fireEvent.submit(form)` remains a faithful way to unit-test a BLOCKED submit — but
 * note it can never prove an ALLOWED one, because it can never carry a submitter; a test that
 * wants to show `canSubmit=true` actually lets a submission through has to dispatch an event that
 * carries one (a real button click, `form.requestSubmit(button)`, or a manually constructed
 * `new SubmitEvent('submit', { submitter, bubbles, cancelable })`) — see SafeForm.test.jsx and
 * this task's PR description for why the migrated forms' own tests needed the same treatment.
 *
 * Everything else is forwarded straight to the underlying `<form>` — `id`, `noValidate`,
 * `className`, `children`, and `ref` all behave exactly as they would on a raw `<form>`.
 */
export const SafeForm = forwardRef(function SafeForm(
  { onSubmit, canSubmit, allowSubmitterlessSubmit = false, ...props },
  ref,
) {
  function handleSubmit(event) {
    if (canSubmit !== undefined && canSubmit !== true) {
      event.preventDefault();
      warnBlocked(props, 'canSubmit is false');
      return;
    }

    if (event.nativeEvent.submitter == null && !allowSubmitterlessSubmit) {
      event.preventDefault();
      warnBlocked(props, 'no submitter — implicit submission with no button involved');
      return;
    }

    onSubmit?.(event);
  }

  // Not eslint-disabled here on purpose: eslint.config.js exempts this whole file from the
  // no-restricted-syntax ban on raw <form> JSX by path (files: ['.../SafeForm.jsx']) rather than
  // an inline directive, so there is exactly one place that grants the exemption, not one per
  // occurrence. This IS the primitive every other file's <form> is banned in favour of.
  return <form ref={ref} onSubmit={handleSubmit} {...props} />;
});

// Loud in development, silent in production: a blocked submit should be inert, not throw — a
// developer's mistaken form should not take down a real user's page — but a developer who hits
// the block while building deserves to know WHY their button did nothing instead of chasing a
// ghost. Mirrors Button.jsx's dev-loud/prod-quiet split for its own accessible-name check.
function warnBlocked(props, reason) {
  if (!import.meta.env.DEV) return;
  const label = props.id
    ? `#${props.id}`
    : props.className
      ? `.${String(props.className).split(' ')[0]}`
      : '(unlabelled — pass an id to make this warning more useful)';
  console.warn(
    `SafeForm ${label}: blocked a submit — ${reason}. This is the HTML "implicit submission" ` +
    'pattern that filed a real tax declaration and a real CRM deal before a submit button was ' +
    'ever clicked (see SafeForm.jsx for the history and mechanism). If a real submit button ' +
    'exists but lives outside this <form> (e.g. a modal footer), link it with the button\'s ' +
    'form="<id>" attribute instead of reaching for a bypass here. If this form genuinely needs a ' +
    'submitter-less programmatic submit, pass allowSubmitterlessSubmit with a comment at the ' +
    'call site explaining why. If this form should only submit from a specific step/view, pass ' +
    'canSubmit instead.',
  );
}
