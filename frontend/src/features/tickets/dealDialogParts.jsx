import { useId } from 'react';

// The small building blocks the five rebuilt deal dialogs share (owner-approved drawings m-create / m-stage /
// m-hold / m-lost / m-cancel, 2026-11-16): a labelled field, radio cards, pick chips and the two notice boxes. One
// copy so the five dialogs read as one family. Tailwind only, existing tokens; every target is at least 44px tall.

/** A field label in the drawings' shape: "ชื่อ *" or "ชื่อ (ไม่บังคับ)". */
export function FieldLabel({ htmlFor, required = false, optional = false, children, id }) {
  return (
    <label htmlFor={htmlFor} id={id} className="m-0 block text-sm font-bold text-text-secondary">
      {children}
      {required ? <span className="text-danger" aria-hidden="true"> *</span> : null}
      {optional ? <em className="ml-1 font-semibold not-italic text-text-muted">(ไม่บังคับ)</em> : null}
    </label>
  );
}

/** An inline field error, wired by id to its control's aria-describedby. */
export function FieldError({ id, children }) {
  if (!children) return null;
  return <p id={id} role="alert" className="m-0 text-xs font-bold text-danger">{children}</p>;
}

/**
 * Radio cards (the drawings' `.rc`): a title and a one-line explanation per option. `options` is
 * `[{ value, title, sub, disabled }]`. A real radiogroup: arrow keys are not wired, but every card is its own button
 * with role="radio" and aria-checked, which is the pattern the rest of this app uses for card choices.
 */
export function RadioCards({ label, options, value, onChange, columns = 2 }) {
  const labelId = useId();
  return (
    <div className="grid gap-1.5">
      <span id={labelId} className="text-sm font-bold text-text-secondary">{label}</span>
      <div
        role="radiogroup"
        aria-labelledby={labelId}
        className={`grid gap-2 ${columns === 2 ? 'sm:grid-cols-2' : ''}`}
      >
        {options.map((option) => {
          const selected = option.value === value;
          return (
            <button
              key={option.value}
              type="button"
              role="radio"
              aria-checked={selected}
              disabled={option.disabled}
              onClick={() => onChange(option.value)}
              className={`flex min-h-11 items-start gap-3 rounded-lg border px-3 py-2.5 text-left disabled:cursor-not-allowed disabled:opacity-55 ${
                selected ? 'border-primary bg-primary/10' : 'border-border bg-surface hover:bg-surface-hover'
              }`}
            >
              <span
                aria-hidden="true"
                className={`mt-0.5 grid h-4 w-4 shrink-0 place-items-center rounded-full border ${
                  selected ? 'border-primary bg-primary' : 'border-border-strong bg-surface'
                }`}
              >
                {selected ? <span className="h-1.5 w-1.5 rounded-full bg-surface" /> : null}
              </span>
              <span className="grid min-w-0 gap-0.5">
                <b className="text-sm font-extrabold text-text">{option.title}</b>
                {option.sub ? <span className="text-xs text-text-muted">{option.sub}</span> : null}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Pick chips (the drawings' `.picks .pk`): one choice from a short list, each optionally led by a code (F1–F8).
 * `tone="danger"` colours the chosen chip as the lost / cancel dialogs draw it.
 */
export function PickChips({ label, options, value, onChange, tone = 'primary' }) {
  const chosen = tone === 'danger' ? 'border-danger bg-danger-bg text-danger-dark' : 'border-primary bg-primary/10 text-primary';
  return (
    <div role="radiogroup" aria-label={label} className="flex flex-wrap gap-2">
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={selected}
            onClick={() => onChange(option.value)}
            className={`inline-flex min-h-11 items-center gap-2 rounded-full border px-3.5 py-1.5 text-left text-sm font-semibold ${
              selected ? chosen : 'border-border bg-surface text-text hover:bg-surface-hover'
            }`}
          >
            {option.code ? (
              <em className={`not-italic text-xs font-extrabold ${selected ? '' : 'text-text-muted'}`}>{option.code}</em>
            ) : null}
            <span>{option.label}</span>
          </button>
        );
      })}
    </div>
  );
}

/** The drawings' `.info` box: how the deal moves on from here. */
export function InfoNote({ children, testId }) {
  return (
    <p data-testid={testId} className="m-0 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs leading-relaxed text-info-dark">
      {children}
    </p>
  );
}

/** The drawings' `.warn` box: what cannot be undone. */
export function WarnNote({ children, testId }) {
  return (
    <p data-testid={testId} role="note" className="m-0 rounded-lg border border-warning-border bg-warning-bg px-3 py-2.5 text-xs font-bold leading-relaxed text-warning-dark">
      {children}
    </p>
  );
}
