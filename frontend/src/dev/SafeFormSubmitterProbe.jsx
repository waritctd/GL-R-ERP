import { useState } from 'react';
import { SafeForm } from '../components/common/SafeForm.jsx';

/**
 * e2e-only fixture (#safe-form-primitive review round, finding F2) — exercises SafeForm's
 * SUBMITTER guard in isolation from `canSubmit`.
 *
 * Why this exists: `e2e/implicit-submission.spec.js` drives `TicketCreateModal`, whose form is
 * `canSubmit`-gated on every view — the DETAILS view it presses Enter on is rejected by
 * `canSubmit` before SafeForm's separate submitter check is ever reached (confirmed by mutation:
 * neutering the submitter guard alone left that spec green; neutering `canSubmit` alone turned it
 * red). Every OTHER migrated form already has a real submit button in the DOM (verified per file
 * during the original migration), so none of them can exercise "no button anywhere" at the
 * browser level either. The submitter guard — the mechanism this whole primitive is built
 * around, the one that maps to the actual historical bug ("Enter in a lone field with literally
 * no submit button in the DOM") — had zero real-browser coverage without this fixture.
 *
 * Two forms, not one: PROBE has no submit button anywhere, `canSubmit` unset — Enter in its one
 * field must NOT submit. CONTROL is identical except for a real submit button, proving both that
 * the harness's own "submitted" marker actually works (so PROBE staying "not submitted" is
 * evidence of something, not a broken assertion) and that SafeForm still lets a legitimate
 * button-driven submission through right next to a form that structurally cannot submit at all.
 *
 * Registered ONLY when `VITE_USE_MOCKS === 'true'` (see App.jsx's route registration) — the same
 * flag CLAUDE.md documents as never `true` in a production build (no `VITE_` vars are set there
 * at all), so this route and this file are unreachable dead weight only in a local/mock/e2e
 * context, never in production.
 */
export function SafeFormSubmitterProbe() {
  const [probeSubmitted, setProbeSubmitted] = useState(false);
  const [controlSubmitted, setControlSubmitted] = useState(false);

  return (
    <div className="grid max-w-md gap-6 p-6">
      <h1 className="m-0 text-lg font-bold">SafeForm submitter-guard e2e probe</h1>

      <section className="grid gap-2">
        <h2 className="m-0 text-sm font-bold">PROBE — no submit button anywhere, no canSubmit</h2>
        {/* preventDefault in both handlers below is load-bearing, not boilerplate: SafeForm only
            blocks a submission it disallows -- one it DOES allow still runs the browser's real
            default action unless the caller's own onSubmit stops it, exactly like a plain <form>.
            Without this, a genuinely allowed submission here would trigger an actual full-page
            navigation (no `action`/`method` set, so the browser GETs the current URL), reloading
            the page and silently wiping this component's React state mid-test. */}
        <SafeForm data-testid="submitter-probe-form" onSubmit={(event) => { event.preventDefault(); setProbeSubmitted(true); }}>
          <label htmlFor="submitter-probe-field">only field</label>
          <input id="submitter-probe-field" type="text" defaultValue="x" />
        </SafeForm>
        <p data-testid="submitter-probe-result">{probeSubmitted ? 'SUBMITTED' : 'NOT SUBMITTED'}</p>
      </section>

      <section className="grid gap-2">
        <h2 className="m-0 text-sm font-bold">CONTROL — identical, but with a real submit button</h2>
        <SafeForm data-testid="submitter-control-form" onSubmit={(event) => { event.preventDefault(); setControlSubmitted(true); }}>
          <label htmlFor="submitter-control-field">only field</label>
          <input id="submitter-control-field" type="text" defaultValue="x" />
          <button type="submit">Go</button>
        </SafeForm>
        <p data-testid="submitter-control-result">{controlSubmitted ? 'SUBMITTED' : 'NOT SUBMITTED'}</p>
      </section>
    </div>
  );
}
