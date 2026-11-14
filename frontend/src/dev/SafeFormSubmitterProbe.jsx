import { useState } from 'react';
import { SafeForm } from '../components/common/SafeForm.jsx';

/** e2e-only fixture (#safe-form-primitive review round, finding F2) — exercises SafeForm's SUBMITTER guard in
 * isolation from `canSubmit`. Two forms, not one: PROBE has no submit button anywhere, `canSubmit` unset —
 * Enter in its one field must NOT submit. */
export function SafeFormSubmitterProbe() {
  const [probeSubmitted, setProbeSubmitted] = useState(false);
  const [controlSubmitted, setControlSubmitted] = useState(false);

  return (
    <div className="grid max-w-md gap-6 p-6">
      <h1 className="m-0 text-lg font-bold">SafeForm submitter-guard e2e probe</h1>

      <section className="grid gap-2">
        <h2 className="m-0 text-sm font-bold">PROBE — no submit button anywhere, no canSubmit</h2>
        {/* preventDefault in both handlers below is load-bearing, not boilerplate: SafeForm only blocks a submission it
         * disallows -- one it DOES allow still runs the browser's real default action unless the caller's own onSubmit
         * stops it, exactly like a plain <form>. */}
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
